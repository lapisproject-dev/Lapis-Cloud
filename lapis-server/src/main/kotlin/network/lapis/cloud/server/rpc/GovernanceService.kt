package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.economy.PlaceholderLtrBalanceProvider
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.server.security.canSubmitMotion
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AgendaItemInput
import network.lapis.cloud.shared.domain.AttendanceDto
import network.lapis.cloud.shared.domain.AttendanceInput
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteOptionDto
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** Meritokratische Voteen (V0.2.3): server-side floor, never trusted from a client. */
private val MIN_STAKE_LTR: BigDecimal = BigDecimal("0.01")
private val ZERO_LTR: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * DoS caps on [network.lapis.cloud.shared.domain.VoteOpenInput.optionLabels] — well above
 * any realistic Sachentscheidung's option count/label length, but bounded so a careless or
 * malicious caller cannot make `openVote` insert an unbounded number of
 * [network.lapis.cloud.server.db.generated.VoteOptionTable] rows or exceed that table's
 * `label VARCHAR(200)` column with a confusing DB-level error instead of a clean 409.
 */
private const val MAX_ABSTIMMUNG_OPTIONS = 50
private const val MAX_OPTION_LABEL_LENGTH = 200

/**
 * Committee and meeting management (V0.2.1). Reads (`listCommittees`/`getMeetingDetail`/
 * `listResolutions`/etc.) only require a resolvable [network.lapis.cloud.server.security
 * .CurrentMember] (any authenticated member) — see [IGovernanceService] KDoc for why this is a
 * deliberate simplification versus [network.lapis.cloud.shared.domain.DocumentAccessLevel]'s
 * tiered model. Writes that manage a specific Committee's meetings/agenda/attendance/resolutions
 * require that Committee's leadership role or global BOARD/ADMIN, checked via
 * [network.lapis.cloud.server.security.canRecordForMeeting] (see `GovernanceAuthorization.kt`).
 *
 * Member display names for the multiple-FK-to-member tables ([MeetingTable] has three: called
 * by/chair/minute-taker; [AttendanceTable] has two: attendee/proxy) are resolved via
 * [memberDisplayName], a small follow-up lookup per id, rather than aliased multi-joins — kept
 * simple and correct rather than optimized, consistent with this codebase's "simple-transaction"
 * style (see [ContributionService]/[MailingService]). Single-member-FK joins ([CommitteeTable] via
 * [MeetingTable]/[CommitteeMembershipTable] via `member`) still use a plain `innerJoin`.
 *
 * Meritokratische Voteen (V0.2.3): [ltrBalanceProvider] defaults to
 * [PlaceholderLtrBalanceProvider] so `Application.module`'s single-arg
 * `GovernanceService(call)` construction is unaffected; V0.6's ledger-backed implementation only
 * has to change that one default, not every call site. See [LtrBalanceProvider] KDoc for the
 * read-only-in-this-wave boundary.
 */
