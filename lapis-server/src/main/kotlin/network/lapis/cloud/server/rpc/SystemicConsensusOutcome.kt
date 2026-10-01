package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
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
 */
internal fun computeSystemicConsensusOutcome(row: ResultRow): SkErgebnis {
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
    return computeSystemicConsensusResult(
        ballots = ballots,
        optionIds = optionIds,
        scaleMax = row[SystemicConsensusTable.scaleMax],
        aggregation = row[SystemicConsensusTable.aggregation],
        tiebreak = row[SystemicConsensusTable.tiebreakRule],
        groupConflictViableThreshold = row[SystemicConsensusTable.groupConflictViableThreshold].toDouble(),
        groupConflictWarnThreshold = row[SystemicConsensusTable.groupConflictWarnThreshold].toDouble(),
    )
}

internal fun SkErgebnis.toSystemicConsensusResultDto(systemicConsensusId: Uuid): SystemicConsensusResultDto =
    SystemicConsensusResultDto(
        systemicConsensusId = systemicConsensusId.toString(),
        optionResults =
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
            },
        winnerOptionId = winnerOptionId?.toString(),
        tie = tie,
        tiebreakApplied = tiebreakApplied,
        consensusViable = consensusViable,
        groupConflictWarning = groupConflictWarning,
        noRatings = noRatings,
    )
