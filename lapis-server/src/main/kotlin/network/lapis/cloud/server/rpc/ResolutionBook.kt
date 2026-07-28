package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Änderungsantrag (V0.2.6): every [MotionStatus] that is NOT yet a terminal outcome -- used both
 * as "still amendable" ([GovernanceService.submitMotion]) and as the amendment-ordering guard's
 * "pending" set ([requireNoPendingAmendments]). [MotionStatus.WITHDRAWN]/[MotionStatus.RESOLVED]/
 * [MotionStatus.REJECTED]/[MotionStatus.REJECTED_PRELIMINARY] are the terminal statuses.
 */
internal val NON_TERMINAL_MOTION_STATUSES: Set<MotionStatus> =
    setOf(MotionStatus.SUBMITTED, MotionStatus.REVIEWED, MotionStatus.SCHEDULED, MotionStatus.POSTPONED)

/**
 * Änderungsantrag (V0.2.6) ordering guard: a main Motion (`amendsMotionId == null`) may not
 * finalize (transition to a terminal [MotionStatus]) while it still has any amendment in a
 * [NON_TERMINAL_MOTION_STATUSES] status -- voting on/adopting a main motion before its own
 * pending amendments have been decided would make [network.lapis.cloud.shared.domain.MotionDto
 * .effectiveText] ambiguous (an amendment adopted AFTER the main motion resolved could never
 * actually apply).
 *
 * Called from every path capable of transitioning a scheduled Motion to a terminal status --
 * [GovernanceService.resolveMotion] (Committee-Quorum) and [GovernanceService.closeVote]
 * (Meritokratische Vote) are the two paths where an amendment procedurally makes sense (adopting
 * a Sachantrag's text); `ElectionService.tally`/`SystemicConsensusService.evaluate` also call this
 * for full invariant soundness, since they too can transition a Motion carrying pending
 * amendments to RESOLVED/REJECTED/POSTPONED even though amending an Election's/
 * SystemicConsensus's underlying Motion has no real procedural meaning -- same "leaving it
 * unguarded would be a silent bypass of the same invariant" reasoning the Vote path already
 * needed. Must run inside an already-open `transaction {}` (all call sites do).
 */
internal fun requireNoPendingAmendments(motionId: Uuid) {
    val pending =
        MotionTable
            .selectAll()
            .where {
                (MotionTable.amendsMotionId eq motionId) and (MotionTable.status inList NON_TERMINAL_MOTION_STATUSES.toList())
            }.count()
    if (pending > 0) {
        throw ConflictException("Motion $motionId has $pending pending amendment(s); resolve/withdraw them first")
    }
}

/**
 * Shared resolution book write path -- originally private to [GovernanceService] (`computeQuorum`/
 * `nextResolutionNumber`/`insertResolutionRow`/`ResultRow.toResolutionDto`), extracted here
 * (Demokratische Electionen, V0.2.4) so `ElectionService.tally` can write into the *same*
 * resolution book [GovernanceService.recordResolution]/[GovernanceService.resolveMotion]/
 * [GovernanceService.closeVote] already use, tagged [ResolutionMode.DEMOCRATIC], without
 * duplicating the quorum-snapshot/Resolution-numbering logic. Behavior for the pre-existing three
 * call sites is unchanged by this extraction -- only the parameter shape of [insertResolutionRow]
 * and [computeQuorum] moved from taking a full `MeetingDto` to taking the `committeeId`/
 * `scheduledDate` primitives it actually needs, so `ElectionService` (which only ever looks up a
 * Meeting's raw row, never builds a full `MeetingDto`) does not have to construct one just to
 * call this.
 *
 * Must run inside an already-open `transaction {}` (all call sites do).
 */
internal fun computeQuorum(
    meetingId: Uuid,
    committeeId: Uuid,
    scheduledDate: LocalDate,
): QuorumResultDto {
    val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
    val quorumPercent = committeeRow[CommitteeTable.quorumPercent]
    val eligible = eligibleMemberIds(committeeRow, scheduledDate)
    val presentCount =
        AttendanceTable
            .selectAll()
            .where {
                (AttendanceTable.meetingId eq meetingId) and
                    (AttendanceTable.status inList listOf(AttendanceStatus.PRESENT, AttendanceStatus.REPRESENTED))
            }.map { it[AttendanceTable.memberId] }
            .count { it in eligible }
    val eligibleCount = eligible.size
    val requiredCount = ceil(eligibleCount * quorumPercent / 100.0).toInt()
    return QuorumResultDto(
        meetingId = meetingId.toString(),
        eligibleMemberCount = eligibleCount,
        presentCount = presentCount,
        requiredCount = requiredCount,
        quorumPercent = quorumPercent,
        met = presentCount >= requiredCount,
    )
}