class GovernanceService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = PlaceholderLtrBalanceProvider(),
) : IGovernanceService {
    override suspend fun listCommittees(activeOnly: Boolean): List<CommitteeDto> {
        resolveCurrentMember(call)
        return transaction {
            val baseQuery = CommitteeTable.selectAll()
            val query = if (activeOnly) baseQuery.where { CommitteeTable.active eq true } else baseQuery
            query.map { it.toCommitteeDto() }
        }
    }

    override suspend fun createCommittee(input: CommitteeInput): CommitteeDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val now = nowLocalDateTime()
        return transaction {
            val id = Uuid.random()
            CommitteeTable.insert {
                it[CommitteeTable.id] = id
                it[name] = input.name
                it[type] = input.type
                it[description] = input.description
                it[active] = input.active
                it[quorumPercent] = input.quorumPercent
                it[createdAt] = now
            }
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq id }
                .single()
                .toCommitteeDto()
        }
    }

    override suspend fun updateCommittee(
        id: String,
        input: CommitteeInput,
    ): CommitteeDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val committeeId = id.toCommitteeUuid()
        return transaction {
            val updated =
                CommitteeTable.update({ CommitteeTable.id eq committeeId }) {
                    it[name] = input.name
                    it[type] = input.type
                    it[description] = input.description
                    it[active] = input.active
                    it[quorumPercent] = input.quorumPercent
                }
            if (updated == 0) throw NotFoundException("Committee $id not found")
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq committeeId }
                .single()
                .toCommitteeDto()
        }
    }

    override suspend fun listCommitteeMembers(
        committeeId: String,
        activeOnly: Boolean,
    ): List<CommitteeMembershipDto> {
        resolveCurrentMember(call)
        val gId = committeeId.toCommitteeUuid()
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(CommitteeMembershipTable.committeeId eq gId)
            if (activeOnly) conditions += CommitteeMembershipTable.until.isNull()
            (CommitteeMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .map { it.toCommitteeMembershipDto() }
        }
    }

    override suspend fun addCommitteeMember(
        committeeId: String,
        input: CommitteeMembershipInput,
    ): CommitteeMembershipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val gId = committeeId.toCommitteeUuid()
        val memberId = input.memberId.toMemberUuid()
        return transaction {
            CommitteeTable.selectAll().where { CommitteeTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Committee $committeeId not found")
            val activeExists =
                CommitteeMembershipTable
                    .selectAll()
                    .where {
                        (CommitteeMembershipTable.committeeId eq gId) and
                            (CommitteeMembershipTable.memberId eq memberId) and
                            (CommitteeMembershipTable.until.isNull())
                    }.count() > 0
            if (activeExists) {
                throw ConflictException(
                    "Member ${input.memberId} already has an active membership in Committee $committeeId",
                )
            }
            val id = Uuid.random()
            CommitteeMembershipTable.insert {
                it[CommitteeMembershipTable.id] = id
                it[CommitteeMembershipTable.committeeId] = gId
                it[CommitteeMembershipTable.memberId] = memberId
                it[role] = input.role
                it[since] = input.since
                it[until] = null
            }
            (CommitteeMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { CommitteeMembershipTable.id eq id }
                .single()
                .toCommitteeMembershipDto()
        }
    }

    override suspend fun endCommitteeMembership(
        membershipId: String,
        until: LocalDate,
    ): CommitteeMembershipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = membershipId.toMembershipUuid()
        return transaction {
            val updated =
                CommitteeMembershipTable.update({ CommitteeMembershipTable.id eq id }) {
                    it[CommitteeMembershipTable.until] = until
                }
            if (updated == 0) throw NotFoundException("CommitteeMembership $membershipId not found")
            (CommitteeMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { CommitteeMembershipTable.id eq id }
                .single()
                .toCommitteeMembershipDto()
        }
    }

    override suspend fun listMeetings(
        committeeId: String?,
        status: MeetingStatus?,
    ): List<MeetingDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (committeeId != null) conditions += (MeetingTable.committeeId eq committeeId.toCommitteeUuid())
            if (status != null) conditions += (MeetingTable.status eq status)
            val baseQuery = (MeetingTable innerJoin CommitteeTable).selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toMeetingDto() }
        }
    }

    override suspend fun getMeetingDetail(meetingId: String): MeetingDetailDto {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val meeting = loadMeeting(sId)
            MeetingDetailDto(
                meeting = meeting,
                agenda = loadAgenda(sId),
                attendance = loadAttendance(sId),
                resolutions = loadResolutions(sId),
                quorum = computeQuorum(sId, meeting.committeeId.toCommitteeUuid(), meeting.scheduledAt.date),
            )
        }
    }

    override suspend fun createMeeting(input: MeetingInput): MeetingDto {
        val current = resolveCurrentMember(call)
        val gId = input.committeeId.toCommitteeUuid()
        return transaction {
            CommitteeTable.selectAll().where { CommitteeTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Committee ${input.committeeId} not found")
            if (!current.canRecordForMeeting(gId)) throw ForbiddenException()
            val id = Uuid.random()
            val now = nowLocalDateTime()
            MeetingTable.insert {
                it[MeetingTable.id] = id
                it[MeetingTable.committeeId] = gId
                it[title] = input.title
                it[scheduledAt] = input.scheduledAt
                it[location] = input.location
                it[format] = input.format
                it[status] = MeetingStatus.PLANNED
                it[calledBy] = current.memberId
                it[calledAt] = now
                it[chairMemberId] = input.chairMemberId?.let(Uuid::parse)
                it[minuteTakerMemberId] = input.minuteTakerMemberId?.let(Uuid::parse)
                it[protocolDocumentId] = null
                it[createdAt] = now
            }
            loadMeeting(id)
        }
    }

    override suspend fun updateMeetingStatus(
        meetingId: String,
        status: MeetingStatus,
    ): MeetingDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            MeetingTable.update({ MeetingTable.id eq sId }) { it[MeetingTable.status] = status }
            loadMeeting(sId)
        }
    }

    override suspend fun addAgendaItem(
        meetingId: String,
        input: AgendaItemInput,
    ): AgendaItemDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            insertAgendaItem(sId, input)
        }
    }

    override suspend fun removeAgendaItem(id: String) {
        val current = resolveCurrentMember(call)
        val topId = id.toAgendaItemUuid()
        transaction {
            val row =
                AgendaItemTable.selectAll().where { AgendaItemTable.id eq topId }.singleOrNull()
                    ?: throw NotFoundException("AgendaItem $id not found")
            val committeeId = requireMeetingCommitteeId(row[AgendaItemTable.meetingId])
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            AgendaItemTable.deleteWhere { AgendaItemTable.id eq topId }
        }
    }

    override suspend fun recordAttendance(
        meetingId: String,
        input: AttendanceInput,
    ): AttendanceDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        val memberId = input.memberId.toMemberUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val now = nowLocalDateTime()
            val existing =
                AttendanceTable
                    .selectAll()
                    .where { (AttendanceTable.meetingId eq sId) and (AttendanceTable.memberId eq memberId) }
                    .singleOrNull()
            val id =
                if (existing == null) {
                    val newId = Uuid.random()
                    AttendanceTable.insert {
                        it[AttendanceTable.id] = newId
                        it[AttendanceTable.meetingId] = sId
                        it[AttendanceTable.memberId] = memberId
                        it[status] = input.status
                        it[representedByMemberId] = input.representedByMemberId?.let(Uuid::parse)
                        it[note] = input.note
                        it[recordedAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[AttendanceTable.id]
                    AttendanceTable.update({ AttendanceTable.id eq existingId }) {
                        it[status] = input.status
                        it[representedByMemberId] = input.representedByMemberId?.let(Uuid::parse)
                        it[note] = input.note
                        it[recordedAt] = now
                    }
                    existingId
                }
            AttendanceTable
                .selectAll()
                .where { AttendanceTable.id eq id }
                .single()
                .toAttendanceDto()
        }
    }

    override suspend fun getAttendance(meetingId: String): List<AttendanceDto> {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction { loadAttendance(sId) }
    }

    override suspend fun checkQuorum(meetingId: String): QuorumResultDto {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val meeting = loadMeeting(sId)
            computeQuorum(sId, meeting.committeeId.toCommitteeUuid(), meeting.scheduledAt.date)
        }
    }

    override suspend fun recordResolution(
        meetingId: String,
        input: ResolutionInput,
    ): ResolutionDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val meeting = loadMeeting(sId)
            insertResolutionRow(sId, committeeId, meeting.scheduledAt.date, input, current)
        }
    }

    override suspend fun listResolutions(
        committeeId: String?,
        meetingId: String?,
    ): List<ResolutionDto> {
        resolveCurrentMember(call)
        return transaction {
            when {
                meetingId != null -> {
                    val sId = meetingId.toMeetingUuid()
                    ResolutionTable.selectAll().where { ResolutionTable.meetingId eq sId }.map { it.toResolutionDto() }
                }
                committeeId != null -> {
                    val gId = committeeId.toCommitteeUuid()
                    (ResolutionTable innerJoin MeetingTable)
                        .selectAll()
                        .where { MeetingTable.committeeId eq gId }
                        .map { it.toResolutionDto() }
                }
                else -> ResolutionTable.selectAll().map { it.toResolutionDto() }
            }
        }
    }

    override suspend fun generateProtocolDraft(meetingId: String): ProtocolDraftDto {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val meeting = loadMeeting(sId)
            ProtocolDraftDto(
                meeting = meeting,
                attendance = loadAttendance(sId),
                agenda = loadAgenda(sId),
                resolutions = loadResolutions(sId),
                quorum = computeQuorum(sId, meeting.committeeId.toCommitteeUuid(), meeting.scheduledAt.date),
                generatedAt = nowLocalDateTime(),
            )
        }
    }

    override suspend fun submitMotion(input: MotionInput): MotionDto {
        val current = resolveCurrentMember(call)
        val gId = input.targetCommitteeId.toCommitteeUuid()
        return transaction {
            CommitteeTable.selectAll().where { CommitteeTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Committee ${input.targetCommitteeId} not found")
            if (!current.canSubmitMotion(gId)) throw ForbiddenException()
            val id = Uuid.random()
            val now = nowLocalDateTime()
            MotionTable.insert {
                it[MotionTable.id] = id
                it[targetCommitteeId] = gId
                it[title] = input.title
                it[rationale] = input.rationale
                it[text] = input.text
                it[submitterMemberId] = current.memberId
                it[status] = MotionStatus.SUBMITTED
                it[submittedAt] = now
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[reviewNote] = null
                it[meetingId] = null
                it[agendaItemId] = null
                it[resolutionId] = null
                it[withdrawnAt] = null
            }
            loadMotion(id)
        }
    }

    override suspend fun listMotions(
        targetCommitteeId: String?,
        status: MotionStatus?,
    ): List<MotionDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (targetCommitteeId != null) conditions += (MotionTable.targetCommitteeId eq targetCommitteeId.toCommitteeUuid())
            if (status != null) conditions += (MotionTable.status eq status)
            val baseQuery = (MotionTable innerJoin CommitteeTable).selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toMotionDto() }
        }
    }

    override suspend fun getMotion(id: String): MotionDto {
        resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction { loadMotion(aId) }
    }

    override suspend fun withdrawMotion(id: String): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction {
            val row =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            val submitterId = row[MotionTable.submitterMemberId]
            val status = row[MotionTable.status]
            val submitterWithdrawingOwnPending = current.memberId == submitterId && status == MotionStatus.SUBMITTED
            if (!submitterWithdrawingOwnPending && !current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (status == MotionStatus.WITHDRAWN) throw ConflictException("Motion $id already withdrawn")
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = MotionStatus.WITHDRAWN
                it[withdrawnAt] = nowLocalDateTime()
            }
            loadMotion(aId)
        }
    }

    override suspend fun reviewMotion(
        id: String,
        decision: MotionReviewDecision,
        note: String?,
    ): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction {
            val row =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val status = row[MotionTable.status]
            if (status != MotionStatus.SUBMITTED) {
                throw ConflictException("Motion $id is $status, expected SUBMITTED")
            }
            val newStatus =
                when (decision) {
                    MotionReviewDecision.ACCEPT -> MotionStatus.REVIEWED
                    MotionReviewDecision.REJECT -> MotionStatus.REJECTED_PRELIMINARY
                }
            val now = nowLocalDateTime()
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = newStatus
                it[reviewedBy] = current.memberId
                it[reviewedAt] = now
                it[reviewNote] = note
            }
            loadMotion(aId)
        }
    }

    override suspend fun scheduleMotion(
        id: String,
        meetingId: String,
        position: Int,
    ): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val row =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val status = row[MotionTable.status]
            if (status != MotionStatus.REVIEWED && status != MotionStatus.POSTPONED) {
                throw ConflictException("Motion $id is $status, expected REVIEWED or POSTPONED")
            }
            val meetingRow =
                MeetingTable.selectAll().where { MeetingTable.id eq sId }.singleOrNull()
                    ?: throw NotFoundException("Meeting $meetingId not found")
            if (meetingRow[MeetingTable.committeeId] != committeeId) {
                throw ConflictException("Meeting $meetingId does not belong to Motion $id's target Committee")
            }
            if (meetingRow[MeetingTable.status] != MeetingStatus.PLANNED) {
                throw ConflictException("Meeting $meetingId is not PLANNED")
            }
            val top =
                insertAgendaItem(
                    sId,
                    AgendaItemInput(
                        position = position,
                        title = row[MotionTable.title],
                        description = row[MotionTable.rationale],
                        presenterMemberId = row[MotionTable.submitterMemberId].toString(),
                    ),
                )
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = MotionStatus.SCHEDULED
                it[MotionTable.meetingId] = sId
                it[MotionTable.agendaItemId] = Uuid.parse(top.id)
            }
            loadMotion(aId)
        }
    }

    override suspend fun resolveMotion(
        id: String,
        input: MotionResolutionInput,
    ): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction {
            val row =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (row[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("Motion $id is ${row[MotionTable.status]}, expected SCHEDULED")
            }
            val sId = row[MotionTable.meetingId] ?: throw ConflictException("Motion $id has no scheduled Meeting")
            val topId = row[MotionTable.agendaItemId]
            val meeting = loadMeeting(sId)
            val resolutionInput =
                ResolutionInput(
                    agendaItemId = topId?.toString(),
                    title = row[MotionTable.title],
                    text = row[MotionTable.text],
                    votesYes = input.votesYes,
                    votesNo = input.votesNo,
                    votesAbstain = input.votesAbstain,
                    status = input.status,
                )
            val resolution = insertResolutionRow(sId, committeeId, meeting.scheduledAt.date, resolutionInput, current)
            val newMotionStatus =
                when (input.status) {
                    ResolutionStatus.ADOPTED -> MotionStatus.RESOLVED
                    ResolutionStatus.REJECTED -> MotionStatus.REJECTED
                    ResolutionStatus.POSTPONED -> MotionStatus.POSTPONED
                }
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = newMotionStatus
                it[MotionTable.resolutionId] = Uuid.parse(resolution.id)
            }
            loadMotion(aId)
        }
    }

    override suspend fun openVote(input: VoteOpenInput): VoteDto {
        val current = resolveCurrentMember(call)
        val aId = input.motionId.toMotionUuid()
        return transaction {
            val motionRow =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion ${input.motionId} not found")
            val committeeId = motionRow[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (motionRow[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("Motion ${input.motionId} is ${motionRow[MotionTable.status]}, expected SCHEDULED")
            }
            val sId =
                motionRow[MotionTable.meetingId]
                    ?: throw ConflictException("Motion ${input.motionId} has no scheduled Meeting")
            val hasActiveVote =
                VoteTable
                    .selectAll()
                    .where {
                        (VoteTable.motionId eq aId) and
                            (VoteTable.status inList listOf(VoteStatus.OPEN, VoteStatus.CLOSED))
                    }.count() > 0
            if (hasActiveVote) {
                throw ConflictException("Motion ${input.motionId} already has an open or resolved Vote")
            }
            val distinctLabels =
                input.optionLabels
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            if (distinctLabels.size < 2) {
                throw ConflictException("openVote requires at least 2 distinct non-blank option labels")
            }
            if (distinctLabels.size > MAX_ABSTIMMUNG_OPTIONS) {
                throw ConflictException("openVote accepts at most $MAX_ABSTIMMUNG_OPTIONS distinct option labels")
            }
            if (distinctLabels.any { it.length > MAX_OPTION_LABEL_LENGTH }) {
                throw ConflictException("Option labels must be at most $MAX_OPTION_LABEL_LENGTH characters")
            }
            val id = Uuid.random()
            val now = nowLocalDateTime()
            VoteTable.insert {
                it[VoteTable.id] = id
                it[VoteTable.motionId] = aId
                it[VoteTable.meetingId] = sId
                it[title] = motionRow[MotionTable.title]
                it[status] = VoteStatus.OPEN
                it[openedBy] = current.memberId
                it[openedAt] = now
                it[closedAt] = null
                it[winnerOptionId] = null
                it[secondPriceLtr] = null
                it[resolutionId] = null
            }
            distinctLabels.forEachIndexed { index, label ->
                VoteOptionTable.insert {
                    it[VoteOptionTable.id] = Uuid.random()
                    it[VoteOptionTable.voteId] = id
                    it[VoteOptionTable.label] = label
                    it[position] = index
                }
            }
            loadVote(id)
        }
    }

    /**
     * Eligibility mirrors [computeQuorum]'s [eligibleMemberIds] set for the Vote's
     * underlying Meeting -- staking LTR into a basket is, per the concept document, a right
     * exercised by the same constituency that would otherwise cast a headcount vote on this
     * Motion, not an org-wide free-for-all (see the implementation plan's open decision point
     * (c) for the alternative considered and deferred).
     */
    override suspend fun castVoteBallot(input: VoteBallotInput): VoteBallotDto {
        val current = resolveCurrentMember(call)
        val abId = input.voteId.toVoteUuid()
        val optId = input.optionId.toVoteOptionUuid()
        return transaction {
            val voteRow =
                VoteTable.selectAll().where { VoteTable.id eq abId }.singleOrNull()
                    ?: throw NotFoundException("Vote ${input.voteId} not found")
            if (voteRow[VoteTable.status] != VoteStatus.OPEN) {
                throw ConflictException(
                    "Vote ${input.voteId} is ${voteRow[VoteTable.status]}, expected OPEN",
                )
            }
            VoteOptionTable
                .selectAll()
                .where { (VoteOptionTable.id eq optId) and (VoteOptionTable.voteId eq abId) }
                .singleOrNull()
                ?: throw NotFoundException("Option ${input.optionId} does not belong to Vote ${input.voteId}")

            val meeting = loadMeeting(voteRow[VoteTable.meetingId])
            val committeeRow =
                CommitteeTable.selectAll().where { CommitteeTable.id eq meeting.committeeId.toCommitteeUuid() }.single()
            val eligible = eligibleMemberIds(committeeRow, meeting.scheduledAt.date)
            if (current.memberId !in eligible) throw ForbiddenException()

            val stake = input.stakeLtr
            if (stake.scale() > 2) throw ConflictException("stakeLtr must have at most 2 decimal places")
            val normalizedStake = stake.setScale(2, RoundingMode.UNNECESSARY)
            if (normalizedStake < MIN_STAKE_LTR) throw ConflictException("stakeLtr must be at least $MIN_STAKE_LTR")
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            if (normalizedStake > freeBalance) {
                throw ConflictException("stakeLtr $normalizedStake exceeds free LTR balance $freeBalance")
            }

            val now = nowLocalDateTime()
            val existing =
                VoteBallotTable
                    .selectAll()
                    .where {
                        (VoteBallotTable.voteId eq abId) and (VoteBallotTable.memberId eq current.memberId)
                    }.singleOrNull()
            val id =
                if (existing == null) {
                    val newId = Uuid.random()
                    VoteBallotTable.insert {
                        it[VoteBallotTable.id] = newId
                        it[VoteBallotTable.voteId] = abId
                        it[VoteBallotTable.optionId] = optId
                        it[VoteBallotTable.memberId] = current.memberId
                        it[stakeLtr] = normalizedStake
                        it[settledLtr] = null
                        it[castAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[VoteBallotTable.id]
                    VoteBallotTable.update({ VoteBallotTable.id eq existingId }) {
                        it[VoteBallotTable.optionId] = optId
                        it[stakeLtr] = normalizedStake
                        it[settledLtr] = null
                        it[castAt] = now
                    }
                    existingId
                }
            VoteBallotTable
                .selectAll()
                .where { VoteBallotTable.id eq id }
                .single()
                .toVoteBallotDto()
        }
    }

    /**
     * Runs the Vickrey settlement ([computeVickreySettlement]) and writes it into the same
     * resolution book [recordResolution]/[resolveMotion] use, tagged
     * [ResolutionMode.MERITOCRATIC] -- see [insertResolutionRow]. `votesYes`/`votesNo` are
     * populated informationally only for the default 2-option YES/NO shape (headcount of ballots
     * per label, not LTR-weighted); any other option count leaves them at `0/0/0` since the
     * weighted result lives in the Vote itself, not in headcount fields designed for the
     * Committee-Quorum path. The winning *basket's* label decides [ResolutionStatus]: a basket
     * labelled `"NO"` (case-insensitive) resolves [ResolutionStatus.REJECTED], any other winning
     * basket (including a >2-option Sachentscheidung's winning project) resolves
     * [ResolutionStatus.ADOPTED], and a tie (no winner) resolves [ResolutionStatus.POSTPONED] --
     * documented decision point (a) from the implementation plan.
     */
    override suspend fun closeVote(voteId: String): VoteDto {
        val current = resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction {
            val voteRow =
                VoteTable.selectAll().where { VoteTable.id eq abId }.singleOrNull()
                    ?: throw NotFoundException("Vote $voteId not found")
            val motionRow =
                MotionTable.selectAll().where { MotionTable.id eq voteRow[VoteTable.motionId] }.single()
            val committeeId = motionRow[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            // Re-checked inside the transaction: guards against a concurrent second close (or a
            // cast-vs-close race) between the read above and this point.
            if (voteRow[VoteTable.status] != VoteStatus.OPEN) {
                throw ConflictException("Vote $voteId is ${voteRow[VoteTable.status]}, expected OPEN")
            }

            val optionRows = VoteOptionTable.selectAll().where { VoteOptionTable.voteId eq abId }.toList()
            val optionIds = optionRows.map { it[VoteOptionTable.id] }
            val labelByOptionId = optionRows.associate { it[VoteOptionTable.id] to it[VoteOptionTable.label] }
            val ballotRows = VoteBallotTable.selectAll().where { VoteBallotTable.voteId eq abId }.toList()
            val ballots =
                ballotRows.map {
                    Ballot(
                        memberId = it[VoteBallotTable.memberId],
                        optionId = it[VoteBallotTable.optionId],
                        stake = it[VoteBallotTable.stakeLtr],
                    )
                }
            val settlement = computeVickreySettlement(ballots, optionIds)
            val now = nowLocalDateTime()

            ballotRows.forEach { row ->
                val ballotId = row[VoteBallotTable.id]
                val memberId = row[VoteBallotTable.memberId]
                val settled = settlement.charges[memberId] ?: ZERO_LTR
                VoteBallotTable.update({ VoteBallotTable.id eq ballotId }) {
                    it[settledLtr] = settled
                }
            }

            val resolutionStatus =
                when (val winnerId = settlement.winnerOptionId) {
                    null -> ResolutionStatus.POSTPONED
                    else -> {
                        val winnerLabel = labelByOptionId.getValue(winnerId)
                        if (winnerLabel.equals("NO", ignoreCase = true)) ResolutionStatus.REJECTED else ResolutionStatus.ADOPTED
                    }
                }
            val (votesYes, votesNo) =
                if (optionRows.size == 2) {
                    val yes = ballotRows.count { labelByOptionId[it[VoteBallotTable.optionId]].equals("YES", ignoreCase = true) }
                    val no = ballotRows.count { labelByOptionId[it[VoteBallotTable.optionId]].equals("NO", ignoreCase = true) }
                    yes to no
                } else {
                    0 to 0
                }

            val sId = voteRow[VoteTable.meetingId]
            val meeting = loadMeeting(sId)
            val resolutionInput =
                ResolutionInput(
                    agendaItemId = motionRow[MotionTable.agendaItemId]?.toString(),
                    title = motionRow[MotionTable.title],
                    text = motionRow[MotionTable.text],
                    votesYes = votesYes,
                    votesNo = votesNo,
                    votesAbstain = 0,
                    status = resolutionStatus,
                )
            val resolution =
                insertResolutionRow(
                    sId,
                    committeeId,
                    meeting.scheduledAt.date,
                    resolutionInput,
                    current,
                    resolutionMode = ResolutionMode.MERITOCRATIC,
                    voteId = abId,
                )

            val newMotionStatus =
                when (resolutionStatus) {
                    ResolutionStatus.ADOPTED -> MotionStatus.RESOLVED
                    ResolutionStatus.REJECTED -> MotionStatus.REJECTED
                    ResolutionStatus.POSTPONED -> MotionStatus.POSTPONED
                }
            MotionTable.update({ MotionTable.id eq motionRow[MotionTable.id] }) {
                it[MotionTable.status] = newMotionStatus
                it[MotionTable.resolutionId] = Uuid.parse(resolution.id)
            }

            VoteTable.update({ VoteTable.id eq abId }) {
                it[status] = VoteStatus.CLOSED
                it[closedAt] = now
                it[winnerOptionId] = settlement.winnerOptionId
                it[secondPriceLtr] = settlement.secondPrice
                it[resolutionId] = Uuid.parse(resolution.id)
            }
            loadVote(abId)
        }
    }

    override suspend fun abortVote(voteId: String): VoteDto {
        val current = resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction {
            val voteRow =
                VoteTable.selectAll().where { VoteTable.id eq abId }.singleOrNull()
                    ?: throw NotFoundException("Vote $voteId not found")
            val motionRow =
                MotionTable.selectAll().where { MotionTable.id eq voteRow[VoteTable.motionId] }.single()
            if (!current.canRecordForMeeting(motionRow[MotionTable.targetCommitteeId])) throw ForbiddenException()
            if (voteRow[VoteTable.status] != VoteStatus.OPEN) {
                throw ConflictException("Vote $voteId is ${voteRow[VoteTable.status]}, expected OPEN")
            }
            VoteTable.update({ VoteTable.id eq abId }) {
                it[status] = VoteStatus.ABORTED
                it[closedAt] = nowLocalDateTime()
            }
            loadVote(abId)
        }
    }

    override suspend fun getVote(voteId: String): VoteDto {
        resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction { loadVote(abId) }
    }

    override suspend fun listVoteBallots(voteId: String): List<VoteBallotDto> {
        resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction {
            VoteTable.selectAll().where { VoteTable.id eq abId }.singleOrNull()
                ?: throw NotFoundException("Vote $voteId not found")
            VoteBallotTable
                .selectAll()
                .where { VoteBallotTable.voteId eq abId }
                .map { it.toVoteBallotDto() }
        }
    }

    private fun loadMeeting(id: Uuid): MeetingDto =
        (MeetingTable innerJoin CommitteeTable)
            .selectAll()
            .where { MeetingTable.id eq id }
            .singleOrNull()
            ?.toMeetingDto()
            ?: throw NotFoundException("Meeting $id not found")

    private fun requireMeetingCommitteeId(meetingId: Uuid): Uuid =
        MeetingTable
            .selectAll()
            .where { MeetingTable.id eq meetingId }
            .singleOrNull()
            ?.get(MeetingTable.committeeId)
            ?: throw NotFoundException("Meeting $meetingId not found")

    private fun loadAgenda(meetingId: Uuid): List<AgendaItemDto> =
        AgendaItemTable
            .selectAll()
            .where { AgendaItemTable.meetingId eq meetingId }
            .orderBy(AgendaItemTable.position, SortOrder.ASC)
            .map { it.toAgendaItemDto() }

    private fun loadAttendance(meetingId: Uuid): List<AttendanceDto> =
        AttendanceTable
            .selectAll()
            .where { AttendanceTable.meetingId eq meetingId }
            .map { it.toAttendanceDto() }

    private fun loadResolutions(meetingId: Uuid): List<ResolutionDto> =
        ResolutionTable
            .selectAll()
            .where { ResolutionTable.meetingId eq meetingId }
            .map { it.toResolutionDto() }

    private fun loadMotion(id: Uuid): MotionDto =
        (MotionTable innerJoin CommitteeTable)
            .selectAll()
            .where { MotionTable.id eq id }
            .singleOrNull()
            ?.toMotionDto()
            ?: throw NotFoundException("Motion $id not found")

    private fun loadVote(id: Uuid): VoteDto =
        VoteTable
            .selectAll()
            .where { VoteTable.id eq id }
            .singleOrNull()
            ?.toVoteDto()
            ?: throw NotFoundException("Vote $id not found")

    /**
     * Shared insert path for [addAgendaItem] and [scheduleMotion] (V0.2.2) — the latter
     * populates [AgendaItemInput] from the Motion (`title`/`rationale`/submitter) rather
     * than duplicating the position-collision check and insert logic. Must run inside an
     * already-open `transaction {}` (both call sites do).
     */
    private fun insertAgendaItem(
        sId: Uuid,
        input: AgendaItemInput,
    ): AgendaItemDto {
        val positionTaken =
            AgendaItemTable
                .selectAll()
                .where {
                    (AgendaItemTable.meetingId eq sId) and (AgendaItemTable.position eq input.position)
                }.count() > 0
        if (positionTaken) throw ConflictException("Position ${input.position} already used for Meeting $sId")
        val id = Uuid.random()
        AgendaItemTable.insert {
            it[AgendaItemTable.id] = id
            it[AgendaItemTable.meetingId] = sId
            it[position] = input.position
            it[title] = input.title
            it[description] = input.description
            it[presenterMemberId] = input.presenterMemberId?.let(Uuid::parse)
        }
        return AgendaItemTable
            .selectAll()
            .where { AgendaItemTable.id eq id }
            .single()
            .toAgendaItemDto()
    }

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { id ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun ResultRow.toCommitteeDto(): CommitteeDto =
        CommitteeDto(
            id = this[CommitteeTable.id].toString(),
            name = this[CommitteeTable.name],
            type = this[CommitteeTable.type],
            description = this[CommitteeTable.description],
            active = this[CommitteeTable.active],
            quorumPercent = this[CommitteeTable.quorumPercent],
            createdAt = this[CommitteeTable.createdAt],
        )

    private fun ResultRow.toCommitteeMembershipDto(): CommitteeMembershipDto =
        CommitteeMembershipDto(
            id = this[CommitteeMembershipTable.id].toString(),
            committeeId = this[CommitteeMembershipTable.committeeId].toString(),
            memberId = this[CommitteeMembershipTable.memberId].toString(),
            memberDisplayName = this[MemberTable.displayName],
            role = this[CommitteeMembershipTable.role],
            since = this[CommitteeMembershipTable.since],
            until = this[CommitteeMembershipTable.until],
        )

    private fun ResultRow.toMeetingDto(): MeetingDto =
        MeetingDto(
            id = this[MeetingTable.id].toString(),
            committeeId = this[MeetingTable.committeeId].toString(),
            committeeName = this[CommitteeTable.name],
            title = this[MeetingTable.title],
            scheduledAt = this[MeetingTable.scheduledAt],
            location = this[MeetingTable.location],
            format = this[MeetingTable.format],
            status = this[MeetingTable.status],
            calledById = this[MeetingTable.calledBy]?.toString(),
            calledByDisplayName = memberDisplayName(this[MeetingTable.calledBy]),
            calledAt = this[MeetingTable.calledAt],
            chairMemberId = this[MeetingTable.chairMemberId]?.toString(),
            chairDisplayName = memberDisplayName(this[MeetingTable.chairMemberId]),
            minuteTakerMemberId = this[MeetingTable.minuteTakerMemberId]?.toString(),
            minuteTakerDisplayName = memberDisplayName(this[MeetingTable.minuteTakerMemberId]),
            protocolDocumentId = this[MeetingTable.protocolDocumentId]?.toString(),
            createdAt = this[MeetingTable.createdAt],
        )

    private fun ResultRow.toAgendaItemDto(): AgendaItemDto =
        AgendaItemDto(
            id = this[AgendaItemTable.id].toString(),
            meetingId = this[AgendaItemTable.meetingId].toString(),
            position = this[AgendaItemTable.position],
            title = this[AgendaItemTable.title],
            description = this[AgendaItemTable.description],
            presenterMemberId = this[AgendaItemTable.presenterMemberId]?.toString(),
            presenterDisplayName = memberDisplayName(this[AgendaItemTable.presenterMemberId]),
        )

    private fun ResultRow.toAttendanceDto(): AttendanceDto =
        AttendanceDto(
            id = this[AttendanceTable.id].toString(),
            meetingId = this[AttendanceTable.meetingId].toString(),
            memberId = this[AttendanceTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[AttendanceTable.memberId]).orEmpty(),
            status = this[AttendanceTable.status],
            representedByMemberId = this[AttendanceTable.representedByMemberId]?.toString(),
            representedByDisplayName = memberDisplayName(this[AttendanceTable.representedByMemberId]),
            note = this[AttendanceTable.note],
            recordedAt = this[AttendanceTable.recordedAt],
        )

    /**
     * Follow-up-queries the options ([VoteOptionTable]) and their computed basket totals
     * (summed from [VoteBallotTable], never stored) for this Vote — same
     * "simple-transaction" style as [memberDisplayName], not an optimized single join.
     */
    private fun ResultRow.toVoteDto(): VoteDto {
        val voteId = this[VoteTable.id]
        val stakesByOption =
            VoteBallotTable
                .selectAll()
                .where { VoteBallotTable.voteId eq voteId }
                .groupBy({ it[VoteBallotTable.optionId] }, { it[VoteBallotTable.stakeLtr] })
                .mapValues { (_, stakes) -> stakes.fold(ZERO_LTR) { acc, stake -> acc + stake } }
        val options =
            VoteOptionTable
                .selectAll()
                .where { VoteOptionTable.voteId eq voteId }
                .orderBy(VoteOptionTable.position, SortOrder.ASC)
                .map { optRow ->
                    val optionId = optRow[VoteOptionTable.id]
                    VoteOptionDto(
                        id = optionId.toString(),
                        voteId = voteId.toString(),
                        label = optRow[VoteOptionTable.label],
                        position = optRow[VoteOptionTable.position],
                        basketTotalLtr = stakesByOption[optionId] ?: ZERO_LTR,
                    )
                }
        return VoteDto(
            id = voteId.toString(),
            motionId = this[VoteTable.motionId].toString(),
            meetingId = this[VoteTable.meetingId].toString(),
            title = this[VoteTable.title],
            status = this[VoteTable.status],
            options = options,
            winnerOptionId = this[VoteTable.winnerOptionId]?.toString(),
            secondPriceLtr = this[VoteTable.secondPriceLtr],
            openedById = this[VoteTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[VoteTable.openedBy]).orEmpty(),
            openedAt = this[VoteTable.openedAt],
            closedAt = this[VoteTable.closedAt],
            resolutionId = this[VoteTable.resolutionId]?.toString(),
        )
    }

    private fun ResultRow.toVoteBallotDto(): VoteBallotDto =
        VoteBallotDto(
            id = this[VoteBallotTable.id].toString(),
            voteId = this[VoteBallotTable.voteId].toString(),
            optionId = this[VoteBallotTable.optionId].toString(),
            memberId = this[VoteBallotTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[VoteBallotTable.memberId]).orEmpty(),
            stakeLtr = this[VoteBallotTable.stakeLtr],
            settledLtr = this[VoteBallotTable.settledLtr],
            castAt = this[VoteBallotTable.castAt],
        )

    private fun ResultRow.toMotionDto(): MotionDto =
        MotionDto(
            id = this[MotionTable.id].toString(),
            targetCommitteeId = this[MotionTable.targetCommitteeId].toString(),
            targetCommitteeName = this[CommitteeTable.name],
            targetCommitteeType = this[CommitteeTable.type],
            title = this[MotionTable.title],
            rationale = this[MotionTable.rationale],
            text = this[MotionTable.text],
            submitterMemberId = this[MotionTable.submitterMemberId].toString(),
            submitterDisplayName = memberDisplayName(this[MotionTable.submitterMemberId]).orEmpty(),
            status = this[MotionTable.status],
            submittedAt = this[MotionTable.submittedAt],
            reviewedById = this[MotionTable.reviewedBy]?.toString(),
            reviewedByDisplayName = memberDisplayName(this[MotionTable.reviewedBy]),
            reviewedAt = this[MotionTable.reviewedAt],
            reviewNote = this[MotionTable.reviewNote],
            meetingId = this[MotionTable.meetingId]?.toString(),
            agendaItemId = this[MotionTable.agendaItemId]?.toString(),
            resolutionId = this[MotionTable.resolutionId]?.toString(),
        )

    private fun String.toCommitteeUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMemberUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMeetingUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMembershipUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toAgendaItemUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMotionUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toVoteUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toVoteOptionUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
