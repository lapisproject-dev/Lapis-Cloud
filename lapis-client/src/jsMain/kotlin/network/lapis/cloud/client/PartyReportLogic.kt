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

// ---------------------------------------------------------------- V1.9.269 (R3): separate statements, notes, plausibility hints

/** "Zeile 3" / "Zeilen 1, 3 und 7": the row numbers of a hint, pure and DOM-free. */
internal fun partgRowNumbersText(rows: List<Int>): String =
    when (rows.size) {
        0 -> ""
        1 -> io.kvision.i18n.gettext("Zeile %1", rows.single())
        else -> io.kvision.i18n.gettext("Zeilen %1 und %2", rows.dropLast(1).joinToString(", "), rows.last())
    }

/** `15.06.2030` for the date of a count (`asOf`); the date is formatted from its ISO text, no locale involved. */
internal fun partgGermanDate(date: kotlinx.datetime.LocalDate): String {
    val (year, month, day) = date.toString().split("-")
    return "$day.$month.$year"
}

/** The short reason of a plausibility hint. Never contains a name, an address or an amount: the DTO carries none. */
internal fun partgFindingShortLabel(finding: network.lapis.cloud.shared.domain.PartgFindingDto): String =
    when (finding.code) {
        network.lapis.cloud.shared.domain.PartgFindingCode.P01 -> io.kvision.i18n.gettext("Anschrift fehlt")
        network.lapis.cloud.shared.domain.PartgFindingCode.P02 -> io.kvision.i18n.gettext("Anschrift unvollständig")
        network.lapis.cloud.shared.domain.PartgFindingCode.P03 -> io.kvision.i18n.gettext("Bar-Spenden über 1.000 € erfasst")
        network.lapis.cloud.shared.domain.PartgFindingCode.P04 -> io.kvision.i18n.gettext("Juristische Person")
        network.lapis.cloud.shared.domain.PartgFindingCode.P05 ->
            finding.positionCode?.let { io.kvision.i18n.gettext("Position %1 weicht deutlich vom Vorjahr ab", partgNumberLabelOf(it)) }
                ?: io.kvision.i18n.gettext("Weitere deutliche Abweichungen zum Vorjahr werden nicht einzeln aufgeführt")
        network.lapis.cloud.shared.domain.PartgFindingCode.P06 ->
            when (finding.gap) {
                "MEMBER_COUNT" -> io.kvision.i18n.gettext("Mitgliederzahl fehlt")
                "DEDUCTIONS" -> io.kvision.i18n.gettext("Mindestens ein Abzug fehlt")
                else -> io.kvision.i18n.gettext("Die allgemeinen Angaben zur Rechnungslegung fehlen")
            }
        network.lapis.cloud.shared.domain.PartgFindingCode.P07 -> io.kvision.i18n.gettext("Anschrift des Sponsors fehlt")
        network.lapis.cloud.shared.domain.PartgFindingCode.P08 -> io.kvision.i18n.gettext("Anschrift des Sponsors unvollständig")
        network.lapis.cloud.shared.domain.PartgFindingCode.P09 -> io.kvision.i18n.gettext("Sponsor unter beiden Grenzen, nicht im Bericht")
        network.lapis.cloud.shared.domain.PartgFindingCode.P10 -> io.kvision.i18n.gettext("Jugendorganisation ohne Zahlen")
        network.lapis.cloud.shared.domain.PartgFindingCode.P11 ->
            finding.positionCode?.let { io.kvision.i18n.gettext("Unternehmen weichen von Position %1 ab", partgNumberLabelOf(it)) }
                ?: io.kvision.i18n.gettext("Unternehmen weichen von der Position ab")
        network.lapis.cloud.shared.domain.PartgFindingCode.P12 -> io.kvision.i18n.gettext("Unternehmen ohne vollständige Zahlen")
        network.lapis.cloud.shared.domain.PartgFindingCode.P13 ->
            finding.positionCode?.let {
                io.kvision.i18n.gettext("Position %1 erfasst, aber kein Unternehmen in Ausweis D", partgNumberLabelOf(it))
            } ?: io.kvision.i18n.gettext("Position erfasst, aber kein Unternehmen in Ausweis D")
        network.lapis.cloud.shared.domain.PartgFindingCode.P14 ->
            io.kvision.i18n.gettext("%1 Konten mit Buchungen sind keiner Position zugeordnet", (finding.count ?: 0).toString())
        network.lapis.cloud.shared.domain.PartgFindingCode.P15 ->
            io.kvision.i18n.gettext("%1 Buchungen vorhanden, die Buchführung steht auf Modus C", (finding.count ?: 0).toString())
        network.lapis.cloud.shared.domain.PartgFindingCode.P16 ->
            finding.positionCode?.let {
                io.kvision.i18n.gettext("Position %1 weicht vom aktuellen Vorschlag aus den Buchungen ab", partgNumberLabelOf(it))
            }
                ?: io.kvision.i18n.gettext("Ein Wert aus Buchungen weicht vom aktuellen Vorschlag ab")
        network.lapis.cloud.shared.domain.PartgFindingCode.P17 ->
            finding.positionCode?.let {
                io.kvision.i18n.gettext(
                    "Die Übertragungen weichen vom Wert der Position %1 ab oder die Position fehlt",
                    partgNumberLabelOf(it),
                )
            } ?: io.kvision.i18n.gettext("Die Übertragungen weichen vom Wert einer Position ab")
        network.lapis.cloud.shared.domain.PartgFindingCode.P18 ->
            io.kvision.i18n.gettext(
                "%1 Übertragungen sind einseitig oder nennen eine Gliederung ohne Daten",
                (finding.count ?: 0).toString(),
            )
        network.lapis.cloud.shared.domain.PartgFindingCode.P19 ->
            finding.positionCode?.let {
                io.kvision.i18n.gettext("Position %1 ist erfasst, aber es ist keine Übertragung eingetragen", partgNumberLabelOf(it))
            } ?: io.kvision.i18n.gettext("Eine Position ist erfasst, aber es ist keine Übertragung eingetragen")
        network.lapis.cloud.shared.domain.PartgFindingCode.P20 ->
            io.kvision.i18n.gettext("%1 mögliche Zusammenführungen von Großspendern", (finding.count ?: 0).toString())
        network.lapis.cloud.shared.domain.PartgFindingCode.P21 ->
            io.kvision.i18n.gettext("%1 Zusammenführungen von Großspendern prüfen", (finding.count ?: 0).toString())
    }

