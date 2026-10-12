package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionSeatOutcomeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SINGLE_HOLDER_COMMITTEE_ROLES
import network.lapis.cloud.shared.domain.SeatOutcome
import network.lapis.cloud.shared.domain.SeatOutcomeDto
import network.lapis.cloud.shared.domain.SeatOutcomeReason
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Welle V1.9.103 -- what an election tally did to a seat; stored in the audit snapshot's `trigger`. */
internal enum class ElectionSeatAuditTrigger { ELECTION_SEATED, ELECTION_SUCCESSION, ELECTION_ROLE_CHANGE }

/** One row of the succession log, before it is written to `election_seat_outcome`. */
internal data class ElectionSeatOutcomeRow(
    val memberId: Uuid,
    val outcome: SeatOutcome,
    val reason: SeatOutcomeReason?,
)

/** A `BoardMembership` created by the succession; the caller audits it (`auditBoardMembershipCreate`) as part of its last audit writes. */
internal data class SeatedBoardMembershipRef(
    val id: Uuid,
    val memberId: Uuid,
    val role: CommitteeRole,
    val startedAt: LocalDate,
)

/** Result of [ElectionSeatSuccession.apply] (phase 1); [recordAudits] is phase 2 and must run at the very end of the caller's transaction. */
internal class ElectionSeatSuccessionResult(
    val outcomes: List<ElectionSeatOutcomeRow>,
    val seatedBoardMemberships: List<SeatedBoardMembershipRef>,
    private val pendingAudits: List<PendingAudit>,
) {
    /** Phase 2 -- see [AuditLogRecorder]: the global hash-chain row lock must be the LAST row lock of the transaction. */
    fun recordAudits() {
        pendingAudits.forEach { it.write() }
    }
}

/**
 * Welle V1.9.103 "Gremien, Welle C" -- the ONE place where the tally of an election with an [IncumbentPolicy] turns winners into committee
 * seats and (REPLACE) ends the previous holders of the target role. Called from exactly one place: `tallyElectionLocked`. Elections without a
 * policy (`incumbent_policy IS NULL`: opened before this wave) keep the old seating code path byte for byte.
 *
 * **Never throws for a business reason.** A winner who is no longer an active member is [SeatOutcome.SKIPPED] (and, with REPLACE, the previous
 * holders are then kept: the Gremium may temporarily have too many seats, never none); the tally still completes. Only a programming error
 * (the UNIQUE key of `election_seat_outcome`) or a database error escapes, and then rolls the whole tally back.
 *
 * **Semantics.** `until` is INCLUSIVE everywhere in the eligibility code: on the stichtag (the day of the tally, organisation time zone)
 * both a predecessor (`until = stichtag`) and a winner (`since = stichtag`) count -- documented overlap, not corrected. Rows already ended are
 * never touched. A previous holder whose seat starts only after the stichtag is never touched either ([SeatOutcomeReason.SEAT_STARTS_LATER]).
 * `AccountRole` and `member.role` are never written: a seat gives committee rights only.
 *
 * **Lock order** (caller: motion, election): winner `member` rows (`forMemberUpdate`, by id), then the open seat rows of the Gremium
 * (`FOR UPDATE`, by id); audits are only collected and written by [ElectionSeatSuccessionResult.recordAudits].
 */
