package network.lapis.cloud.server.meetingreward

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyChangeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.PeerGuard
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingRewardBlockedReason
import network.lapis.cloud.shared.domain.MeetingRewardPolicyAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingRewardPolicyAuditValues
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.MeetingRewardPolicyProposalInput
import network.lapis.cloud.shared.domain.MeetingRewardPolicyValuesDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyViewDto
import network.lapis.cloud.shared.domain.MeetingRewardResolutionOptionDto
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.316 -- the rules of the LTR recognition and how they may change. Every method must run inside an open `transaction {}` and opens none; each
 * writing method locks the singleton policy row FIRST (`MeetingRewardStore.lockPolicy`) and records its audit entry as the last write.
 *
 *  * **propose**: any administrator. A CHANGE (this is the only way to switch on) cites an ADOPTED resolution of the general assembly and stores the old
 *    and the new figures; at most one proposal is open.
 *  * **confirm**: a SECOND administrator (`decided_by <> proposed_by`) who has held the role for at least `PeerGuard.ADMIN_TENURE` at the time of the
 *    proposal (otherwise one administrator could appoint a puppet administrator and confirm their own proposal). A proposal whose "old" figures no
 *    longer equal the figures in force is STALE and refused.
 *  * **withdraw**: any administrator. **disable**: one administrator suffices (switching off can never create LTR).
 * No log line carries a name, an amount or a text -- only ids.
 */
internal object MeetingRewardPolicyOperations {
    fun propose(
        current: CurrentMember,
        input: MeetingRewardPolicyProposalInput,
    ): Uuid {
        val policy = MeetingRewardStore.lockPolicy()
        if (policy[MeetingRewardPolicyTable.openChangeId] != null) throw ConflictException(POLICY_PROPOSAL_OPEN)
        val values = validateValues(input.values)
        val resolutionId =
            runCatching { Uuid.parse(input.resolutionId) }.getOrNull()
                ?: throw ConflictException(if (values.enabled) ENABLE_NEEDS_RESOLUTION else RESOLUTION_INVALID)
        requireGeneralAssemblyResolution(resolutionId)
        val reference = input.resolutionReference?.trim()?.takeIf { it.isNotEmpty() }
        if (reference != null && reference.length > MAX_REFERENCE_LENGTH) throw ConflictException(REFERENCE_TOO_LONG)
        if (values.committeeIds.isNotEmpty()) {
            val known = CommitteeTable.selectAll().where { CommitteeTable.id inList values.committeeIds }.count()
            if (known.toInt() != values.committeeIds.size) throw ConflictException(COMMITTEE_INVALID)
        }
        val old = MeetingRewardStore.valuesOf(policy)
        val now = DbClock.nowLocalDateTime()
        val id = Uuid.random()
        MeetingRewardStore.insertChange(
            id = id,
            kind = MeetingRewardPolicyChangeKind.CHANGE,
            status = MeetingRewardPolicyChangeStatus.PROPOSED,
            proposedBy = current.memberId,
            proposedAt = now,
            decidedBy = null,
            decidedAt = null,
            resolutionId = resolutionId,
            resolutionReference = reference,
            old = old,
            new = values,
        )
        MeetingRewardStore.setOpenChange(id)
        audit(
            current = current,
            changeId = id,
            event = "PROPOSED",
            kind = MeetingRewardPolicyChangeKind.CHANGE,
            resolutionId = resolutionId,
            resolutionReference = reference,
            old = old,
            new = values,
        )
        logger.info { "meeting reward policy change $id proposed" }
        return id
    }