/**
 * `"<CommitteeType>-<Jahr>-<laufendeNummer>"` (e.g. `"EXECUTIVE_BOARD-2026-03"`). The running number is
 * `count(resolution where committee = X and year(decidedAt) = Y) + 1`, computed by loading this
 * Committee's Resolution rows for the year and filtering in Kotlin rather than a DB-side
 * `EXTRACT(YEAR FROM ...)` -- no DB sequence needed at this scale, and avoids a date-function that
 * behaves slightly differently between H2 and Postgres.
 */
internal fun nextResolutionNumber(
    committeeId: Uuid,
    committeeTypeName: String,
    year: Int,
): String {
    val countThisYear =
        (ResolutionTable innerJoin MeetingTable)
            .selectAll()
            .where { MeetingTable.committeeId eq committeeId }
            .count { it[ResolutionTable.decidedAt].year == year }
    return "$committeeTypeName-$year-${(countThisYear + 1).toString().padStart(2, '0')}"
}

/**
 * Shared insert path for [GovernanceService.recordResolution] and [GovernanceService.resolveMotion]
 * (V0.2.2), extended in V0.2.3 to also serve [GovernanceService.closeVote], in V0.2.4 to
 * also serve `ElectionService.tally`, and in V0.2.5 to also serve
 * `SystemicConsensusService.evaluate` -- what actually makes "resolution links into the existing
 * resolution book mechanism rather than creating a parallel one" true in code, not just in the DTO
 * shape. [resolutionMode]/[voteId]/[electionId]/[systemicConsensusId] default to the pre-V0.2.3
 * Committee-Quorum shape so [GovernanceService.recordResolution]/[GovernanceService.resolveMotion]
 * call sites stay source-compatible.
 *
 * `quorumMet` is still snapshotted for a [ResolutionMode.MERITOCRATIC]/[ResolutionMode
 * .DEMOCRATIC]/[ResolutionMode.SYSTEMIC_CONSENSUS] Resolution too (for the historical record),
 * even though the outcome itself is decided by LTR baskets, one-person-one-vote ballots or lowest
 * cumulative resistance, not by this headcount figure -- documented decision point carried over
 * from the V0.2.3 implementation plan's "Quorum interaction" note; a minimum-participation guard
 * on Voteen/Electionen/SystemicConsensusen is deferred.
 */
internal fun insertResolutionRow(
    sId: Uuid,
    committeeId: Uuid,
    scheduledDate: LocalDate,
    input: ResolutionInput,
    current: CurrentMember,
    resolutionMode: ResolutionMode = ResolutionMode.COMMITTEE_QUORUM,
    voteId: Uuid? = null,
    electionId: Uuid? = null,
    systemicConsensusId: Uuid? = null,
): ResolutionDto {
    val quorum = computeQuorum(sId, committeeId, scheduledDate)
    val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val number = nextResolutionNumber(committeeId, committeeRow[CommitteeTable.type].name, now.year)
    val id = Uuid.random()
    ResolutionTable.insert {
        it[ResolutionTable.id] = id
        it[ResolutionTable.meetingId] = sId
        it[agendaItemId] = input.agendaItemId?.let(Uuid::parse)
        it[ResolutionTable.number] = number
        it[title] = input.title
        it[text] = input.text
        it[votesYes] = input.votesYes
        it[votesNo] = input.votesNo
        it[votesAbstain] = input.votesAbstain
        it[quorumMet] = quorum.met
        it[status] = input.status
        it[decidedAt] = now
        it[recordedBy] = current.memberId
        it[ResolutionTable.resolutionMode] = resolutionMode
        it[ResolutionTable.voteId] = voteId
        it[ResolutionTable.electionId] = electionId
        it[ResolutionTable.systemicConsensusId] = systemicConsensusId
    }
    return ResolutionTable
        .selectAll()
        .where { ResolutionTable.id eq id }
        .single()
        .toResolutionDto()
}