internal object ElectionSeatSuccession {
    private val logger = KotlinLogging.logger {}

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun apply(
        electionId: Uuid,
        committeeId: Uuid,
        committeeType: CommitteeType,
        role: CommitteeRole,
        policy: IncumbentPolicy,
        winnerMemberIds: List<Uuid>,
        stichtag: LocalDate,
        now: LocalDateTime,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
    ): ElectionSeatSuccessionResult {
        check(TransactionManager.currentOrNull() != null) {
            "ElectionSeatSuccession.apply must run inside an already-open transaction {} block"
        }
        requireNotRuleCommittee(committeeId)
        val winners = winnerMemberIds.distinct()
        val outcomes = mutableListOf<ElectionSeatOutcomeRow>()
        val audits = mutableListOf<PendingAudit>()
        val seatedBoard = mutableListOf<SeatedBoardMembershipRef>()

        // 1. lock the winners' member rows (by id) and read their status
        val statusByMember = lockedMemberStatuses(winners)
        // 2. lock the open seats of the Gremium (by id); ALL roles, because a winner may hold another role
        val openRows =
            CommitteeMembershipTable
                .selectAll()
                .where { (CommitteeMembershipTable.committeeId eq committeeId) and CommitteeMembershipTable.until.isNull() }
                .orderBy(CommitteeMembershipTable.id)
                .forUpdate()
                .toList()
        val openByMember = openRows.groupBy { it[CommitteeMembershipTable.memberId] }

        // 3. skipped winners
        val skipped = linkedMapOf<Uuid, SeatOutcomeReason>()
        winners.forEach { memberId ->
            val status = statusByMember[memberId]
            when {
                status == null || status !in MemberStatusSets.ORGANIZATION_MEMBER ->
                    skipped[memberId] = SeatOutcomeReason.MEMBER_NOT_ACTIVE
                openByMember[memberId].orEmpty().any { it[CommitteeMembershipTable.since] > stichtag } ->
                    skipped[memberId] = SeatOutcomeReason.SEAT_STARTS_LATER
            }
        }
        // A single-holder role whose current holder's seat starts later cannot be handed over: seating the winner would either leave two
        // open seats or make the single-holder displacement close the predecessor's board membership with an inverted interval.
        // So the winners are skipped and the board stays untouched.
        val singleHolderSeatStartsLater =
            policy == IncumbentPolicy.REPLACE &&
                role in SINGLE_HOLDER_COMMITTEE_ROLES &&
                openRows.any {
                    it[CommitteeMembershipTable.role] == role &&
                        it[CommitteeMembershipTable.memberId] !in winners &&
                        it[CommitteeMembershipTable.since] > stichtag
                }
        if (singleHolderSeatStartsLater) {
            winners.forEach { memberId -> skipped.putIfAbsent(memberId, SeatOutcomeReason.SEAT_STARTS_LATER) }
        }
        skipped.forEach { (memberId, reason) ->
            outcomes +=
                ElectionSeatOutcomeRow(memberId = memberId, outcome = SeatOutcome.SKIPPED, reason = reason)
        }
        val activeWinners = winners.filter { it !in skipped }.sortedBy { it.toString() }

        // 4./5./6./7. previous holders of the role
        if (policy == IncumbentPolicy.REPLACE) {
            val predecessors =
                openRows.filter {
                    it[CommitteeMembershipTable.role] == role && it[CommitteeMembershipTable.memberId] !in winners
                }
            val (notYetStarted, endable) = predecessors.partition { it[CommitteeMembershipTable.since] > stichtag }
            notYetStarted.forEach {
                outcomes +=
                    ElectionSeatOutcomeRow(
                        memberId = it[CommitteeMembershipTable.memberId],
                        outcome = SeatOutcome.PREDECESSOR_KEPT,
                        reason = SeatOutcomeReason.SEAT_STARTS_LATER,
                    )
            }
            if (skipped.isNotEmpty()) {
                endable.forEach {
                    outcomes +=
                        ElectionSeatOutcomeRow(
                            memberId = it[CommitteeMembershipTable.memberId],
                            outcome = SeatOutcome.PREDECESSOR_KEPT,
                            reason = SeatOutcomeReason.WINNER_SKIPPED,
                        )
                }
            } else {
                // Predecessors end BEFORE the winners are seated: otherwise recordBoardJoin would close a single-holder BoardMembership
                // itself, without the GoBD audit entry of the cascade.
                endable.forEach { row ->
                    val seatId = row[CommitteeMembershipTable.id]
                    val updated =
                        CommitteeMembershipTable.update({
                            (CommitteeMembershipTable.id eq seatId) and CommitteeMembershipTable.until.isNull()
                        }) {
                            it[until] = stichtag
                            it[endedByElectionId] = electionId
                        }
                    if (updated == 0) return@forEach // a parallel run already ended it
                    outcomes +=
                        ElectionSeatOutcomeRow(
                            memberId = row[CommitteeMembershipTable.memberId],
                            outcome = SeatOutcome.ENDED,
                            reason = null,
                        )
                    cascadeExecutiveBoardEnd(
                        committeeId = committeeId,
                        memberId = row[CommitteeMembershipTable.memberId],
                        until = stichtag,
                        now = now,
                        actorMemberId = actorMemberId,
                        actorRole = actorRole,
                    )?.let { audits += it }
                    audits +=
                        endAudit(
                            row = row,
                            committeeType = committeeType,
                            electionId = electionId,
                            stichtag = stichtag,
                            trigger = ElectionSeatAuditTrigger.ELECTION_SUCCESSION,
                            now = now,
                            actorMemberId = actorMemberId,
                            actorRole = actorRole,
                        )
                }
            }
        }

        // 8. the active winners
        activeWinners.forEach { memberId ->
            val own = openByMember[memberId].orEmpty()
            if (own.any { it[CommitteeMembershipTable.role] == role }) {
                outcomes += ElectionSeatOutcomeRow(memberId = memberId, outcome = SeatOutcome.CONTINUED, reason = null)
                return@forEach
            }
            own.forEach { row ->
                val seatId = row[CommitteeMembershipTable.id]
                val updated =
                    CommitteeMembershipTable.update({
                        (CommitteeMembershipTable.id eq seatId) and CommitteeMembershipTable.until.isNull()
                    }) {
                        it[until] = stichtag
                        it[endedByElectionId] = electionId
                    }
                if (updated > 0) {
                    audits +=
                        endAudit(
                            row = row,
                            committeeType = committeeType,
                            electionId = electionId,
                            stichtag = stichtag,
                            trigger = ElectionSeatAuditTrigger.ELECTION_ROLE_CHANGE,
                            now = now,
                            actorMemberId = actorMemberId,
                            actorRole = actorRole,
                        )
                }
            }
            val newSeatId = Uuid.random()
            CommitteeMembershipTable.insert {
                it[CommitteeMembershipTable.id] = newSeatId
                it[CommitteeMembershipTable.committeeId] = committeeId
                it[CommitteeMembershipTable.memberId] = memberId
                it[CommitteeMembershipTable.role] = role
                it[since] = stichtag
                it[until] = null
                it[startedByElectionId] = electionId
            }
            if (committeeType == CommitteeType.EXECUTIVE_BOARD) {
                val boardMembershipId =
                    BoardMembershipEvents.recordBoardJoin(memberId = memberId, role = role, startedAt = stichtag, now = now)
                seatedBoard += SeatedBoardMembershipRef(id = boardMembershipId, memberId = memberId, role = role, startedAt = stichtag)
            }
            audits +=
                startAudit(
                    seatId = newSeatId,
                    memberId = memberId,
                    committeeId = committeeId,
                    committeeType = committeeType,
                    role = role,
                    electionId = electionId,
                    stichtag = stichtag,
                    now = now,
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                )
            outcomes += ElectionSeatOutcomeRow(memberId = memberId, outcome = SeatOutcome.SEATED, reason = null)
        }

        // 9. the succession log; a UNIQUE violation is a programming error and rolls the tally back (deliberately not caught)
        outcomes.forEach { o ->
            ElectionSeatOutcomeTable.insert {
                it[ElectionSeatOutcomeTable.id] = Uuid.random()
                it[ElectionSeatOutcomeTable.electionId] = electionId
                it[ElectionSeatOutcomeTable.memberId] = o.memberId
                it[ElectionSeatOutcomeTable.committeeId] = committeeId
                it[ElectionSeatOutcomeTable.role] = role
                it[outcome] = o.outcome
                it[reason] = o.reason
                it[effectiveOn] = stichtag
                it[createdAt] = now
            }
        }
        logger.info {
            "Election succession: election=$electionId committee=$committeeId policy=$policy " +
                outcomes
                    .groupingBy { it.outcome }
                    .eachCount()
                    .toSortedMap()
                    .entries
                    .joinToString(",") { "${it.key}=${it.value}" }
        }
        return ElectionSeatSuccessionResult(outcomes = outcomes, seatedBoardMemberships = seatedBoard, pendingAudits = audits)
    }

