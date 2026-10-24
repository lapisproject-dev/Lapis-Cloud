package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.InputType
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
internal class DateRangeFilterControls(
    private val from: DateEndpoint,
    private val to: DateEndpoint,
) {
    fun parseFrom(): LocalDate? = from.dateOrNull()

    fun parseTo(): LocalDate? = to.dateOrNull()

    /** The raw entry of the start field (ISO in picker mode, the locale text otherwise; a half-typed picker entry is a non-blank marker). */
    val fromText: String get() = from.text

    val toText: String get() = to.text

    fun setFrom(date: LocalDate?) = from.setDate(date)

    fun setTo(date: LocalDate?) = to.setDate(date)

    /**
     * Welle V1.4.26 (W2): „hat der Leser überhaupt einen Zeitraum eingegrenzt?" -- die Frage, die ein
     * Leerzustand beantworten muss, um „noch keine Daten" von „nichts in diesem Zeitraum" zu trennen
     * (Richtlinie 2.4). Absichtlich über die ROHE Eingabe, nicht über [parseFrom]/[parseTo]: ein
     * unvollständig getipptes Datum ist serverseitig kein Filter, für den Leser aber sehr wohl eine
     * Eingrenzungsabsicht -- der Satz „nichts in diesem Zeitraum" ist dann der hilfreichere.
     */
    fun hasRange(): Boolean = from.text.isNotBlank() || to.text.isNotBlank()
}

/** V1.9.173: one end of a date range, whichever widget holds it (a raw [LocalDateInput] or a [LapisField]). */
internal interface DateEndpoint {
    fun dateOrNull(): LocalDate?

    val text: String

    fun setDate(date: LocalDate?)
}

internal fun LocalDateInput.asEndpoint(): DateEndpoint =
    object : DateEndpoint {
        override fun dateOrNull() = this@asEndpoint.dateOrNull()

        override val text get() = this@asEndpoint.text.trim()

        override fun setDate(date: LocalDate?) = this@asEndpoint.setDate(date)
    }

internal fun LapisField.asEndpoint(): DateEndpoint =
    object : DateEndpoint {
        override fun dateOrNull() = this@asEndpoint.localDateOrNull()

        override val text get() = this@asEndpoint.rawTemporalText().trim()

        override fun setDate(date: LocalDate?) = this@asEndpoint.setDate(date)
    }

internal fun Container.dateRangeFilter(
    fromLabel: String = tr("Von (optional)"),
    toLabel: String = tr("Bis"),
): DateRangeFilterControls = lapisToolbar().dateRangeFields(fromLabel, toLabel)

/**
 * V1.9.206: the two date fields placed DIRECTLY into the receiver -- no toolbar of their own. [dateRangeFilter] wraps them in one;
 * a screen that already has a row (an `inlineFieldRow`) calls this so the row has no nested toolbar and one centre line.
 */
internal fun Container.dateRangeFields(
    fromLabel: String = tr("Von (optional)"),
    toLabel: String = tr("Bis"),
): DateRangeFilterControls {
    // V1.9.137: the two fields read and show the date in the locale (`15.10.2026`); V1.9.173: native pickers, no hint line in a toolbar.
    val fromInput = localDateInput(label = fromLabel, hintLine = false)
    val toInput = localDateInput(label = toLabel, hintLine = false)
    linkDateRange(fromInput, toInput)
    return DateRangeFilterControls(fromInput.asEndpoint(), toInput.asEndpoint())
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
    // V1.9.173: a year is a number, never a `type=month` picker (it would show a month nobody chooses).
    val yearInput = text(type = InputType.NUMBER, value = currentYear.toString(), label = label)
    yearInput.input.setAttribute("min", LOCALE_DATE_MIN_YEAR.toString())
    yearInput.input.setAttribute("max", LOCALE_DATE_MAX_YEAR.toString())
    return FiscalYearFilterControls(yearInput)
}
