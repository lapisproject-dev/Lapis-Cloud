package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.html.span
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.RegionalChapterRefDto

// V1.9.289 (R4a) -- the "Gliederung" controls of the journal: the field of the booking form, the filter of the journal, the Hauptbuch and the
// Kassenbuch, and the column of the journal table. Kept out of `LedgerScreen.kt` on purpose. The server decides what a person sees; these controls
// only offer the units the server listed (`listBookingUnits`): everything for the finance roles, the granted chapters for a chapter treasurer.

/** The value of the "Bund" entry of the form and the filter (the federal level is `null` in the data). */
internal const val LEDGER_UNIT_FEDERAL = "FEDERAL"
internal const val LEDGER_UNIT_ALL = ""

/** `true` iff the person books only through a chapter-treasurer grant (never TREASURER, BOARD or ADMIN). */
internal fun ledgerChapterOnly(session: network.lapis.cloud.shared.domain.SessionInfoDto? = AppState.session): Boolean =
    session != null &&
        session.role !in setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) &&
        session.chapterTreasurerScopes.isNotEmpty()

/**
 * The options of the form field "Gliederung". The finance roles choose "Bund" (the default) or a chapter; a chapter treasurer chooses among their own
 * chapters only (no "Bund"). No units at all: no field (the list is empty).
 */
internal fun ledgerUnitFormOptions(
    units: List<RegionalChapterRefDto>,
    chapterOnly: Boolean,
): List<Pair<String, String>> =
    when {
        units.isEmpty() -> emptyList()
        chapterOnly -> untrustedOptions(units.map { it.id to it.name })
        else -> listOf(LEDGER_UNIT_ALL to tr("Bund")) + untrustedOptions(units.map { it.id to it.name })
    }

/** The value the form starts with: the only chapter of a chapter treasurer, else "Bund". */
internal fun ledgerUnitFormDefault(
    units: List<RegionalChapterRefDto>,
    chapterOnly: Boolean,
): String = if (chapterOnly && units.size == 1) units.single().id else LEDGER_UNIT_ALL

/** `true` iff the form field is fixed (a chapter treasurer of exactly one chapter): shown, but not changeable. */
internal fun ledgerUnitFormLocked(
    units: List<RegionalChapterRefDto>,
    chapterOnly: Boolean,
): Boolean = chapterOnly && units.size == 1

/** The options of the filter: "Alle", "Bund" (finance roles only) and the units. */
internal fun ledgerUnitFilterOptions(
    units: List<RegionalChapterRefDto>,
    chapterOnly: Boolean,
): List<Pair<String, String>> =
    if (units.isEmpty()) {
        emptyList()
    } else {
        listOf(LEDGER_UNIT_ALL to tr("Alle")) +
            (if (chapterOnly) emptyList() else listOf(LEDGER_UNIT_FEDERAL to tr("Bund"))) +
            untrustedOptions(units.map { it.id to it.name })
    }

/** The `unitFilter` argument of the RPC for a selected filter value (`""` = no filter). */
internal fun ledgerUnitFilterArgument(selected: String?): String? = selected?.takeIf { it.isNotEmpty() }

/** The `regionalChapterId` of a booking for the selected value of the form field (`""` = federal = `null`). */
internal fun ledgerUnitInputId(selected: String?): String? = selected?.takeIf { it.isNotEmpty() && it != LEDGER_UNIT_FEDERAL }

/** The text of the column "Gliederung". */
internal fun ledgerUnitLabel(entry: JournalEntryDto): String = entry.regionalChapterName ?: tr("Bund")

/** The filter select. [onChange] gets the RPC argument (`null` = no filter). Nothing is added while there are no units. */
internal fun Container.ledgerUnitFilter(
    units: List<RegionalChapterRefDto>,
    chapterOnly: Boolean,
    onChange: (String?) -> Unit,
) {
    val options = ledgerUnitFilterOptions(units, chapterOnly)
    if (options.isEmpty()) return
    span(tr("Gliederung"), className = "fw-semibold me-1")
    val picker = select(options = options, value = LEDGER_UNIT_ALL)
    val pickerLabel = resolvedAttributeText(tr("Gliederung"))
    picker.setAttribute("aria-label", pickerLabel)
    picker.subscribe { onChange(ledgerUnitFilterArgument(picker.value)) }
}