    private fun endAudit(
        row: ResultRow,
        committeeType: CommitteeType,
        electionId: Uuid,
        stichtag: LocalDate,
        trigger: ElectionSeatAuditTrigger,
        now: LocalDateTime,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
    ): PendingAudit {
        val before =
            CommitteeMembershipAuditSnapshot(
                memberId = row[CommitteeMembershipTable.memberId].toString(),
                committeeId = row[CommitteeMembershipTable.committeeId].toString(),
                committeeType = committeeType,
                role = row[CommitteeMembershipTable.role],
                since = row[CommitteeMembershipTable.since],
                until = row[CommitteeMembershipTable.until],
                autoEndReason = row[CommitteeMembershipTable.autoEndReason],
                startedByElectionId = row[CommitteeMembershipTable.startedByElectionId]?.toString(),
            )
        val after = before.copy(until = stichtag, trigger = trigger.name, endedByElectionId = electionId.toString())
        val seatId = row[CommitteeMembershipTable.id]
        return PendingAudit {
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.COMMITTEE_MEMBERSHIP,
                entityId = seatId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(CommitteeMembershipAuditSnapshot.serializer(), before),
                after = Json.encodeToString(CommitteeMembershipAuditSnapshot.serializer(), after),
                occurredAt = now,
            )
        }
    }

    private fun startAudit(
        seatId: Uuid,
        memberId: Uuid,
        committeeId: Uuid,
        committeeType: CommitteeType,
        role: CommitteeRole,
        electionId: Uuid,
        stichtag: LocalDate,
        now: LocalDateTime,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
    ): PendingAudit {
        val after =
            CommitteeMembershipAuditSnapshot(
                memberId = memberId.toString(),
                committeeId = committeeId.toString(),
                committeeType = committeeType,
                role = role,
                since = stichtag,
                until = null,
                autoEndReason = null,
                trigger = ElectionSeatAuditTrigger.ELECTION_SEATED.name,
                startedByElectionId = electionId.toString(),
            )
        return PendingAudit {
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.COMMITTEE_MEMBERSHIP,
                entityId = seatId,
                action = AuditAction.CREATE,
                before = null,
                after = Json.encodeToString(CommitteeMembershipAuditSnapshot.serializer(), after),
                occurredAt = now,
            )
        }
    }
}

