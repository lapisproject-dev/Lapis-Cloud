package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canManageSystemicConsensus
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptVerificationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Upper bound of [SystemicConsensusReads.participations] -- a list view never needs more, and it bounds the work per call. */
internal const val MAX_PARTICIPATION_BATCH = 100

/** A receipt code is 20 random bytes in unpadded URL-safe Base64 (27 characters) -- anything else cannot exist, so no query is made. */
private val RECEIPT_CODE_FORMAT = Regex("^[A-Za-z0-9_-]{27}$")

/**
 * V1.9.28 -- the read-only side of the Systemic Consensus UI. Kept out of [SystemicConsensusService] so that
 * class does not keep growing; the service only delegates. Every function MUST run inside a transaction.
 *
 * Security properties (see the V1.9.28 audit checklist): only the caller's own rows are read, the aggregated
 * result is only available once EVALUATED, and nothing here logs or echoes receipt codes. V1.9.42: an anonymous
 * result below the minimum participation carries no figures (see [toResultDto]); receipt verification returns only
 * the caller's own ratings, never an aggregate.
 */
internal object SystemicConsensusReads {
    fun result(kId: Uuid): SystemicConsensusResultDto {
        val row = requireRow(kId)
        if (row[SystemicConsensusTable.status] != SystemicConsensusStatus.EVALUATED) {
            throw ConflictException("SystemicConsensus $kId is ${row[SystemicConsensusTable.status]}, expected EVALUATED")
        }
        return computeSystemicConsensusOutcome(row).toResultDto(kId)
    }

    fun participation(
        kId: Uuid,
        current: CurrentMember,
    ): SystemicConsensusParticipationDto = participations(ids = listOf(kId), current = current).single()

    fun participations(
        ids: List<Uuid>,
        current: CurrentMember,
    ): List<SystemicConsensusParticipationDto> {
        if (ids.isEmpty()) return emptyList()
        val rows = SystemicConsensusTable.selectAll().where { SystemicConsensusTable.id inList ids }.toList()
        val byId = rows.associateBy { it[SystemicConsensusTable.id] }
        ids.firstOrNull { it !in byId }?.let { throw NotFoundException("SystemicConsensus $it not found") }

        val own = SystemicConsensusOwnParticipation.load(rows = rows, memberId = current.memberId)
        val committeeByMotion =
            MotionTable
                .selectAll()
                .where { MotionTable.id inList rows.map { it[SystemicConsensusTable.motionId] }.distinct() }
                .associate { it[MotionTable.id] to it[MotionTable.targetCommitteeId] }

        val eligibleCounts = countsByRound(rows = rows, ballots = false)
        val ballotCounts = countsByRound(rows = rows, ballots = true)
        val activeMember = current.status in MemberStatusSets.ORGANIZATION_MEMBER
        val eligibleSetCache = mutableMapOf<Pair<Uuid, Uuid>, Set<Uuid>>()

        return ids.map { id ->
            val row = byId.getValue(id)
            val status = row[SystemicConsensusTable.status]
            val round = row[SystemicConsensusTable.round]
            val flags = own.getValue(id)
            val committeeId = committeeByMotion.getValue(row[SystemicConsensusTable.motionId])
            val canPropose =
                status == SystemicConsensusStatus.COLLECTION &&
                    (
                        current.isPrivileged ||
                            current.memberId in
                            eligibleSetCache.getOrPut(committeeId to row[SystemicConsensusTable.meetingId]) {
                                eligibleMembersOf(committeeId = committeeId, meetingId = row[SystemicConsensusTable.meetingId])
                            }
                    )
            SystemicConsensusParticipationDto(
                systemicConsensusId = id.toString(),
                round = round,
                eligible = flags.eligible,
                hasRated = flags.hasRated,
                canProposeOptions = canPropose,
                canManage = current.canManageSystemicConsensus(committeeId),
                canRate =
                    status == SystemicConsensusStatus.RATING && flags.eligible == true && !flags.hasRated && activeMember,
                eligibleCount = if (row[SystemicConsensusTable.ratingOpenedAt] != null) eligibleCounts[id to round] ?: 0 else null,
                ballotCount = ballotCounts[id to round] ?: 0,
            )
        }
    }

