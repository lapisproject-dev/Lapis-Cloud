package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rules.RuleReferences
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeModeChangeBlock
import network.lapis.cloud.shared.domain.CommitteeModeChangeImpactDto
import network.lapis.cloud.shared.domain.CommitteeRuleChangeSnapshot
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 "regelbasierte Gremien" -- everything that changes or inspects the membership mode of a committee, kept out of the
 * already very large [GovernanceService]. Every function runs inside the caller's open `transaction {}` unless stated otherwise.
 *
 * **Lock order of this wave.** Committee rows in the database's own `ORDER BY id` (own row plus every referenced committee,
 * [RuleReferences.lockAndValidate]); then the seat rows ([CommitteeSeatLifecycle.endSeatsForRuleMode], by id); the webhook outbox; and the
 * `COMMITTEE` audit entry as the LAST locking operation. A member row is never locked by this wave (`addCommitteeMember` locks member then
 * committee, this wave only committees then seats -- no cycle).
 */
internal object CommitteeRuleOps {
    private val RULE_FORBIDDEN_TYPES = setOf(CommitteeType.GENERAL_ASSEMBLY, CommitteeType.EXECUTIVE_BOARD)
    private val FINISHED_ELECTION = listOf(ElectionStatus.TALLIED, ElectionStatus.ABORTED)
    private val FINISHED_CONSENSUS = listOf(SystemicConsensusStatus.EVALUATED, SystemicConsensusStatus.ABORTED)

    /** Strict parse for the committee context. The message is fixed text -- never any part of the rejected rule. */
    fun parseOrBadRequest(ruleJson: String): MemberRule =
        try {
            MemberRuleCodec.parse(text = ruleJson, context = RuleContext.COMMITTEE)
        } catch (e: InvalidMemberRuleException) {
            throw BadRequestException("Ungültige Regel.")
        }

    /** A rule-based committee can be neither the General Assembly nor the executive board. */
    fun requireTypeAllowsRule(type: CommitteeType) {
        if (type in RULE_FORBIDDEN_TYPES) throw BadRequestException("Regelgremien sind für diesen Gremientyp nicht möglich.")
    }

    /**
     * Why the mode / rule of [committeeRow] cannot be changed right now (null = it can). Checked in a fixed order. Rules, see the
     * wave notes: only a meeting that has begun (PLANNED and due), an open vote, an unfinished election or consensus of one of the
     * committee's meetings, an unfinished election that targets the committee, and (switching INTO rule mode) a rule that references it.
     */
    fun blockOf(
        committeeRow: ResultRow,
        targetMode: CommitteeMembershipMode,
        wallNow: LocalDateTime,
    ): CommitteeModeChangeBlock? {
        val id = committeeRow[CommitteeTable.id]
        if (targetMode == CommitteeMembershipMode.RULE && committeeRow[CommitteeTable.type] in RULE_FORBIDDEN_TYPES) {
            return CommitteeModeChangeBlock.TYPE_NOT_ALLOWED
        }
        val meetingStarted =
            MeetingTable
                .selectAll()
                .where {
                    (MeetingTable.committeeId eq id) and
                        (MeetingTable.status eq MeetingStatus.PLANNED) and
                        (MeetingTable.scheduledAt lessEq wallNow)
                }.limit(1)
                .count() > 0
        if (meetingStarted) return CommitteeModeChangeBlock.MEETING_IN_PROGRESS
        val openVote =
            (VoteTable innerJoin MeetingTable)
                .selectAll()
                .where { (MeetingTable.committeeId eq id) and (VoteTable.status eq VoteStatus.OPEN) }
                .limit(1)
                .count() > 0
        val openElection =
            (ElectionTable innerJoin MeetingTable)
                .selectAll()
                .where { (MeetingTable.committeeId eq id) and (ElectionTable.status notInList FINISHED_ELECTION) }
                .limit(1)
                .count() > 0
        val openConsensus =
            (SystemicConsensusTable innerJoin MeetingTable)
                .selectAll()
                .where { (MeetingTable.committeeId eq id) and (SystemicConsensusTable.status notInList FINISHED_CONSENSUS) }
                .limit(1)
                .count() > 0
        if (openVote || openElection || openConsensus) return CommitteeModeChangeBlock.OPEN_DECISION
        val electionTargets =
            ElectionTable
                .selectAll()
                .where { (ElectionTable.targetCommitteeId eq id) and (ElectionTable.status notInList FINISHED_ELECTION) }
                .limit(1)
                .count() > 0
        if (electionTargets) return CommitteeModeChangeBlock.ELECTION_TARGETS_COMMITTEE
        if (targetMode == CommitteeMembershipMode.RULE &&
            committeeRow[CommitteeTable.membershipMode] == CommitteeMembershipMode.MANUAL &&
            RuleReferences.countReferencesTo(id) > 0
        ) {
            return CommitteeModeChangeBlock.REFERENCED_BY_RULE
        }
        return null
    }

