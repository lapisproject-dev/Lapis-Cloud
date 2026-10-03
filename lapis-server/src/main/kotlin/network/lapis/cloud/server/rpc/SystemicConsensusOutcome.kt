package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.shared.domain.DisclosureRules
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.28: the pure outcome calculation of a Systemic Consensus' *current* round, extracted unchanged from
 * [SystemicConsensusService.evaluate] so that [SystemicConsensusService.getSystemicConsensusResult] (which only
 * reads it) and `evaluate` (which persists it) can never drift apart -- same split as
 * `ElectionService.computeOutcome`. MUST run inside a transaction. Locking, status checks and the
 * resolution-book write deliberately stay in `evaluate`.
 *
 * V1.9.42: the decision is made from the full data ([SystemicConsensusOutcome.ergebnis]); what is *disclosed* is
 * reduced in [toResultDto], the only way to a [SystemicConsensusResultDto].
 */
internal fun computeSystemicConsensusOutcome(row: ResultRow): SystemicConsensusOutcome {
    val kId = row[SystemicConsensusTable.id]
    val round = row[SystemicConsensusTable.round]
    val optionIds =
        SystemicConsensusOptionTable
            .selectAll()
            .where { SystemicConsensusOptionTable.systemicConsensusId eq kId }
            .orderBy(SystemicConsensusOptionTable.position)
            .map { it[SystemicConsensusOptionTable.id] }
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
    val ballots = ballotIds.map { id -> SystemicConsensusBallotData(resistances = resistancesByBallot[id].orEmpty().toMap()) }
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
    return SystemicConsensusOutcome(ergebnis = ergebnis, ballotCount = ballotIds.size, secret = row[SystemicConsensusTable.secret])
}

/** Outcome of the current round plus what the disclosure decision needs. */
internal data class SystemicConsensusOutcome(
    val ergebnis: SkErgebnis,
    val ballotCount: Int,
    val secret: Boolean,
)

/** V1.9.42 -- `true` iff the figures of this round must be withheld. Pure. */
internal fun systemicConsensusFiguresWithheld(
    secret: Boolean,
    ballotCount: Int,
): Boolean = secret && ballotCount < DisclosureRules.MIN_ANONYMOUS_RESPONSES

/**
 * The ONLY way to a [SystemicConsensusResultDto]. The decision fields always come from the full [ergebnis]; the
 * figures (every option's aggregates, and with them the ranking) only when they are not withheld. No role
 * exception: managers and moderation get the withheld form too.
 */
internal fun SystemicConsensusOutcome.toResultDto(systemicConsensusId: Uuid): SystemicConsensusResultDto {
    val withheld = systemicConsensusFiguresWithheld(secret = secret, ballotCount = ballotCount)
    return ergebnis.toDisclosedDto(systemicConsensusId = systemicConsensusId, withheld = withheld)
}

private fun SkErgebnis.toDisclosedDto(
    systemicConsensusId: Uuid,
    withheld: Boolean,
): SystemicConsensusResultDto =
    SystemicConsensusResultDto(
        systemicConsensusId = systemicConsensusId.toString(),
        optionResults =
            if (withheld) {
                emptyList()
            } else {
                optionResults.map {
                    SystemicConsensusOptionResultDto(
                        optionId = it.optionId.toString(),
                        cumulativeResistance = it.cumulativeResistance,
                        meanResistance = it.meanResistance,
                        maxResistance = it.maxResistance,
                        standardDeviation = it.standardDeviation,
                        consensusIndex = it.consensusIndex,
                        distribution = it.distribution,
                    )
                }
            },
        winnerOptionId = winnerOptionId?.toString(),
        tie = tie,
        tiebreakApplied = tiebreakApplied,
        consensusViable = consensusViable,
        groupConflictWarning = groupConflictWarning,
        noRatings = noRatings,
        figuresWithheld = withheld,
    )

/**
 * V1.9.44 -- `true` iff the single ballots of this consensus may be delivered at all.
 * An anonymous consensus never delivers single ballots: not in any status, at no participation,
 * to no role (owner decision 2026-10-03). Pure, no DB access.
 */
internal fun systemicConsensusSingleBallotsDisclosable(secret: Boolean): Boolean = singleBallotsDisclosable(secret)

/**
 * The ONLY way to a list of [SystemicConsensusBallotDto]. Returns an empty list for an anonymous
 * consensus BEFORE any ballot/resistance row is read. For an open consensus: every ballot of the
 * current round, named, with its ratings (unchanged behaviour since V1.9.x).
 * MUST run inside a transaction. [row] is the already-loaded SystemicConsensusTable row.
 */
internal fun disclosedSystemicConsensusBallots(
    row: ResultRow,
    memberDisplayName: (Uuid?) -> String?,
): List<SystemicConsensusBallotDto> {
    if (!systemicConsensusSingleBallotsDisclosable(row[SystemicConsensusTable.secret])) return emptyList()
    val kId = row[SystemicConsensusTable.id]
    val round = row[SystemicConsensusTable.round]
    val ballotRows =
        SystemicConsensusBallotTable
            .selectAll()
            .where { (SystemicConsensusBallotTable.systemicConsensusId eq kId) and (SystemicConsensusBallotTable.round eq round) }
            .toList()
    if (ballotRows.isEmpty()) return emptyList()
    val ballotIds = ballotRows.map { it[SystemicConsensusBallotTable.id] }
    val resistancesByBallot =
        SystemicConsensusResistanceTable
            .selectAll()
            .where { SystemicConsensusResistanceTable.ballotId inList ballotIds }
            .toList()
            .groupBy(
                { it[SystemicConsensusResistanceTable.ballotId] },
                { it[SystemicConsensusResistanceTable.optionId].toString() to it[SystemicConsensusResistanceTable.resistanceValue] },
            )
    return ballotRows.map { ballot ->
        val ballotId = ballot[SystemicConsensusBallotTable.id]
        val memberId = ballot[SystemicConsensusBallotTable.memberId]
        SystemicConsensusBallotDto(
            id = ballotId.toString(),
            systemicConsensusId = ballot[SystemicConsensusBallotTable.systemicConsensusId].toString(),
            memberId = memberId?.toString(),
            memberDisplayName = memberDisplayName(memberId),
            resistances = resistancesByBallot[ballotId].orEmpty().toMap(),
            castAt = ballot[SystemicConsensusBallotTable.castAt],
            round = ballot[SystemicConsensusBallotTable.round],
        )
    }
}