    fun verifyReceipt(
        kId: Uuid,
        receiptCode: String,
    ): SystemicConsensusReceiptVerificationDto {
        val row = requireRow(kId)
        if (!row[SystemicConsensusTable.secret]) {
            throw ConflictException("SystemicConsensus $kId is not anonymous, there are no receipts")
        }
        val notFound =
            SystemicConsensusReceiptVerificationDto(found = false, round = null, countedInCurrentResult = false, resistances = null)
        if (!RECEIPT_CODE_FORMAT.matches(receiptCode)) return notFound
        val ballot =
            SystemicConsensusBallotTable
                .select(SystemicConsensusBallotTable.id, SystemicConsensusBallotTable.round)
                .where {
                    (SystemicConsensusBallotTable.systemicConsensusId eq kId) and
                        (SystemicConsensusBallotTable.receiptCode eq receiptCode)
                }.singleOrNull()
                ?: return notFound
        val ballotRound = ballot[SystemicConsensusBallotTable.round]
        val counted =
            ballotRound == row[SystemicConsensusTable.round] && row[SystemicConsensusTable.status] == SystemicConsensusStatus.EVALUATED
        return SystemicConsensusReceiptVerificationDto(
            found = true,
            round = ballotRound,
            countedInCurrentResult = counted,
            resistances = null,
        )
    }

    /** `(consensus id, round) -> count`, one grouped query, only for the rows' own rounds. */
    private fun countsByRound(
        rows: List<ResultRow>,
        ballots: Boolean,
    ): Map<Pair<Uuid, Int>, Int> {
        val ids = rows.map { it[SystemicConsensusTable.id] }
        val wanted = rows.associate { it[SystemicConsensusTable.id] to it[SystemicConsensusTable.round] }
        return if (ballots) {
            val n = SystemicConsensusBallotTable.id.count()
            SystemicConsensusBallotTable
                .select(SystemicConsensusBallotTable.systemicConsensusId, SystemicConsensusBallotTable.round, n)
                .where { SystemicConsensusBallotTable.systemicConsensusId inList ids }
                .groupBy(SystemicConsensusBallotTable.systemicConsensusId, SystemicConsensusBallotTable.round)
                .associate {
                    (it[SystemicConsensusBallotTable.systemicConsensusId] to it[SystemicConsensusBallotTable.round]) to
                        it[n].toInt()
                }
        } else {
            val n = SystemicConsensusEligibleVoterTable.id.count()
            SystemicConsensusEligibleVoterTable
                .select(SystemicConsensusEligibleVoterTable.systemicConsensusId, SystemicConsensusEligibleVoterTable.round, n)
                .where { SystemicConsensusEligibleVoterTable.systemicConsensusId inList ids }
                .groupBy(SystemicConsensusEligibleVoterTable.systemicConsensusId, SystemicConsensusEligibleVoterTable.round)
                .associate {
                    (it[SystemicConsensusEligibleVoterTable.systemicConsensusId] to it[SystemicConsensusEligibleVoterTable.round]) to
                        it[n].toInt()
                }
        }.filterKeys { (id, round) -> wanted[id] == round }
    }

    private fun requireRow(kId: Uuid): ResultRow =
        SystemicConsensusTable.selectAll().where { SystemicConsensusTable.id eq kId }.singleOrNull()
            ?: throw NotFoundException("SystemicConsensus $kId not found")

    private fun eligibleMembersOf(
        committeeId: Uuid,
        meetingId: Uuid,
    ): Set<Uuid> {
        val meetingRow = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.single()
        val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
        return eligibleMemberIds(committeeRow = committeeRow, scheduledDate = meetingRow[MeetingTable.scheduledAt].date)
    }
}