    fun confirm(
        current: CurrentMember,
        changeId: Uuid,
    ) {
        val policy = MeetingRewardStore.lockPolicy()
        val change = MeetingRewardStore.changeRow(id = changeId, forUpdate = true) ?: throw NotFoundException("Not found")
        if (change[MeetingRewardPolicyChangeTable.status] != MeetingRewardPolicyChangeStatus.PROPOSED ||
            policy[MeetingRewardPolicyTable.openChangeId] != changeId
        ) {
            throw ConflictException(NOT_AVAILABLE)
        }
        val proposer = change[MeetingRewardPolicyChangeTable.proposedBy]
        if (proposer == current.memberId) throw ConflictException(FOUR_EYES_REQUIRED)
        val proposedAt = change[MeetingRewardPolicyChangeTable.proposedAt]
        // The confirming administrator's account row is locked (after the policy and the change row) so the tenure facts cannot change under us.
        val accountRows =
            AccountTable
                .selectAll()
                .where { AccountTable.memberId eq current.memberId }
                .forUpdate()
                .toList()
        val tenured =
            PeerGuard.eligibleApproverIds(lockedAdminRows = accountRows, excluding = setOf(proposer), requestCreatedAt = proposedAt)
        if (current.memberId !in tenured) throw ConflictException(APPROVER_NOT_TENURED)
        val currentValues = MeetingRewardStore.valuesOf(policy)
        if (!currentValues.sameAs(MeetingRewardStore.oldValuesOf(change))) throw ConflictException(POLICY_PROPOSAL_STALE)
        val new = MeetingRewardStore.newValuesOf(change)
        val now = DbClock.nowLocalDateTime()
        if (MeetingRewardStore.markChangeConfirmed(id = changeId, by = current.memberId, at = now) !=
            1
        ) {
            throw ConflictException(CONCURRENT_CHANGE)
        }
        MeetingRewardStore.applyPolicy(
            values = new,
            changeId = changeId,
            resolutionId = change[MeetingRewardPolicyChangeTable.resolutionId],
            resolutionReference = change[MeetingRewardPolicyChangeTable.resolutionReference],
            keepResolution = false,
            at = now,
        )
        MeetingRewardStore.replaceCommittees(ids = new.committeeIds, changeId = changeId, at = now)
        MeetingRewardStore.setOpenChange(null)
        audit(
            current = current,
            changeId = changeId,
            event = "CONFIRMED",
            kind = MeetingRewardPolicyChangeKind.CHANGE,
            resolutionId = change[MeetingRewardPolicyChangeTable.resolutionId],
            resolutionReference = change[MeetingRewardPolicyChangeTable.resolutionReference],
            old = currentValues,
            new = new,
        )
        logger.info { "meeting reward policy change $changeId confirmed" }
    }

    fun withdraw(
        current: CurrentMember,
        changeId: Uuid,
    ) {
        val policy = MeetingRewardStore.lockPolicy()
        val change = MeetingRewardStore.changeRow(id = changeId, forUpdate = true) ?: throw NotFoundException("Not found")
        if (change[MeetingRewardPolicyChangeTable.status] != MeetingRewardPolicyChangeStatus.PROPOSED ||
            policy[MeetingRewardPolicyTable.openChangeId] != changeId
        ) {
            throw ConflictException(NOT_AVAILABLE)
        }
        val now = DbClock.nowLocalDateTime()
        if (MeetingRewardStore.markChangeWithdrawn(id = changeId, by = current.memberId, at = now) !=
            1
        ) {
            throw ConflictException(CONCURRENT_CHANGE)
        }
        MeetingRewardStore.setOpenChange(null)
        audit(
            current = current,
            changeId = changeId,
            event = "WITHDRAWN",
            kind = MeetingRewardPolicyChangeKind.CHANGE,
            resolutionId = change[MeetingRewardPolicyChangeTable.resolutionId],
            resolutionReference = change[MeetingRewardPolicyChangeTable.resolutionReference],
            old = MeetingRewardStore.oldValuesOf(change),
            new = MeetingRewardStore.newValuesOf(change),
        )
        logger.info { "meeting reward policy change $changeId withdrawn" }
    }

