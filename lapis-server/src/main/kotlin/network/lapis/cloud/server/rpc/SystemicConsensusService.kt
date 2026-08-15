package network.lapis.cloud.server.rpc
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import network.lapis.cloud.server.conference.SecretBallotStreamGuard
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.security.canManageSystemicConsensus
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBallotCastResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ISystemicConsensusService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/** Soft cap: [SystemicConsensusDto.tooManyOptionsWarning] flips true past this many options, but addOption still succeeds. */
private const val MAX_OPTIONS_SOFT = 10

/** Hard cap: `addOption` throws a [ConflictException] once this many options already exist. */
private const val MAX_OPTIONS_HARD = 25

/** Security-audit MAJOR-4 fix -- hard ceiling on [SystemicConsensusOpenInput.maxRounds]: each `reopenRating` round is a fresh secret-ballot pause/resume cycle (see [SecretBallotStreamGuard]), so an unbounded value would let a single SystemicConsensus drive an unbounded number of LiveKit StopEgress/restart cycles over the maximum allowed timeout window before `resumeStreamsForMeeting`'s own [DefaultSecretBallotStreamGuard.resumeRateLimiter] budget would even matter. */
private const val MAX_ROUNDS_HARD_CAP = 10

private const val STATUS_QUO_OPTION_LABEL = "Status quo (no change)"
private const val RECEIPT_CODE_BYTES = 20 // 160 bits, comfortably above the >=128-bit KDoc floor -- same as ElectionService.
private const val RECEIPT_CODE_MAX_ATTEMPTS = 5

private val secureRandom = SecureRandom()
private val GROUP_CONFLICT_THRESHOLD_RANGE = BigDecimal.ZERO..BigDecimal.ONE

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- carries what
 * [SystemicConsensusService.freezeOptions]/[SystemicConsensusService.reopenRating]'s own
 * OUTSIDE-transaction [network.lapis.cloud.server.conference.SecretBallotStreamGuard
 * .quiesceStreamsForMeeting] call needs, out of their transactions. Same shape as
 * [ElectionService]'s own `OpenVotingPrep`, shared here between the two call sites since both mean
 * exactly the same thing ("this SystemicConsensus just entered RATING").
 */
private data class OpenRatingPrep(
    val meetingId: Uuid,
    val secret: Boolean,
)

/**
 * Systemic Consensus (V0.2.5): lowest-cumulative-resistance consensus tool. Implements
 * [ISystemicConsensusService] -- see that interface's KDoc for the full lifecycle (`openSystemicConsensus`
 * -> `addOption`/`removeOption` -> `freezeOptions` -> `castResistanceBallot` -> `closeRating` ->
 * `evaluate`, with `reopenRating` as the discuss-and-revote loop back into
 * `castResistanceBallot`) and `03 Bereiche/Lapis Cloud/Systemic Consensus.md` for the concept
 * document. Reuses [insertResolutionRow] (`ResolutionBook.kt`) and [eligibleMemberIds]
 * (`CommitteeEligibility.kt`) so a SystemicConsensus's tally lands in the same resolution book
 * [GovernanceService]/`ElectionService` write to, tagged [ResolutionMode.SYSTEMIC_CONSENSUS] --
 * only when [network.lapis.cloud.shared.domain.SystemicConsensusBindingness.BINDING].
 *
 * Anonymity is a practical DB-level table-split, not cryptography -- the identical mechanism
 * `ElectionService.castElectionBallot` already uses (`systemic_consensus_ballot.member_id` is nullable and
 * always `NULL` on the `secret` path; `systemic_consensus_participation` carries the "this member rated"
 * proof instead; ballot timestamps are coarsened to the calendar date to avoid a
 * `voted_at = cast_at` re-identification join -- see `09-systemic-consensus.kuml.kts`'s file header
 * for the full rationale). The one structural addition over Election's shape is the `round` column on
 * every participation-tracking table, letting a `reopenRating`'d ratingRound keep prior
 * rounds' rows (DSGVO retention) while a tally only ever counts the *current* `round`.
 *
 * Same "simple-transaction" style as [GovernanceService]/`ElectionService`: follow-up queries per row
 * ([memberDisplayName], option lists) rather than aliased multi-joins.
 */
