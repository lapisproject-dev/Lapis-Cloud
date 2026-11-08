package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgConsolidationStore
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgConsolidatedRowDto
import network.lapis.cloud.shared.domain.PartgConsolidationRules
import network.lapis.cloud.shared.domain.PartgExcludedUnitDto
import network.lapis.cloud.shared.domain.PartgExclusionReason
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import network.lapis.cloud.shared.domain.PartgIntraTransferDto
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgNotComputableReason
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportComputation
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnmatchedDto
import kotlin.math.abs

/** The findings P17..P21 of the consolidation, computed by [PartgConsolidation.findings] and handed to the plausibility check. */
internal class PartgConsolidationFindingsInput(
    val findings: List<PartgFindingDto>,
)

/** The result of the consolidation of one year (and the year before) -- everything the screen and the exports show, without the donors. */
internal class ConsolidationResult(
    val included: List<String>,
    val excluded: List<PartgExcludedUnitDto>,
    val priorExcluded: List<PartgExcludedUnitDto>,
    val rows: List<PartgConsolidatedRowDto>,
    val priorTransfersUnmatched: Boolean,
    val transfers: List<PartgIntraTransferDto>,
    val unmatched: List<PartgUnmatchedDto>,
)

/**
 * V1.9.290 (R5) -- the consolidation of the whole party over its units. Pure: no table, no clock.
 *
 *  - A unit is INCLUDED when it has at least one stored value (any source) in the year. Every other unit is EXCLUDED (reason NO_DATA, or
 *    BOOKINGS_NOT_TAKEN_OVER when it books in Lapis and has bookings that were never taken over); an excluded unit is NEVER counted as 0.
 *  - A leaf is a value only when EVERY included unit has it; otherwise it is "not computable" with the units that lack it. A sum is a value only
 *    when all its inputs are (the inputs come from [PartgReportComputation.inputsOf], the formula from [PartgPositions.sums]).
 *  - Elimination per pair is the MINIMUM of the two stated amounts, only when both units are included and both amounts are stated; it is taken
 *    from BOTH positions (A03 and E10, or BP05 and SP04). It is NEVER capped: when the elimination of a position is larger than its sum the
 *    consolidated cell is "not computable". A difference between the two sides stays in the list `unmatched`.
 */
internal object PartgConsolidation {
    private val leafOrder: List<String> = PartgPositions.ALL.filter { it.kind == PartgKind.LEAF }.map { it.code }
    private val transferPositions: Set<String> =
        PartgTransferKind.entries
            .flatMap {
                listOf(
                    PartgConsolidationRules.payerPosition(it),
                    PartgConsolidationRules.payeePosition(it),
                )
            }.toSet()

    /** One year computed: unit-sum cells, eliminated amounts and consolidated cells per code, plus the per-unit eliminated shares. */
    private class YearResult(
        val included: List<String>,
        val unitSum: Map<String, PartgConsolidatedCell>,
        val eliminated: Map<String, Long>,
        val consolidated: Map<String, PartgConsolidatedCell>,
        val eliminatedByUnitPosition: Map<Pair<String, String>, Long>,
    )

    fun includedUnits(
        units: List<PartgUnitDto>,
        values: Map<String, Map<String, Long>>,
    ): List<String> = units.map { it.unitKey }.filter { values[it].orEmpty().isNotEmpty() }

    fun excludedUnits(
        units: List<PartgUnitDto>,
        values: Map<String, Map<String, Long>>,
        takeOverPending: Set<String>,
    ): List<PartgExcludedUnitDto> =
        units
            .map { it.unitKey }
            .filter { values[it].orEmpty().isEmpty() }
            .map {
                PartgExcludedUnitDto(
                    unitKey = it,
                    reason = if (it in takeOverPending) PartgExclusionReason.BOOKINGS_NOT_TAKEN_OVER else PartgExclusionReason.NO_DATA,
                )
            }

