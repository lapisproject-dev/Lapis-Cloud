package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import kotlin.math.sqrt
import kotlin.uuid.Uuid

/**
 * One voter's complete resistance vector for a single ratingRound -- the pure-function input
 * shape for [computeSystemicConsensusResult], deliberately decoupled from
 * [network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable] so this file has zero DB
 * dependency and can be property-tested directly (see `SystemicConsensusTallyTest`), same
 * rationale as `ElectionTally.ElectionBallotData` gives. [resistances] MUST cover every frozen option
 * id exactly once -- [computeSystemicConsensusResult] rejects any ballot that doesn't.
 */
data class SystemicConsensusBallotData(
    val resistances: Map<Uuid, Int>,
)

/**
 * Per-option aggregate. [cumulativeResistance] is the classic Kumulierter Resistance (KW) = the
 * sum of every rated resistance value for this option; [meanResistance] = KW/n;
 * [consensusIndex] is the Gruppenkonflikt-Index (G-K) = KW / (n * scaleMax), always in `[0, 1]`.
 * [distribution] maps each distinct resistance value cast to how many voters cast it.
 */
data class SkOptionErgebnis(
    val optionId: Uuid,
    val cumulativeResistance: Int,
    val meanResistance: Double,
    val maxResistance: Int,
    val standardDeviation: Double,
    val consensusIndex: Double,
    val distribution: Map<Int, Int>,
)

/**
 * Result of [computeSystemicConsensusResult]. [optionResults] is sorted best-first (lowest KW
 * first). [winnerOptionId] is `null` iff [tie] is `true` AND the configured
 * [SystemicConsensusTiebreakRule.REPEAT] rule applies (or there were zero ballots) -- otherwise a
 * deterministic option-id-string-ascending fallback always yields a concrete winner even under an
 * unbroken KW tie, mirroring `computePersonnelElectionErgebnis`'s deterministic ordering.
 * [tiebreakApplied] names the rule that actually decided a KW tie (`null` when KW alone gave a
 * clear winner). [consensusViable]/[groupConflictWarning] are only meaningful when
 * [winnerOptionId] is non-null; both are `false` when [noRatings] is `true`.
 */
data class SkErgebnis(
    val optionResults: List<SkOptionErgebnis>,
    val winnerOptionId: Uuid?,
    val tie: Boolean,
    val tiebreakApplied: SystemicConsensusTiebreakRule?,
    val consensusViable: Boolean,
    val groupConflictWarning: Boolean,
    val noRatings: Boolean,
)

/**
 * Systemisches-Konsensieren tally: the option with the LOWEST cumulative resistance (KW) wins --
 * the exact opposite ranking direction of `computePersonnelElectionErgebnis`'s "most votes wins".
 * Pure function, no DB access -- exhaustively property-testable, same rationale
 * `ElectionTally`'s KDoc gives (a bug here directly changes a governance outcome).
 *
 * [aggregation] is deliberately **not** used to compute the ranking: within one SystemicConsensus,
 * [SystemicConsensusAggregation.SUM] (raw KW) and [SystemicConsensusAggregation.MEAN] (KW/n) always agree on the winner,
 * because every option is rated by the same `n` voters -- the mean is a strictly monotone
 * transform of the sum for a fixed `n`. The parameter exists purely so callers can request either
 * figure for display/cross-SystemicConsensus-comparison purposes ([SkOptionErgebnis] always reports
 * both); do not expect it to change [SkErgebnis.winnerOptionId].
 *
 * Tiebreak on equal KW: [SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE] -> lowest per-option
 * [SkOptionErgebnis.maxResistance] wins (the strongest minority-protection rule -- no single voter
 * was pushed to their maximum resistance); [SystemicConsensusTiebreakRule.LOWEST_STD_DEV] -> lowest
 * [SkOptionErgebnis.standardDeviation] wins (the option the group agrees on most uniformly);
 * [SystemicConsensusTiebreakRule.REPEAT] -> [SkErgebnis.tie] `true`, [SkErgebnis.winnerOptionId] `null`
 * (no winner, signals a repeat round). A final deterministic `optionId.toString()` ascending
 * fallback keeps [LOWEST_MAX_RESISTANCE]/[LOWEST_STD_DEV] well-defined even when the
 * tiebreak criterion itself is still tied.
 */