/**
 * V0.5.3 GoBD audit-log helper for every [insertResolutionRow] call site (recordResolution,
 * resolveMotion, closeVote, `ElectionService.tally`, `SystemicConsensusService.evaluate`) --
 * CREATE only, a Resolution is never mutated after recording (see `14-audit-log.kuml.kts` file
 * header). Factored out so all five call sites produce the exact same [ResolutionSnapshot] shape
 * instead of re-deriving it -- fixes a V0.5.3-review finding where only [GovernanceService
 * .recordResolution] actually audited its Resolution and the other four call sites (which are the
 * vote-/election-/systemic-consensus-*decided* outcomes, not just administrative recording) wrote
 * no audit row at all.
 *
 * Deliberately NOT called from inside [insertResolutionRow] itself, even though that would remove
 * the need for every call site to remember to call this: [AuditLogRecorder.record]'s documented
 * deadlock-avoidance contract requires it to be the LAST database operation of the caller's
 * transaction that takes a row lock, but every call site except recordResolution performs further
 * row-locking writes (`MotionTable.update`/`VoteTable.update`/`ElectionTable.update`/
 * `CommitteeMembershipTable.update`, etc.) AFTER [insertResolutionRow] returns. Calling
 * [AuditLogRecorder.record] from inside [insertResolutionRow] would insert its chain-state row
 * lock into the middle of those transactions instead of at the end, reintroducing exactly the
 * lock-ordering hazard that KDoc warns about. Every call site therefore invokes this helper itself,
 * explicitly, as the true last locking operation of its own transaction -- see each call site for
 * the exact placement.
 */
internal fun auditResolutionCreate(
    resolution: ResolutionDto,
    current: CurrentMember,
) {
    AuditLogRecorder.record(
        actorMemberId = current.memberId,
        actorRole = current.role,
        entityType = AuditEntityType.RESOLUTION,
        entityId = Uuid.parse(resolution.id),
        action = AuditAction.CREATE,
        before = null,
        after =
            Json.encodeToString(
                ResolutionSnapshot.serializer(),
                ResolutionSnapshot(
                    meetingId = resolution.meetingId,
                    number = resolution.number,
                    title = resolution.title,
                    text = resolution.text,
                    votesYes = resolution.votesYes,
                    votesNo = resolution.votesNo,
                    votesAbstain = resolution.votesAbstain,
                    quorumMet = resolution.quorumMet,
                    status = resolution.status,
                    decidedAt = resolution.decidedAt,
                    recordedBy = resolution.recordedById,
                    resolutionMode = resolution.resolutionMode,
                ),
            ),
    )
}

internal fun ResultRow.toResolutionDto(): ResolutionDto =
    ResolutionDto(
        id = this[ResolutionTable.id].toString(),
        meetingId = this[ResolutionTable.meetingId].toString(),
        agendaItemId = this[ResolutionTable.agendaItemId]?.toString(),
        number = this[ResolutionTable.number],
        title = this[ResolutionTable.title],
        text = this[ResolutionTable.text],
        votesYes = this[ResolutionTable.votesYes],
        votesNo = this[ResolutionTable.votesNo],
        votesAbstain = this[ResolutionTable.votesAbstain],
        quorumMet = this[ResolutionTable.quorumMet],
        status = this[ResolutionTable.status],
        decidedAt = this[ResolutionTable.decidedAt],
        recordedById = this[ResolutionTable.recordedBy].toString(),
        recordedByDisplayName = resolutionRecorderDisplayName(this[ResolutionTable.recordedBy]).orEmpty(),
        resolutionMode = this[ResolutionTable.resolutionMode],
        voteId = this[ResolutionTable.voteId]?.toString(),
        electionId = this[ResolutionTable.electionId]?.toString(),
        systemicConsensusId = this[ResolutionTable.systemicConsensusId]?.toString(),
    )

private fun resolutionRecorderDisplayName(memberId: Uuid?): String? =
    memberId?.let { id ->
        MemberTable
            .selectAll()
            .where { MemberTable.id eq id }
            .singleOrNull()
            ?.get(MemberTable.displayName)
    }
