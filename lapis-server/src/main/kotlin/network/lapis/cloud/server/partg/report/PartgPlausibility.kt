package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgSponsorQualification
import network.lapis.cloud.shared.domain.PartgStatementRules
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import kotlin.math.abs

/**
 * V1.9.269 (R3) -- the plausibility assistant P01..P06. Pure: no database, no clock. A hint is a pointer, never a verdict, and it carries NEVER a
 * name, an address or an amount: only unit key, row numbers (1-based position in the sorted list of the unit), position code and gap code.
 *
 *  - P01 (CHECK): street, postal code and city of a row are all empty (one finding per unit, all rows).
 *  - P02 (CHECK): at least one, but not all, of the three is empty.
 *  - P03 (CHECK): deduction "cash over 1,000 EUR" is greater than zero.
 *  - P04 (INFO): a row is a legal person.
 *  - P05 (INFO): a LEAF position differs from the year before by more than 1,000.00 EUR AND more than 50 percent (integer arithmetic).
 *  - P06 (CHECK): a gap -- member count missing (federal always, state units that have any data), a deduction field missing (units that have an
 *    annex or values), or the accounting-principles note is not included (organisation).
 *
 *  - P07 (CHECK): a sponsor of statement C has street, postal code and city all empty (ALL sponsors, also those not in the report).
 *  - P08 (CHECK): a sponsor has at least one, but not all, of the three empty.
 *  - P09 (INFO): a sponsor is under both limits and therefore NOT in the report (so the board can delete the row; data minimisation).
 *  - P10 (CHECK): a youth organisation has neither income nor expenses entered.
 *    P07..P10 refer to the row number of the sorted list of the unit as the user interface shows it, and carry no name and no amount.
 *
 * At most [PartgAnnexRules.MAX_FINDINGS] findings: when the P05 hints would exceed it they are cut off and ONE P05 INFO without position marks
 * the cut.
 */
internal object PartgPlausibility {
    fun check(
        year: Int,
        units: List<PartgUnitDto>,
        entered: Map<Int, Map<String, Map<String, Long>>>,
        annex: Map<Int, Map<String, PartgAnnexStore.AnnexData>>,
        notes: Map<Int, PartgAnnexStore.NotesData>,
    ): List<PartgFindingDto> {
        val order = units.withIndex().associate { it.value.unitKey to it.index }
        val base = mutableListOf<PartgFindingDto>()
        val p05 = mutableListOf<PartgFindingDto>()
        units.forEach { unit ->
            val data = annex[year]?.get(unit.unitKey)
            val values = entered[year]?.get(unit.unitKey).orEmpty()
            if (data != null) {
                addDonorFindings(unitKey = unit.unitKey, data = data, out = base)
                addCash(unitKey = unit.unitKey, data = data, out = base)
                addSponsorFindings(unitKey = unit.unitKey, data = data, out = base)
                addYouthFindings(unitKey = unit.unitKey, data = data, out = base)
            }
            if (data != null || values.isNotEmpty()) addGapFindings(unit = unit, data = data, out = base)
            if (unit.level == PartgUnitLevel.FEDERAL && data == null) addMemberGap(unitKey = unit.unitKey, out = base)
            addVariance(unitKey = unit.unitKey, current = values, prior = entered[year - 1]?.get(unit.unitKey).orEmpty(), out = p05)
        }
        val generalIncluded = notes[year]?.notes?.firstOrNull { it.block == PartgNoteBlock.GENERAL_ACCOUNTING }?.included == true
        if (!generalIncluded) {
            base +=
                PartgFindingDto(
                    code = PartgFindingCode.P06,
                    level = PartgFindingLevel.CHECK,
                    unitKey = null,
                    gap = GAP_GENERAL_ACCOUNTING,
                    target = PartgFindingTarget.NOTES,
                )
        }
        val room = (PartgAnnexRules.MAX_FINDINGS - base.size).coerceAtLeast(0)
        val all =
            if (p05.size <= room) {
                base + p05
            } else {
                base + p05.take((room - 1).coerceAtLeast(0)) +
                    PartgFindingDto(
                        code = PartgFindingCode.P05,
                        level = PartgFindingLevel.INFO,
                        unitKey = null,
                        target = PartgFindingTarget.SHEET,
                    )
            }
        return all
            .distinct()
            .sortedWith(
                compareBy<PartgFindingDto> { f -> f.unitKey?.let { order[it] ?: Int.MAX_VALUE - 1 } ?: Int.MAX_VALUE }
                    .thenBy { it.code.ordinal }
                    .thenBy { it.rowNumbers.firstOrNull() ?: 0 }
                    .thenBy { it.positionCode.orEmpty() }
                    .thenBy { it.gap.orEmpty() },
            ).take(PartgAnnexRules.MAX_FINDINGS)
    }

    private fun addDonorFindings(
        unitKey: String,
        data: PartgAnnexStore.AnnexData,
        out: MutableList<PartgFindingDto>,
    ) {
        val numbered = data.donors.withIndex().map { (index, donor) -> (index + 1) to donor }

        fun empty(s: String?) = s.isNullOrBlank()
        val noAddress = numbered.filter { (_, d) -> empty(d.street) && empty(d.postalCode) && empty(d.city) }.map { it.first }
        val partial =
            numbered
                .filter { (_, d) ->
                    val missing = listOf(d.street, d.postalCode, d.city).count { empty(it) }
                    missing in 1..2
                }.map { it.first }
        val legal = numbered.filter { (_, d) -> d.personKind == PartgPersonKind.LEGAL }.map { it.first }
        if (noAddress.isNotEmpty()) {
            out +=
                finding(code = PartgFindingCode.P01, level = PartgFindingLevel.CHECK, unitKey = unitKey, rows = noAddress)
        }
        if (partial.isNotEmpty()) {
            out +=
                finding(code = PartgFindingCode.P02, level = PartgFindingLevel.CHECK, unitKey = unitKey, rows = partial)
        }
        if (legal.isNotEmpty()) out += finding(code = PartgFindingCode.P04, level = PartgFindingLevel.INFO, unitKey = unitKey, rows = legal)
    }