class SystemicConsensusService(
    private val call: ApplicationCall,
    /**
     * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" (D6) -- NO default
     * value, same reasoning as [ElectionService]'s own `streamGuard` KDoc.
     */
    private val streamGuard: SecretBallotStreamGuard,
) : ISystemicConsensusService {
    override suspend fun openSystemicConsensus(input: SystemicConsensusOpenInput): SystemicConsensusDto {
        val current = resolveCurrentMember(call)
        val aId = input.motionId.toUuidOrNotFound("Motion")
        return transaction {
            val motionRow =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion ${input.motionId} not found")
            val committeeId = motionRow[MotionTable.targetCommitteeId]
            if (!current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
            if (motionRow[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("Motion ${input.motionId} is ${motionRow[MotionTable.status]}, expected SCHEDULED")
            }
            val sId = motionRow[MotionTable.meetingId] ?: throw ConflictException("Motion ${input.motionId} has no scheduled Meeting")

            val hasActive =
                SystemicConsensusTable
                    .selectAll()
                    .where {
                        (SystemicConsensusTable.motionId eq aId) and
                            (SystemicConsensusTable.status neq SystemicConsensusStatus.ABORTED)
                    }.count() > 0
            if (hasActive) {
                throw ConflictException("Motion ${input.motionId} already has an open or resolved SystemicConsensus")
            }

            if (input.scaleMax < 1) throw ConflictException("scaleMax must be at least 1, got ${input.scaleMax}")
            if (input.maxRounds < 1 || input.maxRounds > MAX_ROUNDS_HARD_CAP) {
                throw ConflictException("maxRounds must be between 1 and $MAX_ROUNDS_HARD_CAP, got ${input.maxRounds}")
            }
            if (input.groupConflictViableThreshold !in GROUP_CONFLICT_THRESHOLD_RANGE) {
                throw ConflictException("groupConflictViableThreshold must be in 0..1, got ${input.groupConflictViableThreshold}")
            }
            if (input.groupConflictWarnThreshold !in GROUP_CONFLICT_THRESHOLD_RANGE) {
                throw ConflictException("groupConflictWarnThreshold must be in 0..1, got ${input.groupConflictWarnThreshold}")
            }

            val id = Uuid.random()
            val now = nowLocalDateTime()
            SystemicConsensusTable.insert {
                it[SystemicConsensusTable.id] = id
                it[SystemicConsensusTable.motionId] = aId
                it[SystemicConsensusTable.meetingId] = sId
                it[title] = motionRow[MotionTable.title]
                it[status] = SystemicConsensusStatus.COLLECTION
                it[secret] = input.secret
                it[scaleMax] = input.scaleMax
                it[aggregation] = input.aggregation
                it[tiebreakRule] = input.tiebreakRule
                it[groupConflictViableThreshold] = input.groupConflictViableThreshold
                it[groupConflictWarnThreshold] = input.groupConflictWarnThreshold
                it[statusQuoOptionAuto] = input.statusQuoOptionAuto
                it[bindingness] = input.bindingness
                it[maxRounds] = input.maxRounds
                it[round] = 1
                it[winnerOptionId] = null
                it[openedBy] = current.memberId
                it[openedAt] = now
                it[ratingOpenedAt] = null
                it[ratingClosedAt] = null
                it[tallyRunAt] = null
                it[resolutionId] = null
            }
            if (input.statusQuoOptionAuto) {
                SystemicConsensusOptionTable.insert {
                    it[SystemicConsensusOptionTable.id] = Uuid.random()
                    it[SystemicConsensusOptionTable.systemicConsensusId] = id
                    it[label] = STATUS_QUO_OPTION_LABEL
                    it[position] = 0
                    it[isStatusQuoOption] = true
                    it[createdBy] = current.memberId
                }
            }
            loadSystemicConsensus(id)
        }
    }

    override suspend fun addOption(
        systemicConsensusId: String,
        input: SystemicConsensusOptionInput,
    ): SystemicConsensusOptionDto {
        val current = resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        return transaction {
            val row = requireSystemicConsensusRow(kId)
            if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.COLLECTION) {
                throw ConflictException(
                    "SystemicConsensus $systemicConsensusId is ${row[SystemicConsensusTable.status]}, expected COLLECTION",
                )
            }
            val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
            if (!current.isPrivileged) {
                val eligible = eligibleMembersOf(committeeId = committeeId, meetingId = row[SystemicConsensusTable.meetingId])
                if (current.memberId !in eligible) throw ForbiddenException()
            }
            val optionCount =
                SystemicConsensusOptionTable
                    .selectAll()
                    .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                    .count()
            if (optionCount >= MAX_OPTIONS_HARD) {
                throw ConflictException("SystemicConsensus $systemicConsensusId already has $MAX_OPTIONS_HARD options (hard cap)")
            }
            val nextPosition =
                (
                    SystemicConsensusOptionTable
                        .selectAll()
                        .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                        .maxOfOrNull { it[SystemicConsensusOptionTable.position] } ?: -1
                ) + 1
            val id = Uuid.random()
            SystemicConsensusOptionTable.insert {
                it[SystemicConsensusOptionTable.id] = id
                it[SystemicConsensusOptionTable.systemicConsensusId] = kId
                it[label] = input.label
                it[position] = nextPosition
                it[isStatusQuoOption] = false
                it[createdBy] = current.memberId
            }
            loadOption(id)
        }
    }

    override suspend fun removeOption(optionId: String): SystemicConsensusDto {
        val current = resolveCurrentMember(call)
        val oId = optionId.toUuidOrNotFound("SystemicConsensusOption")
        return transaction {
            val optionRow =
                SystemicConsensusOptionTable.selectAll().where { SystemicConsensusOptionTable.id eq oId }.singleOrNull()
                    ?: throw NotFoundException("SystemicConsensusOption $optionId not found")
            val kId = optionRow[SystemicConsensusOptionTable.systemicConsensusId]
            val row = requireSystemicConsensusRow(kId)
            if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.COLLECTION) {
                throw ConflictException(
                    "SystemicConsensus ${row[SystemicConsensusTable.id]} is ${row[SystemicConsensusTable.status]}, expected COLLECTION",
                )
            }
            if (optionRow[SystemicConsensusOptionTable.isStatusQuoOption]) {
                throw ConflictException("The status quo option option cannot be removed")
            }
            val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
            val isProposer = optionRow[SystemicConsensusOptionTable.createdBy] == current.memberId
            if (!isProposer && !current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
            SystemicConsensusOptionTable.deleteWhere { SystemicConsensusOptionTable.id eq oId }
            loadSystemicConsensus(kId)
        }
    }

    override suspend fun listOptions(systemicConsensusId: String): List<SystemicConsensusOptionDto> {
        resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        return transaction {
            requireSystemicConsensusRow(kId)
            SystemicConsensusOptionTable
                .selectAll()
                .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                .orderBy(SystemicConsensusOptionTable.position)
                .map { it.toSystemicConsensusOptionDto() }
        }
    }

    override suspend fun freezeOptions(systemicConsensusId: String): SystemicConsensusDto {
        val current = resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        // V1.0 Videokonferenzen, Wave 9 -- same transaction -> network-call-outside-it restructure as
        // ElectionService.openVoting (D7), same "lock rooms after the (potentially large) eligibility
        // snapshot, not before" ordering (Stolperfalle §9.7).
        val prep =
            transaction {
                val row = requireSystemicConsensusRow(kId)
                val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
                if (!current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
                if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.COLLECTION) {
                    throw ConflictException(
                        "SystemicConsensus $systemicConsensusId is ${row[SystemicConsensusTable.status]}, expected COLLECTION",
                    )
                }
                val optionCount =
                    SystemicConsensusOptionTable
                        .selectAll()
                        .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                        .count()
                if (optionCount == 0L) throw ConflictException("SystemicConsensus $systemicConsensusId has no options to freeze")

                val meetingId = row[SystemicConsensusTable.meetingId]
                val secret = row[SystemicConsensusTable.secret]
                snapshotEligibility(
                    systemicConsensusId = kId,
                    committeeId = committeeId,
                    meetingId = meetingId,
                    round = row[SystemicConsensusTable.round],
                )
                val affectedRooms =
                    if (!secret) {
                        emptyList()
                    } else {
                        SecretBallotStreamLock.lockRooms(SecretBallotStreamLock.roomIdsForMeeting(meetingId))
                    }
                SystemicConsensusTable.update({ SystemicConsensusTable.id eq kId }) {
                    it[status] = SystemicConsensusStatus.RATING
                    it[ratingOpenedAt] = nowLocalDateTime()
                }
                if (secret) {
                    ConferenceStreamPauseCoordinator.markPausingForSecretBallot(
                        roomIds = affectedRooms,
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                    )
                }
                OpenRatingPrep(meetingId = meetingId, secret = secret)
            }
        if (prep.secret) streamGuard.quiesceStreamsForMeeting(prep.meetingId)
        return transaction { loadSystemicConsensus(kId) }
    }

    override suspend fun castResistanceBallot(input: SystemicConsensusBallotInput): SystemicConsensusBallotCastResultDto {
        val current = resolveCurrentMember(call)
        val kId = input.systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        return transaction {
            // ANTRAG membership-gate audit (2026-07-30): closes the gap disclosed since V0.7.2 --
            // an ANTRAG applicant must not be able to cast a (potentially BINDING) resistance
            // ballot before board approval. Defense in depth: the eligible check below only
            // re-validates against the SystemicConsensusEligibleVoterTable snapshot taken at
            // freezeOptions/reopenRating time, itself derived from eligibleMemberIds -- for a
            // non-GENERAL_ASSEMBLY Committee that is raw Committee membership. As of the
            // addCommitteeMember root-cause fix (2026-07-30), GovernanceService.addCommitteeMember
            // re-validates the seated member's status at seat-time, so this check stays as an
            // independent second layer (defense in depth against any future seating path that
            // bypasses addCommitteeMember, or a legacy row predating that fix). Member-only
            // (AKTIV), not requireActiveOrGuestMembership -- guests never get vote weight in this
            // project's concept.
            requireActiveMembership(memberId = current.memberId)
            val row = requireSystemicConsensusRow(kId)
            if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.RATING) {
                throw ConflictException(
                    "SystemicConsensus ${input.systemicConsensusId} is ${row[SystemicConsensusTable.status]}, expected RATING",
                )
            }
            // V1.0 Videokonferenzen, Wave 9 -- fail-closed gate (D3), only for a secret
            // SystemicConsensus, same "strictly BEFORE the ExposedSQLException-catching try block
            // below" placement as ElectionService.castElectionBallot (Stolperfalle §9.6).
            if (row[SystemicConsensusTable.secret]) {
                SecretBallotStreamLock.requireStreamQuiescedForBallot(row[SystemicConsensusTable.meetingId])
            }
            val round = row[SystemicConsensusTable.round]
            val eligible =
                SystemicConsensusEligibleVoterTable
                    .selectAll()
                    .where {
                        (SystemicConsensusEligibleVoterTable.systemicConsensusId eq kId) and
                            (SystemicConsensusEligibleVoterTable.memberId eq current.memberId) and
                            (SystemicConsensusEligibleVoterTable.round eq round)
                    }.count() > 0
            if (!eligible) throw ForbiddenException()

            val validOptionIds =
                SystemicConsensusOptionTable
                    .selectAll()
                    .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                    .map { it[SystemicConsensusOptionTable.id] }
                    .toSet()
            val inputOptionIds =
                input.resistances.keys
                    .map { it.toUuidOrNotFound("SystemicConsensusOption") }
                    .toSet()
            if (inputOptionIds != validOptionIds) {
                throw ConflictException(
                    "Ballot must rate exactly the ${validOptionIds.size} frozen option(s) once each, got ${inputOptionIds.size}",
                )
            }
            val scaleMax = row[SystemicConsensusTable.scaleMax]
            val resistancesByOption = input.resistances.mapKeys { (optionIdStr, _) -> Uuid.parse(optionIdStr) }
            resistancesByOption.values.forEach { value ->
                if (value !in 0..scaleMax) throw ConflictException("Resistance value must be in 0..$scaleMax, got $value")
            }

            val secret = row[SystemicConsensusTable.secret]
            val now = nowLocalDateTime()
            // De-anonymization guard: same day-coarsening rationale as ElectionService.castElectionBallot --
            // see 09-systemic-consensus.kuml.kts's file header for the full rationale.
            val castAt = if (secret) LocalDateTime(now.date, LocalTime(0, 0)) else now
            try {
                if (secret) {
                    val alreadyRated =
                        SystemicConsensusParticipationTable
                            .selectAll()
                            .where {
                                (SystemicConsensusParticipationTable.systemicConsensusId eq kId) and
                                    (SystemicConsensusParticipationTable.memberId eq current.memberId) and
                                    (SystemicConsensusParticipationTable.round eq round)
                            }.count() > 0
                    if (alreadyRated) {
                        throw ConflictException(
                            "Member ${current.memberId} already rated SystemicConsensus ${input.systemicConsensusId} in round $round",
                        )
                    }
                    SystemicConsensusParticipationTable.insert {
                        it[SystemicConsensusParticipationTable.id] = Uuid.random()
                        it[SystemicConsensusParticipationTable.systemicConsensusId] = kId
                        it[SystemicConsensusParticipationTable.memberId] = current.memberId
                        it[votedAt] = now
                        it[SystemicConsensusParticipationTable.round] = round
                    }
                } else {
                    val alreadyRated =
                        SystemicConsensusBallotTable
                            .selectAll()
                            .where {
                                (SystemicConsensusBallotTable.systemicConsensusId eq kId) and
                                    (SystemicConsensusBallotTable.memberId eq current.memberId) and
                                    (SystemicConsensusBallotTable.round eq round)
                            }.count() > 0
                    if (alreadyRated) {
                        throw ConflictException(
                            "Member ${current.memberId} already rated SystemicConsensus ${input.systemicConsensusId} in round $round",
                        )
                    }
                }

                val ballotId = Uuid.random()
                val receiptCode = generateUniqueReceiptCode(kId)
                SystemicConsensusBallotTable.insert {
                    it[SystemicConsensusBallotTable.id] = ballotId
                    it[SystemicConsensusBallotTable.systemicConsensusId] = kId
                    it[SystemicConsensusBallotTable.memberId] = if (secret) null else current.memberId
                    it[SystemicConsensusBallotTable.receiptCode] = receiptCode
                    it[SystemicConsensusBallotTable.castAt] = castAt
                    it[SystemicConsensusBallotTable.round] = round
                }
                resistancesByOption.forEach { (optId, value) ->
                    SystemicConsensusResistanceTable.insert {
                        it[SystemicConsensusResistanceTable.id] = Uuid.random()
                        it[SystemicConsensusResistanceTable.resistanceValue] = value
                        it[SystemicConsensusResistanceTable.ballotId] = ballotId
                        it[SystemicConsensusResistanceTable.optionId] = optId
                    }
                }
                SystemicConsensusBallotCastResultDto(
                    id = ballotId.toString(),
                    castAt = castAt,
                    receiptCode = if (secret) receiptCode else null,
                )
            } catch (e: ExposedSQLException) {
                // Application-level pre-checks above are racy under concurrency; the DB-level
                // UNIQUE(systemic_consensus_id, member_id, round) constraint (on systemic_consensus_participation
                // for the secret path, on systemic_consensus_ballot for the non-secret path) is the
                // real backstop -- same convention as ElectionService.castElectionBallot.
                throw ConflictException(
                    "Member ${current.memberId} already rated SystemicConsensus ${input.systemicConsensusId} in round $round",
                )
            }
        }
    }

    override suspend fun closeRating(systemicConsensusId: String): SystemicConsensusDto {
        val current = resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        val closed =
            transaction {
                val row = requireSystemicConsensusRow(kId)
                val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
                if (!current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
                if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.RATING) {
                    throw ConflictException(
                        "SystemicConsensus $systemicConsensusId is ${row[SystemicConsensusTable.status]}, expected RATING",
                    )
                }
                SystemicConsensusTable.update({ SystemicConsensusTable.id eq kId }) {
                    it[status] = SystemicConsensusStatus.CLOSED
                    it[ratingClosedAt] = nowLocalDateTime()
                }
                row[SystemicConsensusTable.secret] to row[SystemicConsensusTable.meetingId]
            }
        // V1.0 Videokonferenzen, Wave 9 -- Auto-Resume AFTER the transaction above has committed (D7).
        val (secret, meetingId) = closed
        if (secret) streamGuard.resumeStreamsForMeeting(meetingId)
        return transaction { loadSystemicConsensus(kId) }
    }

    override suspend fun evaluate(systemicConsensusId: String): SystemicConsensusResultDto {
        val current = resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        return transaction {
            val row = requireSystemicConsensusRow(kId)
            val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
            if (!current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
            if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.CLOSED) {
                throw ConflictException("SystemicConsensus $systemicConsensusId is ${row[SystemicConsensusTable.status]}, expected CLOSED")
            }
            val round = row[SystemicConsensusTable.round]

            val optionRows =
                SystemicConsensusOptionTable
                    .selectAll()
                    .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                    .orderBy(SystemicConsensusOptionTable.position)
                    .toList()
            val optionIds = optionRows.map { it[SystemicConsensusOptionTable.id] }
            val passivloesungOptionId =
                optionRows.firstOrNull { it[SystemicConsensusOptionTable.isStatusQuoOption] }?.get(
                    SystemicConsensusOptionTable.id,
                )

            val ballotIds =
                SystemicConsensusBallotTable
                    .selectAll()
                    .where { (SystemicConsensusBallotTable.systemicConsensusId eq kId) and (SystemicConsensusBallotTable.round eq round) }
                    .map { it[SystemicConsensusBallotTable.id] }
            val resistanceRows =
                if (ballotIds.isEmpty()) {
                    emptyList()
                } else {
                    SystemicConsensusResistanceTable
                        .selectAll()
                        .where { SystemicConsensusResistanceTable.ballotId inList ballotIds }
                        .toList()
                }
            val resistancesByBallot =
                resistanceRows.groupBy(
                    { it[SystemicConsensusResistanceTable.ballotId] },
                    { it[SystemicConsensusResistanceTable.optionId] to it[SystemicConsensusResistanceTable.resistanceValue] },
                )
            val ballots = ballotIds.map { szId -> SystemicConsensusBallotData(resistances = resistancesByBallot[szId].orEmpty().toMap()) }

            val ergebnis =
                computeSystemicConsensusResult(
                    ballots = ballots,
                    optionIds = optionIds,
                    scaleMax = row[SystemicConsensusTable.scaleMax],
                    aggregation = row[SystemicConsensusTable.aggregation],
                    tiebreak = row[SystemicConsensusTable.tiebreakRule],
                    groupConflictViableThreshold = row[SystemicConsensusTable.groupConflictViableThreshold].toDouble(),
                    groupConflictWarnThreshold = row[SystemicConsensusTable.groupConflictWarnThreshold].toDouble(),
                )

            SystemicConsensusTable.update({ SystemicConsensusTable.id eq kId }) {
                it[status] = SystemicConsensusStatus.EVALUATED
                it[tallyRunAt] = nowLocalDateTime()
                it[winnerOptionId] = ergebnis.winnerOptionId
            }

            if (row[SystemicConsensusTable.bindingness] == SystemicConsensusBindingness.BINDING) {
                // Only a genuinely undecided result (winnerOptionId == null -- REPEAT or
                // zero ballots) maps to POSTPONED. ergebnis.tie alone is not the right signal here:
                // it stays true even when LOWEST_MAX_RESISTANCE/LOWEST_STD_DEV resolved a
                // concrete winner despite a raw KW tie -- see SkErgebnis KDoc.
                val resolutionStatus =
                    when {
                        ergebnis.winnerOptionId == null -> ResolutionStatus.POSTPONED
                        ergebnis.winnerOptionId == passivloesungOptionId -> ResolutionStatus.REJECTED
                        else -> ResolutionStatus.ADOPTED
                    }
                val meeting = MeetingTable.selectAll().where { MeetingTable.id eq row[SystemicConsensusTable.meetingId] }.single()
                // forUpdate(): same lock discipline as GovernanceService.resolveMotion/closeVote's
                // own Motion-row read -- see that KDoc for the submitMotion race this closes.
                val motionRow =
                    MotionTable
                        .selectAll()
                        .where { MotionTable.id eq row[SystemicConsensusTable.motionId] }
                        .forUpdate()
                        .single()
                // Änderungsantrag (V0.2.6) ordering guard: this BINDING branch also finalizes the
                // underlying Motion to a terminal status, so it needs the same soundness guard
                // GovernanceService.resolveMotion/closeVote enforce -- see
                // requireNoPendingAmendments KDoc. An ADVISORY SystemicConsensus never reaches
                // this branch (never writes a Resolution, never touches Motion status), so no
                // guard is needed there.
                if (motionRow[MotionTable.amendsMotionId] == null) requireNoPendingAmendments(motionRow[MotionTable.id])
                // Current working text, not the immutable original -- same as
                // GovernanceService.resolveMotion/closeVote, see MotionDto.effectiveText KDoc.
                val effectiveText = motionRow[MotionTable.currentText] ?: motionRow[MotionTable.text]
                val resolutionInput =
                    ResolutionInput(
                        agendaItemId = motionRow[MotionTable.agendaItemId]?.toString(),
                        title = motionRow[MotionTable.title],
                        text = effectiveText,
                        votesYes = 0,
                        votesNo = 0,
                        votesAbstain = 0,
                        status = resolutionStatus,
                    )
                val resolution =
                    insertResolutionRow(
                        sId = row[SystemicConsensusTable.meetingId],
                        committeeId = committeeId,
                        scheduledDate = meeting[MeetingTable.scheduledAt].date,
                        input = resolutionInput,
                        current = current,
                        resolutionMode = ResolutionMode.SYSTEMIC_CONSENSUS,
                        systemicConsensusId = kId,
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
                SystemicConsensusTable.update({ SystemicConsensusTable.id eq kId }) {
                    it[SystemicConsensusTable.resolutionId] = Uuid.parse(resolution.id)
                }
                // V0.5.3 GoBD audit log: called last, after MotionTable.update/SystemicConsensusTable
                // .update, so this satisfies AuditLogRecorder's deadlock-avoidance contract -- see
                // auditResolutionCreate KDoc.
                auditResolutionCreate(resolution = resolution, current = current)
            }

            toSystemicConsensusResultDto(systemicConsensusId = kId, ergebnis = ergebnis)
        }
    }

    override suspend fun reopenRating(systemicConsensusId: String): SystemicConsensusDto {
        val current = resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        // V1.0 Videokonferenzen, Wave 9 -- same transaction -> network-call-outside-it restructure as
        // freezeOptions/ElectionService.openVoting (D7).
        val prep =
            transaction {
                val row = requireSystemicConsensusRow(kId)
                val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
                if (!current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
                val status = row[SystemicConsensusTable.status]
                if (status != SystemicConsensusStatus.CLOSED && status != SystemicConsensusStatus.EVALUATED) {
                    throw ConflictException("SystemicConsensus $systemicConsensusId is $status, expected CLOSED or EVALUATED")
                }
                if (row[SystemicConsensusTable.resolutionId] != null) {
                    throw ConflictException(
                        "SystemicConsensus $systemicConsensusId already has a binding Resolution " +
                            "(${row[SystemicConsensusTable.resolutionId]}) recorded -- reopening would orphan it in the resolution book",
                    )
                }
                val round = row[SystemicConsensusTable.round]
                val maxRounds = row[SystemicConsensusTable.maxRounds]
                if (round >= maxRounds) {
                    throw ConflictException("SystemicConsensus $systemicConsensusId already reached maxRounds=$maxRounds")
                }
                val newRound = round + 1
                val meetingId = row[SystemicConsensusTable.meetingId]
                val secret = row[SystemicConsensusTable.secret]
                snapshotEligibility(systemicConsensusId = kId, committeeId = committeeId, meetingId = meetingId, round = newRound)
                val affectedRooms =
                    if (!secret) {
                        emptyList()
                    } else {
                        SecretBallotStreamLock.lockRooms(SecretBallotStreamLock.roomIdsForMeeting(meetingId))
                    }
                SystemicConsensusTable.update({ SystemicConsensusTable.id eq kId }) {
                    it[SystemicConsensusTable.status] = SystemicConsensusStatus.RATING
                    it[SystemicConsensusTable.round] = newRound
                    it[ratingOpenedAt] = nowLocalDateTime()
                    it[ratingClosedAt] = null
                    it[tallyRunAt] = null
                    it[winnerOptionId] = null
                }
                if (secret) {
                    ConferenceStreamPauseCoordinator.markPausingForSecretBallot(
                        roomIds = affectedRooms,
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                    )
                }
                OpenRatingPrep(meetingId = meetingId, secret = secret)
            }
        if (prep.secret) streamGuard.quiesceStreamsForMeeting(prep.meetingId)
        return transaction { loadSystemicConsensus(kId) }
    }

    override suspend fun abortSystemicConsensus(systemicConsensusId: String): SystemicConsensusDto {
        val current = resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        val aborted =
            transaction {
                val row = requireSystemicConsensusRow(kId)
                val committeeId = requireMotionCommitteeId(row[SystemicConsensusTable.motionId])
                if (!current.canManageSystemicConsensus(committeeId)) throw ForbiddenException()
                val status = row[SystemicConsensusTable.status]
                if (status == SystemicConsensusStatus.EVALUATED || status == SystemicConsensusStatus.ABORTED) {
                    throw ConflictException("SystemicConsensus $systemicConsensusId is already $status")
                }
                SystemicConsensusTable.update({ SystemicConsensusTable.id eq kId }) {
                    it[SystemicConsensusTable.status] =
                        SystemicConsensusStatus.ABORTED
                }
                // V1.0 Videokonferenzen, Wave 9 -- Auto-Resume only owed if the prior status was
                // RATING (the only status this wave ever pauses for) -- COLLECTION never triggered a
                // pause, so no resume attempt is made for it.
                val wasSecretAndWasRating = row[SystemicConsensusTable.secret] && status == SystemicConsensusStatus.RATING
                wasSecretAndWasRating to row[SystemicConsensusTable.meetingId]
            }
        val (wasSecretAndWasRating, meetingId) = aborted
        if (wasSecretAndWasRating) streamGuard.resumeStreamsForMeeting(meetingId)
        return transaction { loadSystemicConsensus(kId) }
    }

    override suspend fun getSystemicConsensus(systemicConsensusId: String): SystemicConsensusDto {
        resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        return transaction { loadSystemicConsensus(kId) }
    }

    override suspend fun listSystemicConsensuses(
        motionId: String?,
        status: SystemicConsensusStatus?,
    ): List<SystemicConsensusDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (motionId != null) conditions += (SystemicConsensusTable.motionId eq motionId.toUuidOrNotFound("Motion"))
            if (status != null) conditions += (SystemicConsensusTable.status eq status)
            val baseQuery = SystemicConsensusTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toSystemicConsensusDto() }
        }
    }

    override suspend fun listResistanceBallots(systemicConsensusId: String): List<SystemicConsensusBallotDto> {
        resolveCurrentMember(call)
        val kId = systemicConsensusId.toUuidOrNotFound("SystemicConsensus")
        return transaction {
            val row = requireSystemicConsensusRow(kId)
            // Pre-tally secrecy gate: same invariant as ElectionService.listElectionBallots's
            // revealLabels -- see SystemicConsensusBallotDto KDoc.
            val revealValues =
                !row[SystemicConsensusTable.secret] || row[SystemicConsensusTable.status] == SystemicConsensusStatus.EVALUATED
            val round = row[SystemicConsensusTable.round]
            SystemicConsensusBallotTable
                .selectAll()
                .where { (SystemicConsensusBallotTable.systemicConsensusId eq kId) and (SystemicConsensusBallotTable.round eq round) }
                .map { it.toSystemicConsensusBallotDto(revealValues) }
        }
    }

    private fun requireSystemicConsensusRow(systemicConsensusId: Uuid): ResultRow =
        SystemicConsensusTable
            .selectAll()
            .where { SystemicConsensusTable.id eq systemicConsensusId }
            .singleOrNull() ?: throw NotFoundException("SystemicConsensus $systemicConsensusId not found")

    private fun requireMotionCommitteeId(motionId: Uuid): Uuid =
        MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.targetCommitteeId]

    private fun eligibleMembersOf(
        committeeId: Uuid,
        meetingId: Uuid,
    ): Set<Uuid> {
        val meetingRow = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.single()
        val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
        return eligibleMemberIds(committeeRow = committeeRow, scheduledDate = meetingRow[MeetingTable.scheduledAt].date)
    }

    /** Snapshots current eligibility into `systemic_consensus_eligible_voter` for [round] -- shared by [freezeOptions] and [reopenRating]. */
    private fun snapshotEligibility(
        systemicConsensusId: Uuid,
        committeeId: Uuid,
        meetingId: Uuid,
        round: Int,
    ) {
        val eligible = eligibleMembersOf(committeeId = committeeId, meetingId = meetingId)
        eligible.forEach { mId ->
            SystemicConsensusEligibleVoterTable.insert {
                it[id] = Uuid.random()
                it[SystemicConsensusEligibleVoterTable.systemicConsensusId] = systemicConsensusId
                it[memberId] = mId
                it[SystemicConsensusEligibleVoterTable.round] = round
            }
        }
    }

    private fun loadSystemicConsensus(id: Uuid): SystemicConsensusDto =
        SystemicConsensusTable
            .selectAll()
            .where { SystemicConsensusTable.id eq id }
            .singleOrNull()
            ?.toSystemicConsensusDto() ?: throw NotFoundException("SystemicConsensus $id not found")

    private fun loadOption(id: Uuid): SystemicConsensusOptionDto =
        SystemicConsensusOptionTable
            .selectAll()
            .where { SystemicConsensusOptionTable.id eq id }
            .single()
            .toSystemicConsensusOptionDto()

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { mId ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq mId }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    /** Retries a small, bounded number of times on a receipt-code collision -- see [ElectionService]'s equivalent KDoc. */
    private fun generateUniqueReceiptCode(systemicConsensusId: Uuid): String {
        repeat(RECEIPT_CODE_MAX_ATTEMPTS) {
            val bytes = ByteArray(RECEIPT_CODE_BYTES)
            secureRandom.nextBytes(bytes)
            val candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val exists =
                SystemicConsensusBallotTable
                    .selectAll()
                    .where {
                        (SystemicConsensusBallotTable.systemicConsensusId eq systemicConsensusId) and
                            (SystemicConsensusBallotTable.receiptCode eq candidate)
                    }.count() > 0
            if (!exists) return candidate
        }
        throw ConflictException(
            "Failed to generate a unique receipt code for SystemicConsensus $systemicConsensusId after $RECEIPT_CODE_MAX_ATTEMPTS attempts",
        )
    }

    private fun ResultRow.toSystemicConsensusDto(): SystemicConsensusDto {
        val kId = this[SystemicConsensusTable.id]
        val optionRows =
            SystemicConsensusOptionTable
                .selectAll()
                .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
                .orderBy(SystemicConsensusOptionTable.position)
                .toList()
        return SystemicConsensusDto(
            id = kId.toString(),
            motionId = this[SystemicConsensusTable.motionId].toString(),
            meetingId = this[SystemicConsensusTable.meetingId].toString(),
            title = this[SystemicConsensusTable.title],
            status = this[SystemicConsensusTable.status],
            secret = this[SystemicConsensusTable.secret],
            scaleMax = this[SystemicConsensusTable.scaleMax],
            aggregation = this[SystemicConsensusTable.aggregation],
            tiebreakRule = this[SystemicConsensusTable.tiebreakRule],
            groupConflictViableThreshold = this[SystemicConsensusTable.groupConflictViableThreshold],
            groupConflictWarnThreshold = this[SystemicConsensusTable.groupConflictWarnThreshold],
            statusQuoOptionAuto = this[SystemicConsensusTable.statusQuoOptionAuto],
            bindingness = this[SystemicConsensusTable.bindingness],
            maxRounds = this[SystemicConsensusTable.maxRounds],
            round = this[SystemicConsensusTable.round],
            winnerOptionId = this[SystemicConsensusTable.winnerOptionId]?.toString(),
            openedById = this[SystemicConsensusTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[SystemicConsensusTable.openedBy]).orEmpty(),
            openedAt = this[SystemicConsensusTable.openedAt],
            ratingOpenedAt = this[SystemicConsensusTable.ratingOpenedAt],
            ratingClosedAt = this[SystemicConsensusTable.ratingClosedAt],
            tallyRunAt = this[SystemicConsensusTable.tallyRunAt],
            resolutionId = this[SystemicConsensusTable.resolutionId]?.toString(),
            options = optionRows.map { it.toSystemicConsensusOptionDto() },
            tooManyOptionsWarning = optionRows.size > MAX_OPTIONS_SOFT,
        )
    }

    private fun ResultRow.toSystemicConsensusOptionDto(): SystemicConsensusOptionDto =
        SystemicConsensusOptionDto(
            id = this[SystemicConsensusOptionTable.id].toString(),
            systemicConsensusId = this[SystemicConsensusOptionTable.systemicConsensusId].toString(),
            label = this[SystemicConsensusOptionTable.label],
            position = this[SystemicConsensusOptionTable.position],
            isStatusQuoOption = this[SystemicConsensusOptionTable.isStatusQuoOption],
            createdById = this[SystemicConsensusOptionTable.createdBy].toString(),
            createdByDisplayName = memberDisplayName(this[SystemicConsensusOptionTable.createdBy]).orEmpty(),
        )

    private fun ResultRow.toSystemicConsensusBallotDto(revealValues: Boolean): SystemicConsensusBallotDto {
        val ballotId = this[SystemicConsensusBallotTable.id]
        val memberId = this[SystemicConsensusBallotTable.memberId]
        val resistances =
            if (revealValues) {
                SystemicConsensusResistanceTable
                    .selectAll()
                    .where { SystemicConsensusResistanceTable.ballotId eq ballotId }
                    .associate {
                        it[SystemicConsensusResistanceTable.optionId].toString() to it[SystemicConsensusResistanceTable.resistanceValue]
                    }
            } else {
                emptyMap()
            }
        return SystemicConsensusBallotDto(
            id = ballotId.toString(),
            systemicConsensusId = this[SystemicConsensusBallotTable.systemicConsensusId].toString(),
            memberId = memberId?.toString(),
            memberDisplayName = memberDisplayName(memberId),
            resistances = resistances,
            castAt = this[SystemicConsensusBallotTable.castAt],
            round = this[SystemicConsensusBallotTable.round],
        )
    }

    private fun toSystemicConsensusResultDto(
        systemicConsensusId: Uuid,
        ergebnis: SkErgebnis,
    ): SystemicConsensusResultDto =
        SystemicConsensusResultDto(
            systemicConsensusId = systemicConsensusId.toString(),
            optionResults =
                ergebnis.optionResults.map {
                    SystemicConsensusOptionResultDto(
                        optionId = it.optionId.toString(),
                        cumulativeResistance = it.cumulativeResistance,
                        meanResistance = it.meanResistance,
                        maxResistance = it.maxResistance,
                        standardDeviation = it.standardDeviation,
                        consensusIndex = it.consensusIndex,
                        distribution = it.distribution,
                    )
                },
            winnerOptionId = ergebnis.winnerOptionId?.toString(),
            tie = ergebnis.tie,
            tiebreakApplied = ergebnis.tiebreakApplied,
            consensusViable = ergebnis.consensusViable,
            groupConflictWarning = ergebnis.groupConflictWarning,
            noRatings = ergebnis.noRatings,
        )

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}