/**
 * Welle V1.9.103 -- reads the succession log of one election. Without [fullView] the administration-only outcomes
 * ([SeatOutcome.SKIPPED], [SeatOutcome.PREDECESSOR_KEPT]) are dropped BEFORE they are mapped: their member ids and reasons never leave
 * the server, only the flag "pending admin review" does. The names of seated/continued/ended holders are exactly as visible as the
 * roster of the Gremium (`listCommitteeMembers`), which every signed-in member may read. Must run inside an open transaction.
 */
internal object ElectionSeatOutcomes {
    fun load(
        electionId: Uuid,
        fullView: Boolean,
    ): Pair<List<SeatOutcomeDto>, Boolean> {
        val rows =
            (ElectionSeatOutcomeTable innerJoin MemberTable innerJoin CommitteeTable)
                .selectAll()
                .where { ElectionSeatOutcomeTable.electionId eq electionId }
                .orderBy(ElectionSeatOutcomeTable.outcome to SortOrder.ASC, ElectionSeatOutcomeTable.memberId to SortOrder.ASC)
                .toList()
        val adminOnly = setOf(SeatOutcome.SKIPPED, SeatOutcome.PREDECESSOR_KEPT)
        val visible = if (fullView) rows else rows.filter { it[ElectionSeatOutcomeTable.outcome] !in adminOnly }
        val dtos =
            visible.map { row ->
                SeatOutcomeDto(
                    memberId = row[ElectionSeatOutcomeTable.memberId].toString(),
                    memberDisplayName = row[MemberTable.displayName],
                    committeeId = row[ElectionSeatOutcomeTable.committeeId].toString(),
                    committeeName = row[CommitteeTable.name],
                    role = row[ElectionSeatOutcomeTable.role],
                    outcome = row[ElectionSeatOutcomeTable.outcome],
                    reason = row[ElectionSeatOutcomeTable.reason],
                    effectiveOn = row[ElectionSeatOutcomeTable.effectiveOn],
                )
            }
        return dtos to (!fullView && visible.size != rows.size)
    }
}
