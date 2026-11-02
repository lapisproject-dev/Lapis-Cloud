package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgAmountError
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.parseGermanAmount

/** The two columns of the sheet editor. */
internal enum class PartgColumn { PRIOR, CURRENT }

/** German input text of an amount in cents without the currency: `123456` -> `1.234,56`. */
internal fun partgInputText(cents: Long): String {
    val euros = cents / 100
    val rest = (cents % 100).toString().padStart(2, '0')
    val grouped =
        euros
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(".")
            .reversed()
    return "$grouped,$rest"
}

/** Why a typed-in cell is refused (shown as a fixed text under the field). */
internal fun partgAmountErrorText(reason: PartgAmountError): String =
    when (reason) {
        PartgAmountError.NEGATIVE -> io.kvision.i18n.tr("Bitte keinen negativen Betrag eingeben.")
        PartgAmountError.TOO_MANY_DECIMALS -> io.kvision.i18n.tr("Höchstens zwei Nachkommastellen.")
        PartgAmountError.OVERFLOW -> io.kvision.i18n.tr("Der Betrag ist zu groß.")
        else -> io.kvision.i18n.tr("Bitte einen Betrag wie 1.234,56 eingeben.")
    }

/**
 * The editing state of one sheet (pure, DOM-free): the saved values of both years and the drafts typed in since. A draft is the raw input text;
 * a blank draft means "not entered". [changes] turns the drafts into the change list of `saveSheet` (only what differs from the saved state).
 */
internal class PartgSheetDraft(
    val year: Int,
    saved: Map<String, Long>,
    savedPrior: Map<String, Long>,
) {
    private val savedBy = mapOf(PartgColumn.CURRENT to saved, PartgColumn.PRIOR to savedPrior)
    private val drafts = LinkedHashMap<Pair<PartgColumn, String>, String>()

    fun saved(
        column: PartgColumn,
        code: String,
    ): Long? = savedBy.getValue(column)[code]

    /** The text the field shows: the draft if there is one, else the saved value, else empty. */
    fun text(
        column: PartgColumn,
        code: String,
    ): String = drafts[column to code] ?: saved(column, code)?.let(::partgInputText).orEmpty()

    fun edit(
        column: PartgColumn,
        code: String,
        text: String,
    ) {
        require(code in PartgPositions.LEAF_CODES) { "only typed-in positions can be edited" }
        val original = saved(column, code)?.let(::partgInputText).orEmpty()
        if (text.trim() == original) drafts.remove(column to code) else drafts[column to code] = text
    }

    /** `Ok(cents)` / `Error` for the cell's current text; a blank text is `Ok(null)` ("not entered"). */
    fun parsed(
        column: PartgColumn,
        code: String,
    ): PartgCellValue {
        val text = text(column, code)
        if (text.isBlank()) return PartgCellValue.Empty
        return when (val result = parseGermanAmount(text)) {
            is AmountParse.Ok -> PartgCellValue.Amount(result.cents)
            is AmountParse.Error -> PartgCellValue.Invalid(result.reason)
        }
    }

    /** Values of a column as the live sums see them (invalid cells count as not entered). */
    fun valuesFor(column: PartgColumn): Map<String, Long> =
        PartgPositions.LEAF_CODES
            .mapNotNull { code -> (parsed(column, code) as? PartgCellValue.Amount)?.let { code to it.cents } }
            .toMap()

    val hasInvalid: Boolean
        get() = drafts.keys.any { (column, code) -> parsed(column, code) is PartgCellValue.Invalid }

    /** The effective change list (amount `null` deletes), sorted for a stable payload. */
    fun changes(): List<PartgValueChangeDto> =
        drafts.keys
            .sortedWith(compareBy({ it.first.ordinal }, { it.second }))
            .mapNotNull { (column, code) ->
                val cents = (parsed(column, code) as? PartgCellValue.Amount)?.cents
                val yearOf = if (column == PartgColumn.CURRENT) year else year - 1
                if (cents == saved(column, code)) null else PartgValueChangeDto(year = yearOf, positionCode = code, amountCents = cents)
            }

    val changeCount: Int get() = changes().size

    fun discard() = drafts.clear()

    /** Fills every empty field of the report year with 0 (never touches the prior year, never an invalid or filled field). */
    fun fillEmptyWithZero() {
        PartgPositions.LEAF_CODES.forEach { code ->
            if (text(PartgColumn.CURRENT, code).isBlank()) edit(PartgColumn.CURRENT, code, "0,00")
        }
    }
}

internal sealed interface PartgCellValue {
    data object Empty : PartgCellValue

    data class Amount(
        val cents: Long,
    ) : PartgCellValue

    data class Invalid(
        val reason: PartgAmountError,
    ) : PartgCellValue
}

/** `n von 35`, the badge of the overview: "Vollständig", "Teilweise (n von N)" or "Nicht erfasst". */
internal enum class PartgFillState { EMPTY, PARTIAL, COMPLETE }

internal fun partgFillState(
    filled: Int,
    total: Int,
): PartgFillState =
    when {
        filled <= 0 -> PartgFillState.EMPTY
        filled >= total -> PartgFillState.COMPLETE
        else -> PartgFillState.PARTIAL
    }

/** What the checklist of the report export says about one unit (V1.9.256). */
internal enum class PartgChecklistKind { MISSING, PRIOR_NONE, PRIOR_PARTIAL, COMPLETE_SUMMARY }

/**
 * One line of the checklist. [MISSING]: [missing] holds the not entered LEAF codes of the report year. [PRIOR_NONE] / [PRIOR_PARTIAL]: the year
 * before (neutral, never a warning); [count] is then the number of missing positions of the year before. [COMPLETE_SUMMARY] has no [unit];
 * [count] is the number of units that are complete for the report year.
 */
internal data class PartgChecklistLine(
    val kind: PartgChecklistKind,
    val unit: PartgUnitDto?,
    val missing: List<String>,
    val count: Int,
    val leafCount: Int,
)

/**
 * The checklist of [dto] for [scopeKey] (`ALL` or a unit key): per unit the missing positions and the state of the year before, then ONE line
 * for all units that are complete. Pure and DOM-free.
 */
internal fun completenessLines(
    dto: PartgCompletenessDto,
    scopeKey: String,
): List<PartgChecklistLine> {
    val units = if (scopeKey == PartgReportScope.ALL_KEY) dto.units else dto.units.filter { it.unit.unitKey == scopeKey }
    val lines = mutableListOf<PartgChecklistLine>()
    var complete = 0
    for (entry in units) {
        if (entry.missingCurrent.isEmpty()) {
            complete += 1
        } else {
            lines +=
                PartgChecklistLine(PartgChecklistKind.MISSING, entry.unit, entry.missingCurrent, entry.missingCurrent.size, dto.leafCount)
        }
        if (entry.enteredPrior == 0) {
            lines += PartgChecklistLine(PartgChecklistKind.PRIOR_NONE, entry.unit, emptyList(), dto.leafCount, dto.leafCount)
        } else if (entry.enteredPrior < dto.leafCount) {
            lines +=
                PartgChecklistLine(
                    PartgChecklistKind.PRIOR_PARTIAL,
                    entry.unit,
                    emptyList(),
                    dto.leafCount - entry.enteredPrior,
                    dto.leafCount,
                )
        }
    }
    if (complete > 0) lines += PartgChecklistLine(PartgChecklistKind.COMPLETE_SUMMARY, null, emptyList(), complete, dto.leafCount)
    return lines
}