    private fun yearResult(
        units: List<PartgUnitDto>,
        values: Map<String, Map<String, Long>>,
        transfers: List<PartgConsolidationStore.TransferRow>,
    ): YearResult {
        val included = includedUnits(units = units, values = values)
        val includedSet = included.toSet()
        val byUnitPosition = HashMap<Pair<String, String>, Long>()
        val eliminatedLeaf = HashMap<String, Long>()
        transfers.forEach { pair ->
            if (pair.payerUnitKey !in includedSet || pair.payeeUnitKey !in includedSet) return@forEach
            val amount = PartgConsolidationRules.eliminable(payer = pair.payerAmountCents, payee = pair.payeeAmountCents) ?: return@forEach
            val payerCode = PartgConsolidationRules.payerPosition(pair.kind)
            val payeeCode = PartgConsolidationRules.payeePosition(pair.kind)
            byUnitPosition.merge(pair.payerUnitKey to payerCode, amount, Long::plus)
            byUnitPosition.merge(pair.payeeUnitKey to payeeCode, amount, Long::plus)
            eliminatedLeaf.merge(payerCode, amount, Long::plus)
            eliminatedLeaf.merge(payeeCode, amount, Long::plus)
        }
        val unitSumLeaf = HashMap<String, PartgConsolidatedCell>()
        val consolidatedLeaf = HashMap<String, PartgConsolidatedCell>()
        leafOrder.forEach { code ->
            val missing = included.filter { values[it]?.containsKey(code) != true }
            val sumCell: PartgConsolidatedCell =
                if (included.isEmpty() || missing.isNotEmpty()) {
                    PartgConsolidatedCell.NotComputable(unitKeys = missing, reason = PartgNotComputableReason.MISSING_IN_UNITS)
                } else {
                    PartgConsolidatedCell.Value(included.sumOf { values.getValue(it).getValue(code) })
                }
            unitSumLeaf[code] = sumCell
            val eliminated = eliminatedLeaf[code] ?: 0L
            consolidatedLeaf[code] =
                when {
                    sumCell !is PartgConsolidatedCell.Value -> sumCell
                    sumCell.cents - eliminated < 0L ->
                        PartgConsolidatedCell.NotComputable(
                            unitKeys =
                                byUnitPosition.keys
                                    .filter { it.second == code }
                                    .map { it.first }
                                    .distinct()
                                    .sorted(),
                            reason = PartgNotComputableReason.ELIMINATION_EXCEEDS,
                        )
                    else -> PartgConsolidatedCell.Value(sumCell.cents - eliminated)
                }
        }
        val eliminatedAll = PartgPositions.sums(eliminatedLeaf) + leafOrder.associateWith { eliminatedLeaf[it] ?: 0L }
        return YearResult(
            included = included,
            unitSum = withSums(unitSumLeaf),
            eliminated = PartgPositions.ALL.associate { it.code to (eliminatedAll[it.code] ?: 0L) },
            consolidated = withSums(consolidatedLeaf),
            eliminatedByUnitPosition = byUnitPosition,
        )
    }

    /** Adds the seven sums to the leaf cells: a value when all inputs are values, else "not computable" with the units and reason of the inputs. */
    private fun withSums(leaf: Map<String, PartgConsolidatedCell>): Map<String, PartgConsolidatedCell> {
        val result = LinkedHashMap<String, PartgConsolidatedCell>()
        val leafValues = leaf.mapNotNull { (code, cell) -> (cell as? PartgConsolidatedCell.Value)?.let { code to it.cents } }.toMap()
        val computed = PartgPositions.sums(leafValues)
        PartgPositions.ALL.forEach { position ->
            if (position.kind == PartgKind.LEAF) {
                result[position.code] = leaf.getValue(position.code)
            } else {
                val inputs = PartgReportComputation.inputsOf(position.code)
                val bad = inputs.mapNotNull { leaf[it] as? PartgConsolidatedCell.NotComputable }
                result[position.code] =
                    if (bad.isEmpty()) {
                        PartgConsolidatedCell.Value(computed.getValue(position.code))
                    } else {
                        PartgConsolidatedCell.NotComputable(
                            unitKeys = bad.flatMap { it.unitKeys }.distinct().sorted(),
                            reason =
                                if (bad.any { it.reason == PartgNotComputableReason.MISSING_IN_UNITS }) {
                                    PartgNotComputableReason.MISSING_IN_UNITS
                                } else {
                                    PartgNotComputableReason.ELIMINATION_EXCEEDS
                                },
                        )
                    }
            }
        }
        return result
    }

    fun compute(
        year: Int,
        units: List<PartgUnitDto>,
        entered: Map<Int, Map<String, Map<String, Long>>>,
        consolidation: Map<Int, PartgConsolidationStore.ConsolidationData>,
        takeOverPending: Map<Int, Set<String>> = emptyMap(),
    ): ConsolidationResult {
        val values = entered[year].orEmpty()
        val priorValues = entered[year - 1].orEmpty()
        val transfers = consolidation[year]?.transfers.orEmpty()
        val priorTransfers = consolidation[year - 1]?.transfers.orEmpty()
        val current = yearResult(units = units, values = values, transfers = transfers)
        val prior = yearResult(units = units, values = priorValues, transfers = priorTransfers)
        val rows =
            PartgPositions.ALL.map { position ->
                PartgConsolidatedRowDto(
                    code = position.code,
                    unitSum = current.unitSum.getValue(position.code),
                    eliminatedCents = current.eliminated.getValue(position.code),
                    consolidated = current.consolidated.getValue(position.code),
                    priorConsolidated = prior.consolidated.getValue(position.code),
                )
            }
        val priorUnmatched =
            priorTransfers.isEmpty() &&
                prior.included.any { unit -> transferPositions.any { (priorValues[unit]?.get(it) ?: 0L) > 0L } }
        val includedSet = current.included.toSet()
        val transferDtos =
            transfers.map {
                PartgIntraTransferDto(
                    id = it.id.toString(),
                    kind = it.kind,
                    payerUnitKey = it.payerUnitKey,
                    payeeUnitKey = it.payeeUnitKey,
                    payerAmountCents = it.payerAmountCents,
                    payeeAmountCents = it.payeeAmountCents,
                    note = it.note,
                    match = PartgConsolidationRules.matchState(payer = it.payerAmountCents, payee = it.payeeAmountCents),
                )
            }
        val unmatched = mutableListOf<PartgUnmatchedDto>()
        units.map { it.unitKey }.filter { it in includedSet }.forEach { unit ->
            leafOrder.filter { it in transferPositions }.forEach { code ->
                val entered1 = values[unit]?.get(code) ?: return@forEach
                val difference = entered1 - (current.eliminatedByUnitPosition[unit to code] ?: 0L)
                if (difference != 0L) unmatched += PartgUnmatchedDto(unitKey = unit, positionCode = code, differenceCents = difference)
            }
        }
        return ConsolidationResult(
            included = current.included,
            excluded = excludedUnits(units = units, values = values, takeOverPending = takeOverPending[year].orEmpty()),
            priorExcluded = excludedUnits(units = units, values = priorValues, takeOverPending = takeOverPending[year - 1].orEmpty()),
            rows = rows,
            priorTransfersUnmatched = priorUnmatched,
            transfers = transferDtos,
            unmatched = unmatched,
        )
    }