    private fun addSponsorFindings(
        unitKey: String,
        data: PartgAnnexStore.AnnexData,
        out: MutableList<PartgFindingDto>,
    ) {
        val numbered = data.sponsors.withIndex().map { (index, sponsor) -> (index + 1) to sponsor }

        fun empty(s: String?) = s.isNullOrBlank()
        val noAddress = numbered.filter { (_, d) -> empty(d.street) && empty(d.postalCode) && empty(d.city) }.map { it.first }
        val partial =
            numbered
                .filter { (_, d) -> listOf(d.street, d.postalCode, d.city).count { empty(it) } in 1..2 }
                .map { it.first }
        val notInReport =
            numbered
                .filter { (_, d) ->
                    PartgStatementRules.qualification(d.entries.map { it.amountCents }) == PartgSponsorQualification.NONE
                }.map { it.first }
        if (noAddress.isNotEmpty()) {
            out +=
                finding(code = PartgFindingCode.P07, level = PartgFindingLevel.CHECK, unitKey = unitKey, rows = noAddress)
        }
        if (partial.isNotEmpty()) {
            out +=
                finding(code = PartgFindingCode.P08, level = PartgFindingLevel.CHECK, unitKey = unitKey, rows = partial)
        }
        if (notInReport.isNotEmpty()) {
            out +=
                finding(code = PartgFindingCode.P09, level = PartgFindingLevel.INFO, unitKey = unitKey, rows = notInReport)
        }
    }

    private fun addYouthFindings(
        unitKey: String,
        data: PartgAnnexStore.AnnexData,
        out: MutableList<PartgFindingDto>,
    ) {
        val noFigures =
            data.youthOrgs
                .withIndex()
                .filter { (_, y) -> y.incomeCents == null && y.expensesCents == null }
                .map { it.index + 1 }
        if (noFigures.isNotEmpty()) {
            out +=
                finding(code = PartgFindingCode.P10, level = PartgFindingLevel.CHECK, unitKey = unitKey, rows = noFigures)
        }
    }

    private fun finding(
        code: PartgFindingCode,
        level: PartgFindingLevel,
        unitKey: String,
        rows: List<Int>,
    ) = PartgFindingDto(code = code, level = level, unitKey = unitKey, rowNumbers = rows, target = PartgFindingTarget.ANNEX)

    private fun addCash(
        unitKey: String,
        data: PartgAnnexStore.AnnexData,
        out: MutableList<PartgFindingDto>,
    ) {
        val cash = data.deductions[PartgDeduction.CASH_OVER_1000]
        if (cash != null &&
            cash > 0L
        ) {
            out += finding(code = PartgFindingCode.P03, level = PartgFindingLevel.CHECK, unitKey = unitKey, rows = emptyList())
        }
    }

    private fun addGapFindings(
        unit: PartgUnitDto,
        data: PartgAnnexStore.AnnexData?,
        out: MutableList<PartgFindingDto>,
    ) {
        if (unit.level != PartgUnitLevel.SUBORDINATE && data?.memberCount == null) addMemberGap(unitKey = unit.unitKey, out = out)
        if (data == null || PartgDeduction.entries.any { data.deductions[it] == null }) {
            out +=
                PartgFindingDto(
                    code = PartgFindingCode.P06,
                    level = PartgFindingLevel.CHECK,
                    unitKey = unit.unitKey,
                    gap = GAP_DEDUCTIONS,
                    target = PartgFindingTarget.ANNEX,
                )
        }
    }

    private fun addMemberGap(
        unitKey: String,
        out: MutableList<PartgFindingDto>,
    ) {
        if (PartgUnit.parse(unitKey) is PartgUnit.Subordinate) return
        out +=
            PartgFindingDto(
                code = PartgFindingCode.P06,
                level = PartgFindingLevel.CHECK,
                unitKey = unitKey,
                gap = GAP_MEMBER_COUNT,
                target = PartgFindingTarget.ANNEX,
            )
    }

    private fun addVariance(
        unitKey: String,
        current: Map<String, Long>,
        prior: Map<String, Long>,
        out: MutableList<PartgFindingDto>,
    ) {
        PartgPositions.LEAF_CODES.forEach { code ->
            val cur = current[code] ?: return@forEach
            val old = prior[code] ?: return@forEach
            val diff = abs(cur - old)
            // "more than 50 percent": diff * 100 > prior * 50 in whole cents; prior 0 counts as exceeded (diff > 0 is implied by the 1,000 EUR rule)
            val ratio = old == 0L || diff * 100 > old * PartgAnnexRules.P05_RATIO_PERCENT
            if (diff > PartgAnnexRules.P05_ABS_CENTS && ratio) {
                out +=
                    PartgFindingDto(
                        code = PartgFindingCode.P05,
                        level = PartgFindingLevel.INFO,
                        unitKey = unitKey,
                        positionCode = code,
                        target = PartgFindingTarget.SHEET,
                    )
            }
        }
    }

    const val GAP_MEMBER_COUNT = "MEMBER_COUNT"
    const val GAP_DEDUCTIONS = "DEDUCTIONS"
    const val GAP_GENERAL_ACCOUNTING = "GENERAL_ACCOUNTING"
}