fun computeSystemicConsensusResult(
    ballots: List<SystemicConsensusBallotData>,
    optionIds: List<Uuid>,
    scaleMax: Int = 10,
    aggregation: SystemicConsensusAggregation = SystemicConsensusAggregation.MEAN,
    tiebreak: SystemicConsensusTiebreakRule = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
    groupConflictViableThreshold: Double = 0.2,
    groupConflictWarnThreshold: Double = 0.5,
): SkErgebnis {
    require(optionIds.isNotEmpty()) { "computeSystemicConsensusResult requires at least 1 option" }
    require(optionIds.size == optionIds.toSet().size) { "optionIds must not contain duplicates" }
    require(scaleMax >= 1) { "scaleMax must be >= 1, got $scaleMax" }
    require(
        groupConflictViableThreshold in 0.0..1.0,
    ) { "groupConflictViableThreshold must be in 0.0..1.0, got $groupConflictViableThreshold" }
    require(groupConflictWarnThreshold in 0.0..1.0) { "groupConflictWarnThreshold must be in 0.0..1.0, got $groupConflictWarnThreshold" }
    val optionIdSet = optionIds.toSet()
    ballots.forEach { ballot ->
        require(ballot.resistances.keys == optionIdSet) {
            "Every ballot must rate exactly the option set $optionIdSet once each, got ${ballot.resistances.keys}"
        }
        ballot.resistances.values.forEach { value ->
            require(value in 0..scaleMax) { "Resistance value must be in 0..$scaleMax, got $value" }
        }
    }
    // aggregation is intentionally unused -- see KDoc "is deliberately not used".

    val n = ballots.size
    val optionResults =
        optionIds.map { optionId ->
            val valuee = ballots.map { it.resistances.getValue(optionId) }
            val kw = valuee.sum()
            val mittel = if (n == 0) 0.0 else kw.toDouble() / n
            val maxWert = valuee.maxOrNull() ?: 0
            val variance = if (n == 0) 0.0 else valuee.sumOf { (it - mittel) * (it - mittel) } / n
            val consensusIndex = if (n == 0) 0.0 else kw.toDouble() / (n.toDouble() * scaleMax)
            SkOptionErgebnis(
                optionId = optionId,
                cumulativeResistance = kw,
                meanResistance = mittel,
                maxResistance = maxWert,
                standardDeviation = sqrt(variance),
                consensusIndex = consensusIndex,
                distribution = valuee.groupingBy { it }.eachCount(),
            )
        }

    if (n == 0) {
        return SkErgebnis(
            optionResults = optionResults,
            winnerOptionId = null,
            tie = true,
            tiebreakApplied = null,
            consensusViable = false,
            groupConflictWarning = false,
            noRatings = true,
        )
    }

    val sortedBestFirst =
        optionResults.sortedWith(compareBy<SkOptionErgebnis> { it.cumulativeResistance }.thenBy { it.optionId.toString() })
    val minKw = sortedBestFirst.first().cumulativeResistance
    val kwTied = sortedBestFirst.filter { it.cumulativeResistance == minKw }

    val tie: Boolean
    val tiebreakApplied: SystemicConsensusTiebreakRule?
    val gewinner: SkOptionErgebnis?

    if (kwTied.size == 1) {
        tie = false
        tiebreakApplied = null
        gewinner = kwTied.single()
    } else {
        // A genuine KW-level tie exists -- [tie] stays true regardless of whether a tiebreak
        // rule below still produces a concrete winner (see SkErgebnis KDoc: tie=true simply
        // means "the raw KW comparison alone did not distinguish a winner", not "no winner was
        // determined at all").
        tie = true
        when (tiebreak) {
            SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE -> {
                val minMax = kwTied.minOf { it.maxResistance }
                tiebreakApplied = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
                gewinner = kwTied.filter { it.maxResistance == minMax }.minBy { it.optionId.toString() }
            }
            SystemicConsensusTiebreakRule.LOWEST_STD_DEV -> {
                val minStdabw = kwTied.minOf { it.standardDeviation }
                tiebreakApplied = SystemicConsensusTiebreakRule.LOWEST_STD_DEV
                gewinner = kwTied.filter { it.standardDeviation == minStdabw }.minBy { it.optionId.toString() }
            }
            SystemicConsensusTiebreakRule.REPEAT -> {
                tiebreakApplied = null
                gewinner = null
            }
        }
    }

    val gewinnerKonsensIndex = gewinner?.consensusIndex
    return SkErgebnis(
        optionResults = sortedBestFirst,
        winnerOptionId = gewinner?.optionId,
        tie = tie,
        tiebreakApplied = tiebreakApplied,
        consensusViable = gewinnerKonsensIndex != null && gewinnerKonsensIndex < groupConflictViableThreshold,
        groupConflictWarning = gewinnerKonsensIndex != null && gewinnerKonsensIndex > groupConflictWarnThreshold,
        noRatings = false,
    )
}