private fun partgNumberLabelOf(code: String): String = PartgPositions.find(code)?.numberLabel ?: code

/** The full line of a hint: the unit, the rows and the reason. */
internal fun partgFindingLine(
    finding: network.lapis.cloud.shared.domain.PartgFindingDto,
    unitName: String?,
): String {
    val rows = partgRowNumbersText(finding.rowNumbers)
    val reason = partgFindingShortLabel(finding)
    val head = listOfNotNull(unitName, rows.ifEmpty { null }).joinToString(", ")
    return if (head.isEmpty()) reason else "$head: $reason"
}

/** One fixed text per refusal code of an R3 input (the server answers with the code only). */
internal fun partgAnnexViolationText(violation: network.lapis.cloud.shared.domain.PartgAnnexViolation): String =
    when (violation) {
        network.lapis.cloud.shared.domain.PartgAnnexViolation.AMOUNT_NOT_ABOVE_THRESHOLD ->
            io.kvision.i18n.gettext("In diese Liste gehören nur Personen mit mehr als 10.000 € im Jahr.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.AMOUNT_TOO_LARGE -> io.kvision.i18n.gettext("Der Betrag ist zu groß.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.NAME_EMPTY -> io.kvision.i18n.gettext("Bitte einen Namen angeben.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.FIELD_TOO_LONG -> io.kvision.i18n.gettext("Der Text ist zu lang.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.CONTROL_CHARACTER ->
            io.kvision.i18n.gettext("Bitte keine Steuerzeichen verwenden.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.COUNTRY_INVALID ->
            io.kvision.i18n.gettext("Bitte zwei Großbuchstaben angeben, zum Beispiel DE.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.TOO_MANY -> io.kvision.i18n.gettext("Es sind nicht mehr Einträge möglich.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.OUT_OF_RANGE ->
            io.kvision.i18n.gettext(
                "Der Wert liegt außerhalb des erlaubten Bereichs.",
            )
        network.lapis.cloud.shared.domain.PartgAnnexViolation.ENTRIES_EMPTY ->
            io.kvision.i18n.gettext("Bitte mindestens eine Leistung eintragen.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.DATE_OUTSIDE_YEAR ->
            io.kvision.i18n.gettext("Das Datum muss im Berichtsjahr liegen.")
        network.lapis.cloud.shared.domain.PartgAnnexViolation.DUPLICATE_ID ->
            io.kvision.i18n.gettext("Eine Leistung kommt doppelt vor.")
    }

/** The order of the tabs on screen; the enum order is only the storage order (new tabs are appended there). */
internal val PARTG_TAB_DISPLAY_ORDER: List<PartyReportTab> =
    listOf(
        PartyReportTab.OVERVIEW,
        PartyReportTab.SHEET,
        PartyReportTab.AUSWEISE,
        PartyReportTab.ERLAEUTERUNGEN,
        PartyReportTab.POSITIONS,
        PartyReportTab.KONTENZUORDNUNG,
        PartyReportTab.REPORT,
        // V1.9.290 (R5): last, rightmost; a chapter treasurer never gets it (see partgVisibleTabs)
        PartyReportTab.KONSOLIDIERUNG,
        // V1.9.291 (R6): last, rightmost; a chapter treasurer never gets it (see partgVisibleTabs)
        PartyReportTab.FREIGABE,
    )

/**
 * V1.9.289 (R4a): the tabs a person sees. A chapter treasurer (a plain member with a grant, never BOARD/ADMIN) sees the overview, the entry of
 * their units, the mapping (read only) and, since V1.9.292 (R4b), the unit part; the server refuses everything else anyway, this only hides the doors.
 */
internal fun partgVisibleTabs(session: network.lapis.cloud.shared.domain.SessionInfoDto?): List<PartyReportTab> =
    if (partgChapterTreasurerOnly(session)) {
        // V1.9.292 (R4b): plus the unit part ("Bericht"): their own unit as PDF/Excel/CSV, never the whole party or the versions
        listOf(PartyReportTab.OVERVIEW, PartyReportTab.SHEET, PartyReportTab.KONTENZUORDNUNG, PartyReportTab.REPORT)
    } else {
        PARTG_TAB_DISPLAY_ORDER
    }

/** `true` iff the person reaches the report only through a chapter-treasurer grant. A missing session is NOT restricted (tests, loading). */
internal fun partgChapterTreasurerOnly(session: network.lapis.cloud.shared.domain.SessionInfoDto?): Boolean =
    session != null &&
        session.role != network.lapis.cloud.shared.domain.AccountRole.BOARD &&
        session.role != network.lapis.cloud.shared.domain.AccountRole.ADMIN &&
        session.chapterTreasurerScopes.isNotEmpty()

/** V1.9.289: the tab a deep link asks for (`?tab=KONTENZUORDNUNG`); `null` for anything else. Strict: an exact tab name. */
internal fun partgTabOfQuery(queryString: String?): PartyReportTab? =
    queryString
        ?.removePrefix("?")
        ?.split("&")
        ?.firstNotNullOfOrNull { pair ->
            val (key, value) = pair.split("=", limit = 2).let { it.first() to it.getOrNull(1) }
            if (key == "tab") PartyReportTab.entries.firstOrNull { it.name == value } else null
        }

/** The number of hints that ask for a look, per unit key. */
internal fun partgCheckCounts(findings: List<network.lapis.cloud.shared.domain.PartgFindingDto>): Map<String, Int> =
    findings
        .filter { it.level == network.lapis.cloud.shared.domain.PartgFindingLevel.CHECK && it.unitKey != null }
        .groupingBy { checkNotNull(it.unitKey) }
        .eachCount()