    /** Switches the recognition off. Idempotent: when it is already off nothing is written. A proposal that is open stays open and becomes STALE. */
    fun disable(current: CurrentMember) {
        val policy = MeetingRewardStore.lockPolicy()
        if (!policy[MeetingRewardPolicyTable.enabled]) return
        val old = MeetingRewardStore.valuesOf(policy)
        val new = old.copy(enabled = false)
        val now = DbClock.nowLocalDateTime()
        val id = Uuid.random()
        MeetingRewardStore.insertChange(
            id = id,
            kind = MeetingRewardPolicyChangeKind.DISABLE,
            status = MeetingRewardPolicyChangeStatus.CONFIRMED,
            proposedBy = current.memberId,
            proposedAt = now,
            decidedBy = current.memberId,
            decidedAt = now,
            resolutionId = null,
            resolutionReference = null,
            old = old,
            new = new,
        )
        MeetingRewardStore.applyPolicy(
            values = new,
            changeId = id,
            resolutionId = null,
            resolutionReference = null,
            keepResolution = true,
            at = now,
        )
        audit(
            current = current,
            changeId = id,
            event = "DISABLED",
            kind = MeetingRewardPolicyChangeKind.DISABLE,
            resolutionId = null,
            resolutionReference = null,
            old = old,
            new = new,
        )
        logger.info { "meeting reward switched off by change $id" }
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────────

    /** The administrator's view. [canConfirmOpen] is a hint for the button; the real checks run in [confirm]. */
    fun view(current: CurrentMember): MeetingRewardPolicyViewDto {
        val policy = MeetingRewardStore.policyRow()
        val values = MeetingRewardStore.valuesOf(policy)
        val changes = MeetingRewardStore.history(HISTORY_LIMIT)
        val names =
            memberNames(
                changes
                    .flatMap {
                        listOfNotNull(
                            it[MeetingRewardPolicyChangeTable.proposedBy],
                            it[MeetingRewardPolicyChangeTable.decidedBy],
                        )
                    }.toSet(),
            )
        val resolutions =
            resolutionOptions(
                changes.mapNotNull { it[MeetingRewardPolicyChangeTable.resolutionId] } +
                    listOfNotNull(policy[MeetingRewardPolicyTable.resolutionId]),
            )
        val dtos = changes.map { changeDto(row = it, names = names, resolutions = resolutions) }
        val open = policy[MeetingRewardPolicyTable.openChangeId]?.let { openId -> dtos.firstOrNull { it.id == openId.toString() } }
        var blocked: MeetingRewardBlockedReason? = null
        var canConfirm = false
        if (open != null) {
            val changeRow = changes.first { it[MeetingRewardPolicyChangeTable.id].toString() == open.id }
            val proposer = changeRow[MeetingRewardPolicyChangeTable.proposedBy]
            val rows = AccountTable.selectAll().where { AccountTable.memberId eq current.memberId }.toList()
            blocked =
                when {
                    !current.isAdmin -> MeetingRewardBlockedReason.NOT_ADMIN
                    proposer == current.memberId -> MeetingRewardBlockedReason.FOUR_EYES
                    current.memberId !in
                        PeerGuard.eligibleApproverIds(
                            lockedAdminRows = rows,
                            excluding = setOf(proposer),
                            requestCreatedAt = changeRow[MeetingRewardPolicyChangeTable.proposedAt],
                        ) ->
                        MeetingRewardBlockedReason.APPROVER_NOT_TENURED
                    else -> null
                }
            canConfirm = blocked == null
        }
        return MeetingRewardPolicyViewDto(
            current = valuesDto(values),
            resolution = policy[MeetingRewardPolicyTable.resolutionId]?.let { resolutions[it] },
            resolutionReference = policy[MeetingRewardPolicyTable.resolutionReference],
            openProposal = open,
            canConfirmOpen = canConfirm,
            confirmBlockedReason = blocked,
            history = dtos,
        )
    }

    /** ADOPTED resolutions of a meeting of the general assembly, newest first (capped). */
    fun resolutionOptions(): List<MeetingRewardResolutionOptionDto> {
        val assemblyMeetings =
            MeetingTable
                .innerJoin(CommitteeTable)
                .select(MeetingTable.id)
                .where { CommitteeTable.type eq CommitteeType.GENERAL_ASSEMBLY }
                .map { it[MeetingTable.id] }
        if (assemblyMeetings.isEmpty()) return emptyList()
        return ResolutionTable
            .selectAll()
            .where { (ResolutionTable.status eq ResolutionStatus.ADOPTED) and (ResolutionTable.meetingId inList assemblyMeetings) }
            .orderBy(ResolutionTable.decidedAt to SortOrder.DESC)
            .limit(RESOLUTION_OPTION_LIMIT)
            .map { optionOf(it) }
    }

    private fun optionOf(row: ResultRow) =
        MeetingRewardResolutionOptionDto(
            resolutionId = row[ResolutionTable.id].toString(),
            number = row[ResolutionTable.number],
            title = row[ResolutionTable.title],
            decidedAt = row[ResolutionTable.decidedAt],
        )

    private fun resolutionOptions(ids: Collection<Uuid>): Map<Uuid, MeetingRewardResolutionOptionDto> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            ResolutionTable.selectAll().where { ResolutionTable.id inList ids.toSet() }.associate { it[ResolutionTable.id] to optionOf(it) }
        }