    /**
     * P17 (CHECK) the sum of the pairs of a unit, side and kind differs from A03/E10/BP05/SP04 by STRICTLY more than 100 cents, or the position is
     * not entered; P18 (CHECK) a pair has only one amount or names a unit without data (count per payer unit); P19 (INFO) a position is above zero
     * but the unit has no pair on that side; P20 (INFO) combination candidates exist (count); P21 (CHECK) a donor combination has fewer than two
     * rows left or a row changed after the combination was made (count). All carry target CONSOLIDATION and NEVER a name or an amount.
     */
    fun findings(
        year: Int,
        units: List<PartgUnitDto>,
        entered: Map<Int, Map<String, Map<String, Long>>>,
        consolidation: Map<Int, PartgConsolidationStore.ConsolidationData>,
        donors: DonorConsolidation,
    ): PartgConsolidationFindingsInput {
        val values = entered[year].orEmpty()
        val transfers = consolidation[year]?.transfers.orEmpty()
        val includedSet = includedUnits(units = units, values = values).toSet()
        val out = mutableListOf<PartgFindingDto>()

        fun finding(
            code: PartgFindingCode,
            level: PartgFindingLevel,
            unitKey: String?,
            positionCode: String? = null,
            count: Int? = null,
        ) = PartgFindingDto(
            code = code,
            level = level,
            unitKey = unitKey,
            positionCode = positionCode,
            target = PartgFindingTarget.CONSOLIDATION,
            count = count,
        )
        // side sums of the stated amounts per (unit, position)
        val pairSums = HashMap<Pair<String, String>, Long>()
        val pairCounts = HashMap<Pair<String, String>, Int>()
        transfers.forEach { pair ->
            pair.payerAmountCents?.let {
                val key = pair.payerUnitKey to PartgConsolidationRules.payerPosition(pair.kind)
                pairSums.merge(key, it, Long::plus)
            }
            pair.payeeAmountCents?.let {
                val key = pair.payeeUnitKey to PartgConsolidationRules.payeePosition(pair.kind)
                pairSums.merge(key, it, Long::plus)
            }
            pairCounts.merge(pair.payerUnitKey to PartgConsolidationRules.payerPosition(pair.kind), 1, Int::plus)
            pairCounts.merge(pair.payeeUnitKey to PartgConsolidationRules.payeePosition(pair.kind), 1, Int::plus)
        }
        units.map { it.unitKey }.forEach { unit ->
            transferPositions.sorted().forEach { code ->
                val sum = pairSums[unit to code]
                val value = values[unit]?.get(code)
                if (sum != null) {
                    if (value == null || abs(value - sum) > PartgConsolidationRules.P17_TOLERANCE_CENTS) {
                        out += finding(PartgFindingCode.P17, PartgFindingLevel.CHECK, unit, positionCode = code)
                    }
                } else if (unit in includedSet && (value ?: 0L) > 0L && (pairCounts[unit to code] ?: 0) == 0) {
                    out += finding(PartgFindingCode.P19, PartgFindingLevel.INFO, unit, positionCode = code)
                }
            }
        }
        transfers
            .filter {
                it.payerAmountCents == null ||
                    it.payeeAmountCents == null ||
                    it.payerUnitKey !in includedSet ||
                    it.payeeUnitKey !in includedSet
            }.groupingBy { it.payerUnitKey }
            .eachCount()
            .forEach { (unit, count) -> out += finding(PartgFindingCode.P18, PartgFindingLevel.CHECK, unit, count = count) }
        if (donors.candidateTotal > 0) {
            out += finding(PartgFindingCode.P20, PartgFindingLevel.INFO, null, count = donors.candidateTotal)
        }
        if (donors.brokenCombinations > 0) {
            out += finding(PartgFindingCode.P21, PartgFindingLevel.CHECK, null, count = donors.brokenCombinations)
        }
        return PartgConsolidationFindingsInput(out)
    }
}
