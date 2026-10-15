package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.i18n.tr
import kotlinx.datetime.LocalDate

// Accounting UI wave -- design decision D12: two genuinely different report-scoping shapes exist
// across `IAccountingService` (a `from`/`to` `LocalDate?` range for the Journal/Hauptbuch/
// Kassenbuch/GuV/four-sphere/cost-center reports, vs. a plain `Int` fiscal year for the
// Mittelverwendungsrechnung/Jahresabschluss) -- one shared control per shape, in one file, because
// multiple Accounting screens need each identically and duplicating either five times is how drift
// happens. No date-picker precedent exists anywhere in this client yet (first use this wave), so
// both mirror the established "plain `text` input + client-side parse, server is the real
// validator" idiom `ContributionsScreen.renderTierAdministration`'s own period inputs already set.

/**
 * [fromInput]/[parseFrom] stay genuinely optional -- an empty `from` means "seit Gründung"
 * server-side (see e.g. `IAccountingService.listJournal` KDoc), and this control must preserve
 * that meaning exactly rather than silently defaulting to e.g. "start of this year". Whether a
 * blank/unparsable [toInput] is itself an error is left to the caller: some RPC signatures accept
 * `to: LocalDate?` (Journal/Hauptbuch/Kassenbuch), others require `to: LocalDate` (GuV/four-sphere/
 * cost-center report) -- this control does not know which shape a given caller needs.
 */
class DateRangeFilterControls(
    val fromInput: Text,
    val toInput: Text,
) {
    fun parseFrom(): LocalDate? =
        fromInput.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { parseLocalDateOrNull(it) }

    fun parseTo(): LocalDate? =
        toInput.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { parseLocalDateOrNull(it) }

    /**
     * Welle V1.4.26 (W2): „hat der Leser überhaupt einen Zeitraum eingegrenzt?" -- die Frage, die ein
     * Leerzustand beantworten muss, um „noch keine Daten" von „nichts in diesem Zeitraum" zu trennen
     * (Richtlinie 2.4). Absichtlich über die ROHE Eingabe, nicht über [parseFrom]/[parseTo]: ein
     * unvollständig getipptes Datum ist serverseitig kein Filter, für den Leser aber sehr wohl eine
     * Eingrenzungsabsicht -- der Satz „nichts in diesem Zeitraum" ist dann der hilfreichere.
     */
    fun hasRange(): Boolean = !fromInput.value.isNullOrBlank() || !toInput.value.isNullOrBlank()
}

fun Container.dateRangeFilter(
    fromLabel: String = tr("Von (optional)"),
    toLabel: String = tr("Bis"),
): DateRangeFilterControls {
    val row = lapisToolbar()
    // V1.9.137: the two fields read and show the date in the locale (`15.10.2026`); the pattern is their placeholder, no hint line in a toolbar.
    val fromInput = row.localDateInput(label = fromLabel, hintLine = false).input
    val toInput = row.localDateInput(label = toLabel, hintLine = false).input
    return DateRangeFilterControls(fromInput, toInput)
}

/**
 * Pre-filled to [currentYear] as a normal, visibly-editable form default -- same category as
 * `MeetingsScreen`/`CommitteesScreen`'s own `positionInput = "1"`-style defaults, not a hidden
 * client-side substitution into a request the user never saw.
 */
class FiscalYearFilterControls(
    val yearInput: Text,
) {
    fun parseYear(): Int? = yearInput.value?.trim()?.toIntOrNull()
}

fun Container.fiscalYearFilter(
    currentYear: Int,
    label: String = tr("Geschäftsjahr (JJJJ)"),
): FiscalYearFilterControls {
    val yearInput = text(value = currentYear.toString(), label = label)
    return FiscalYearFilterControls(yearInput)
}