    private fun memberNames(ids: Set<Uuid>): Map<Uuid, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            MemberTable.selectAll().where { MemberTable.id inList ids }.associate { it[MemberTable.id] to it[MemberTable.displayName] }
        }

    private fun changeDto(
        row: ResultRow,
        names: Map<Uuid, String>,
        resolutions: Map<Uuid, MeetingRewardResolutionOptionDto>,
    ) = MeetingRewardPolicyChangeDto(
        id = row[MeetingRewardPolicyChangeTable.id].toString(),
        kind = row[MeetingRewardPolicyChangeTable.kind],
        status = row[MeetingRewardPolicyChangeTable.status],
        proposedByName = names[row[MeetingRewardPolicyChangeTable.proposedBy]].orEmpty(),
        proposedAt = row[MeetingRewardPolicyChangeTable.proposedAt],
        decidedByName = row[MeetingRewardPolicyChangeTable.decidedBy]?.let { names[it].orEmpty() },
        decidedAt = row[MeetingRewardPolicyChangeTable.decidedAt],
        resolution = row[MeetingRewardPolicyChangeTable.resolutionId]?.let { resolutions[it] },
        resolutionReference = row[MeetingRewardPolicyChangeTable.resolutionReference],
        old = valuesDto(MeetingRewardStore.oldValuesOf(row)),
        new = valuesDto(MeetingRewardStore.newValuesOf(row)),
    )

    // ── validation, mapping, audit ───────────────────────────────────────────────────────────────────

    /** Validates a proposal's figures (scale 2, ranges) and the committee list (distinct, parsable, capped). */
    fun validateValues(dto: MeetingRewardPolicyValuesDto): MeetingRewardPolicyValues {
        val committeeIds =
            dto.committeeIds
                .map { runCatching { Uuid.parse(it) }.getOrNull() ?: throw ConflictException(COMMITTEE_INVALID) }
                .toSet()
        if (committeeIds.size > MAX_COMMITTEES) throw ConflictException(COMMITTEE_INVALID)
        return MeetingRewardPolicyValues(
            enabled = dto.enabled,
            attendanceAmount = validateAmount(value = dto.attendanceAmount, max = MAX_BONUS_OR_BASE),
            bonusChair = validateAmount(value = dto.bonusChair, max = MAX_BONUS_OR_BASE),
            bonusDeputyChair = validateAmount(value = dto.bonusDeputyChair, max = MAX_BONUS_OR_BASE),
            bonusSecretary = validateAmount(value = dto.bonusSecretary, max = MAX_BONUS_OR_BASE),
            bonusManagingDirector = validateAmount(value = dto.bonusManagingDirector, max = MAX_BONUS_OR_BASE),
            capPerPersonPerYear = validateAmount(value = dto.capPerPersonPerYear, max = MAX_CAP),
            capPerMeeting = validateAmount(value = dto.capPerMeeting, max = MAX_CAP),
            committeeIds = committeeIds,
        )
    }

    /** The resolution must exist, be ADOPTED and have been taken in a meeting of the general assembly. */
    private fun requireGeneralAssemblyResolution(resolutionId: Uuid) {
        val resolution =
            ResolutionTable.selectAll().where { ResolutionTable.id eq resolutionId }.singleOrNull()
                ?: throw ConflictException(RESOLUTION_INVALID)
        if (resolution[ResolutionTable.status] != ResolutionStatus.ADOPTED) throw ConflictException(RESOLUTION_INVALID)
        val committeeType =
            MeetingTable
                .innerJoin(CommitteeTable)
                .select(CommitteeTable.type)
                .where { MeetingTable.id eq resolution[ResolutionTable.meetingId] }
                .singleOrNull()
                ?.get(CommitteeTable.type)
        if (committeeType != CommitteeType.GENERAL_ASSEMBLY) throw ConflictException(RESOLUTION_INVALID)
    }

    fun valuesDto(v: MeetingRewardPolicyValues) =
        MeetingRewardPolicyValuesDto(
            enabled = v.enabled,
            attendanceAmount = v.attendanceAmount,
            bonusChair = v.bonusChair,
            bonusDeputyChair = v.bonusDeputyChair,
            bonusSecretary = v.bonusSecretary,
            bonusManagingDirector = v.bonusManagingDirector,
            capPerPersonPerYear = v.capPerPersonPerYear,
            capPerMeeting = v.capPerMeeting,
            committeeIds = v.committeeIds.map { it.toString() }.sorted(),
        )

    private fun plain(v: BigDecimal) = v.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()

    private fun auditValues(v: MeetingRewardPolicyValues) =
        MeetingRewardPolicyAuditValues(
            enabled = v.enabled,
            attendanceAmount = plain(v.attendanceAmount),
            bonusChair = plain(v.bonusChair),
            bonusDeputyChair = plain(v.bonusDeputyChair),
            bonusSecretary = plain(v.bonusSecretary),
            bonusManagingDirector = plain(v.bonusManagingDirector),
            capPerPersonPerYear = plain(v.capPerPersonPerYear),
            capPerMeeting = plain(v.capPerMeeting),
        )

    private fun audit(
        current: CurrentMember,
        changeId: Uuid,
        event: String,
        kind: MeetingRewardPolicyChangeKind,
        resolutionId: Uuid?,
        resolutionReference: String?,
        old: MeetingRewardPolicyValues,
        new: MeetingRewardPolicyValues,
    ) {
        val snapshot =
            MeetingRewardPolicyAuditSnapshot(
                event = event,
                changeId = changeId.toString(),
                kind = kind,
                resolutionId = resolutionId?.toString(),
                resolutionReference = resolutionReference,
                old = auditValues(old),
                new = auditValues(new),
                oldCommitteeIds = old.committeeIds.map { it.toString() }.sorted(),
                newCommitteeIds = new.committeeIds.map { it.toString() }.sorted(),
            )
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.MEETING_REWARD_POLICY,
            entityId = changeId,
            action = if (event == "PROPOSED") AuditAction.CREATE else AuditAction.UPDATE,
            before = null,
            after = Json.encodeToString(MeetingRewardPolicyAuditSnapshot.serializer(), snapshot),
        )
    }

    private const val HISTORY_LIMIT = 50
    private const val RESOLUTION_OPTION_LIMIT = 200
}