    /** Seats that would end when the committee is switched to rule mode: `until IS NULL OR until >= today`. */
    fun openSeatCount(
        committeeId: Uuid,
        today: LocalDate,
    ): Int =
        CommitteeMembershipTable
            .selectAll()
            .where {
                (CommitteeMembershipTable.committeeId eq committeeId) and
                    (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq today))
            }.count()
            .toInt()

    /** `getCommitteeModeChangeImpact` -- read only. */
    fun impact(
        committeeId: Uuid,
        targetMode: CommitteeMembershipMode,
    ): CommitteeModeChangeImpactDto {
        val now = DbClock.nowLocalDateTime()
        val row =
            CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.singleOrNull()
                ?: throw NotFoundException("Committee $committeeId not found")
        return CommitteeModeChangeImpactDto(
            openSeats = openSeatCount(committeeId = committeeId, today = OrganizationTimeZone.dateOf(now)),
            blocked = blockOf(committeeRow = row, targetMode = targetMode, wallNow = OrganizationTimeZone.wallNowOf(now)),
        )
    }

    /**
     * `setCommitteeMembershipRule` -- the transaction body. [rule] is non-null exactly for [CommitteeMembershipMode.RULE]. Writes the
     * mode/rule, ends the open seats when switching into rule mode, publishes `COMMITTEE_UPDATED` and records ONE `COMMITTEE` audit entry
     * (the last locking operation) with counts only. A request that changes nothing writes nothing.
     */
    fun setMembershipRule(
        current: CurrentMember,
        committeeId: Uuid,
        mode: CommitteeMembershipMode,
        rule: MemberRule?,
    ): CommitteeDto {
        val canonical = rule?.let { MemberRuleCodec.encode(it) }
        val now = DbClock.nowLocalDateTime()
        val today = OrganizationTimeZone.dateOf(now)
        // own row + every referenced committee, locked in the database's id order BEFORE anything is read or checked
        if (rule != null) {
            RuleReferences.lockAndValidate(rule = rule, context = RuleContext.COMMITTEE, selfCommitteeId = committeeId)
        }
        val row =
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq committeeId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("Committee $committeeId not found")
        val blocked = blockOf(committeeRow = row, targetMode = mode, wallNow = OrganizationTimeZone.wallNowOf(now))
        if (blocked != null) throw ConflictException(blocked.name)
        val modeBefore = row[CommitteeTable.membershipMode]
        val ruleBefore = row[CommitteeTable.ruleJson]
        if (modeBefore == mode && ruleBefore == canonical) return committeeDto(committeeId)
        val endedSeats =
            if (modeBefore == CommitteeMembershipMode.MANUAL && mode == CommitteeMembershipMode.RULE) {
                CommitteeSeatLifecycle.endSeatsForRuleMode(committeeId = committeeId, today = today)
            } else {
                0
            }
        CommitteeTable.update({ CommitteeTable.id eq committeeId }) {
            it[membershipMode] = mode
            it[ruleJson] = canonical
        }
        val eligibleCount = rule?.let { eligibleIdsForRule(rule = it, today = today).size }
        WebhookEventPublisher.publish(eventType = WebhookEventType.COMMITTEE_UPDATED, entityId = committeeId, occurredAt = now)
        val event = if (modeBefore != mode) "COMMITTEE_MODE_CHANGED" else "COMMITTEE_RULE_SET"
        recordAudit(
            current = current,
            committeeId = committeeId,
            action = AuditAction.UPDATE,
            before = CommitteeRuleChangeSnapshot(event = event, mode = modeBefore, ruleJson = ruleBefore),
            after =
                CommitteeRuleChangeSnapshot(
                    event = event,
                    mode = mode,
                    ruleJson = canonical,
                    eligibleCount = eligibleCount,
                    endedSeats = endedSeats,
                ),
            now = now,
        )
        return committeeDto(committeeId)
    }

    /** The audit entry of the creation of a rule-based committee (counts and the canonical rule only). Call it as the last locking write. */
    fun auditCreatedRuleCommittee(
        current: CurrentMember,
        committeeId: Uuid,
        rule: MemberRule,
        now: LocalDateTime,
    ) {
        recordAudit(
            current = current,
            committeeId = committeeId,
            action = AuditAction.CREATE,
            before = null,
            after =
                CommitteeRuleChangeSnapshot(
                    event = "COMMITTEE_RULE_SET",
                    mode = CommitteeMembershipMode.RULE,
                    ruleJson = MemberRuleCodec.encode(rule),
                    eligibleCount = eligibleIdsForRule(rule = rule, today = OrganizationTimeZone.dateOf(now)).size,
                    endedSeats = 0,
                ),
            now = now,
        )
    }

    private fun recordAudit(
        current: CurrentMember,
        committeeId: Uuid,
        action: AuditAction,
        before: CommitteeRuleChangeSnapshot?,
        after: CommitteeRuleChangeSnapshot,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.COMMITTEE,
            entityId = committeeId,
            action = action,
            before = before?.let { Json.encodeToString(CommitteeRuleChangeSnapshot.serializer(), it) },
            after = Json.encodeToString(CommitteeRuleChangeSnapshot.serializer(), after),
            occurredAt = now,
        )
    }

    private fun committeeDto(committeeId: Uuid): CommitteeDto =
        CommitteeTable
            .selectAll()
            .where { CommitteeTable.id eq committeeId }
            .single()
            .toCommitteeDto(revealRule = true)
}
