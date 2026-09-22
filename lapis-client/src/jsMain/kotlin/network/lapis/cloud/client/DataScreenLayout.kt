package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.panel.HPanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Cell
import io.kvision.table.HeaderCell
import io.kvision.table.ResponsiveType
import io.kvision.table.Row
import io.kvision.table.Scope
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px

/**
 * Gemeinsames Layout-Vokabular fuer **datendichte Tabellen-Screens** -- Ergebnis der
 * Design-Team-Sitzung vom 2026-09-18 (siehe Vault, root `CLAUDE.md` "UI/UX-Design-Team").
 *
 * ## Warum ueberhaupt
 *
 * Jeder Screen hatte seine eigene, hart kodierte Breite auf dem Root-`vPanel`
 * (`width = 720/800/860/900/960.px` + `mx-auto`). Zwei Folgen:
 *
 * 1. **`width` statt `maxWidth` ist nicht responsiv.** Ein fester `width`-Wert bleibt auch dann
 *    stehen, wenn das Viewport schmaler ist -- auf 375 px erzwingt `width = 960.px` horizontales
 *    Scrollen der gesamten Seite. `maxWidth` deckelt nur nach oben und laesst den Inhalt darunter
 *    frei schrumpfen.
 * 2. **Auf grossen Schirmen blieb rechts toter Raum**, obwohl genau diese Screens (Kontenplan,
 *    Mahnwesen, SEPA-Laeufe) breite Tabellen mit sechs bis acht Spalten tragen.
 *
 * ## Warum 1440 px und nicht "unbegrenzt"
 *
 * Unbegrenzte Breite bricht die Lesbarkeit der Textspalten (Kontoname, Betreff, Grund) auf einem
 * 27"-Schirm -- die Zeilenlaenge wird zur Augenbewegung. 1440 px ist der Kompromiss, den das
 * Design-Team gegen Ives "nimm den ganzen Schirm" gesetzt hat: breit genug, dass auf einem
 * 1440p-/1080p-Laptop kein toter Raum mehr entsteht, schmal genug, dass die Tabelle auf einem
 * Ultrawide nicht zum Horizont laeuft.
 *
 * ## Geltungsbereich -- bewusst NICHT app-weit
 *
 * Diese Helfer gehoeren ausschliesslich auf Screens mit **breiten Datentabellen**. Formular- und
 * Karten-Screens (`LoginScreen`, `RegistrationScreen`, `FriendRegistrationScreen`,
 * `VerifyEmailDeepLinkScreen`, `PasswordResetDeepLinkScreen`, `ConferenceScreen`) sowie die
 * Governance-Karten-Screens (Gremien, Sitzungen, Antraege, Events) behalten ihre schmale,
 * lesefreundliche Spalte -- deren Grosszuegigkeit ist fuer ihre Nutzung richtig und wurde in der
 * Sitzung ausdruecklich bestaetigt.
 *
 * **`CostCentersScreen`/`DonorsScreen`: Nachtrag Design-Team-Sitzung 2026-09-18 (Nachmittag).**
 * Beide standen am Vormittag noch auf der Karten-Ausnahmeliste ("Aktion nicht im dichten Raster").
 * Steve Jobs' abschliessendes Review (Punkt 2) hat diese Entscheidung revidiert: Tesler gewinnt
 * gegen Rams, weil auf dem Kostenstellen-Screen dieselben Objekte oben als Karten und im Bericht
 * darunter bereits als Tabellenzeile erscheinen -- zwei Darstellungsgrammatiken fuer ein Objekt auf
 * einem Screen war der eigentliche Defekt, nicht die Kartenform selbst. Beide Screens nutzen jetzt
 * [dataScreenRoot] + eine echte Tabelle -- analog zu `LedgerScreen.kt`s Kontenplan-Tabelle.
 *
 * ## Gegenstueck auf der Tabelle selbst: `ResponsiveType.RESPONSIVE`
 *
 * Solange das Root-Panel eine feste Breite von 900 px hatte, scrollte auf einem 375-px-Telefon die
 * GANZE Seite horizontal -- Kopfzeile und Navigation inklusive. Mit der jetzt frei schrumpfenden
 * Breite waere eine achtspaltige Tabelle stattdessen aus ihrem Container herausgelaufen. Deshalb
 * gehoert zu jedem Screen, der auf [dataScreenRoot] umgestellt wird, zwingend
 * `responsiveType = ResponsiveType.RESPONSIVE` auf seinen breiten Tabellen: Bootstrap legt die
 * Tabelle dann in einen eigenen, horizontal scrollbaren Rahmen -- gescrollt wird die Tabelle, nicht
 * die Seite. Das ist der eigentliche Grund, warum die Breitenfreigabe bei 375 px nicht bricht.
 */
internal const val DATA_SCREEN_MAX_WIDTH_PX = 1440

/**
 * The other three screen widths of the UI/UX guideline (Welle V1.4.25, W1). Declared here so the later
 * waves (W2-W5) migrate screens onto named widths instead of new magic numbers; W1 itself applies none
 * of them.
 *
 * - [NARROW_FORM_MAX_WIDTH_PX]: single-purpose forms (login, password reset).
 * - [READING_MAX_WIDTH_PX]: running text / card screens with a comfortable line length.
 * - [DASHBOARD_MAX_WIDTH_PX]: the compact "what needs me" overview.
 * - [CARD_LIST_BREAKPOINT_PX]: below this viewport width a [dataTable] renders as a card list (the one
 *   new breakpoint; `theme.css` and [CARD_LIST_MEDIA_QUERY] use the matching `767.98px`).
 */
internal const val NARROW_FORM_MAX_WIDTH_PX = 480

/** The card width of the pre-login screens (login, password reset): narrower than [NARROW_FORM_MAX_WIDTH_PX] (V1.4.28 audit: a named constant instead of a literal in two screens). */
internal const val AUTH_CARD_MAX_WIDTH_PX = 380
internal const val READING_MAX_WIDTH_PX = 720
internal const val DASHBOARD_MAX_WIDTH_PX = 640
internal const val CARD_LIST_BREAKPOINT_PX = 768

/** Default vertical rhythm between the blocks of a data screen (UI/UX guideline: 16 px, was 14). */
internal const val DATA_SCREEN_SPACING_PX = 16

/**
 * Root-Panel eines datendichten Tabellen-Screens.
 *
 * `w-100` + `mx-auto` + [DATA_SCREEN_MAX_WIDTH_PX] als `maxWidth`: nimmt bis 1440 px die volle
 * verfuegbare Breite, zentriert darueber. `px-3` gibt die 16-px-Seitenrinne, damit der Inhalt auf
 * einem 375-px-Telefon nicht am Displayrand klebt -- ohne diese Rinne sass die Tabelle vorher
 * buendig auf Kante, sobald die feste Breite unterschritten wurde.
 */
fun Container.dataScreenRoot(spacing: Int = DATA_SCREEN_SPACING_PX): VPanel =
    vPanel(spacing = spacing) {
        addCssClasses("mx-auto w-100 px-3")
        maxWidth = DATA_SCREEN_MAX_WIDTH_PX.px
        marginTop = 24.px
    }

/**
 * Icon-Knopf fuer die Aktionsspalte einer Datentabelle.
 *
 * ## Warum Icon statt Text
 *
 * Mehrere Volltext-Knoepfe pro Zeile ("Details anzeigen", "Deaktivieren") stapeln sich in einer
 * schmalen Zelle untereinander und blaehen jede Zeile auf ~60 px auf. Bei 40 Konten ist das eine
 * halbe Bildschirmhoehe reiner Knopfrand. Nebeneinanderliegende Icon-Knoepfe halten die Zeile bei
 * einer Zeilenhoehe.
 *
 * ## Warum trotzdem nicht "Icon pur"
 *
 * Don Normans Einwand in der Sitzung -- Zielgruppe ist teils der Schatzmeister, der sich einmal im
 * Monat einloggt -- ist der Grund, warum [tooltip] **Pflichtparameter** ist und sowohl als
 * `title` (Hover-Tooltip) als auch als `aria-label` (Screenreader; ein Knopf ohne Textinhalt hat
 * sonst keinen zugaenglichen Namen) gesetzt wird. Ein Icon ohne jeden Kontext waere die
 * Verdichtung, die die Erkennbarkeit kaputt macht.
 *
 * `title` wird ueber KVisions eigene `Widget.title`-Property gesetzt, nicht per rohem
 * DOM-Schreibzugriff -- siehe die KDoc-Warnung in `MemberAdministrationScreen.kt` zum
 * `###KvI18nS###`-Marker-Leak, wenn uebersetzte Strings am KVision-Rendering vorbei gesetzt werden.
 *
 * `btn-sm` per [addCssClass] statt ueber ein KVision-`size`-Enum: eine einzelne, garantiert
 * vorhandene Bootstrap-Utility-Klasse (siehe `CssClasses.kt` zum Unterschied Einzelklasse vs.
 * Mehrfachklassen-String).
 */
fun Container.tableActionButton(
    icon: String,
    tooltip: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
): Button {
    val actionButton = button("", icon = icon, style = style)
    actionButton.addCssClass("btn-sm")
    actionButton.tableActionTooltip(tooltip)
    return actionButton
}

/**
 * Setzt Tooltip **und** zugaenglichen Namen eines Icon-Knopfes in einem Schritt.
 *
 * Eigene Funktion (statt nur `title = ...`), weil mehrere Aufrufstellen den Tooltip nachtraeglich
 * ueberschreiben, sobald der Knopf deaktiviert wird ("DSGVO-geloescht", "Peer-Schutz ..."): dabei
 * muss `aria-label` zwingend mitwandern, sonst liest ein Screenreader weiter den alten Grund vor.
 */
fun Button.tableActionTooltip(tooltip: String) {
    title = tooltip
    // Welle V1.4.21: `aria-label` geht per `setAttribute` am KVision-Patch-Zyklus vorbei -- ein `tr(...)`-
    // Ergebnis trüge dort den `###KvI18nS###`-Marker in den DOM (Screenreader: "###KvI18nS###Details
    // anzeigen"). `title` oben ist eine KVision-Property und wird korrekt aufgelöst. Marker und
    // Übersetzung werden in `resolvedAttributeText` aufgelöst -- für bereits aufgelöste Strings
    // (`gettext`) ein No-op, gilt also unbedingt --
    // Aufrufstellen dürfen weiter `tr(...)` übergeben. Der Tripwire `ClientTrAttributeLeakTest` sieht
    // diesen indirekten Fluss (Aufrufstelle -> Helfer -> setAttribute) nicht.
    setAttribute("aria-label", resolvedAttributeText(tooltip))
}

/** Marker, den KVisions `tr()` um jeden Text legt; nur der eigene Patch-Zyklus löst ihn auf. */
internal const val KV_I18N_MARKER = "###KvI18nS###"

/**
 * Der Plural-Gegenstueck-Marker zu [KV_I18N_MARKER]: KVisions `ntr(singularKey, pluralKey, value)`
 * kodiert alle drei Teile mit diesem Trenner (`"###KvI18nP###" + singularKey + "###KvI18nP###" + pluralKey +
 * "###KvI18nP###" + value`, siehe `io.kvision.i18n.Widget.trans`/`ntr` im kompilierten Bundle). Diese Codebase
 * benutzt `ntr()`/`ngettext()` selbst nirgends (siehe [I18nCatalogManager]-KDoc), aber KVisions eigener
 * `Widget.trans`-Render-Pfad loest den Marker trotzdem bedingungslos auf jedem Widget-Content auf, unabhaengig
 * davon, ob diese App ihn je selbst erzeugt -- ein Angreifer kann ihn als reinen ASCII-Text in ein beliebiges
 * DTO-Feld tippen. [sanitizeUntrustedI18nText] muss ihn deshalb genauso entfernen wie [KV_I18N_MARKER]
 * (Security-Audit W6b, Runde 7).
 */
internal const val KV_I18N_MARKER_PLURAL = "###KvI18nP###"

/**
 * Für jeden Text, der per rohem `setAttribute(...)` in den DOM geht: löst den `tr()`-Marker UND die
 * Übersetzung auf (`I18n.trans`). Nur den Marker zu entfernen (`removePrefix`) hinterließ den
 * UNübersetzten deutschen Schlüssel -- der sichtbare Spaltenkopf war übersetzt, Tooltip/`aria-label`
 * des Sortierknopfs und der Icon-Knöpfe blieben deutsch (Audit V1.4.25 M2). Für bereits aufgelöste
 * Strings (`gettext`) ist das ein No-op. Gleiche Idee wie `ConferenceScreen.resolvedA11yText`, aber
 * mit Übersetzung.
 */
internal fun resolvedAttributeText(text: String): String = I18n.trans(text).removePrefix(KV_I18N_MARKER)

/**
 * Segmented Control (Bootstrap `btn-group btn-group-sm`, `role="group"`, je Knopf `aria-pressed`) für
 * eine kleine, gegenseitig ausschließende Auswahl -- Welle V1.4.21 (Offene Posten: Alle / Kreditoren /
 * Debitoren). [options] trägt bereits übersetzte Labels (sichtbarer Knopftext ist zugleich der
 * zugängliche Name); [ariaLabel] benennt die Gruppe und läuft über [resolvedAttributeText], darf
 * also `tr(...)` oder `gettext(...)` sein.
 */
fun <T> Container.segmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    ariaLabel: String,
    onSelect: (T) -> Unit,
): HPanel {
    val group =
        hPanel(spacing = 0) {
            addCssClasses("btn-group btn-group-sm")
            setAttribute("role", "group")
            setAttribute("aria-label", resolvedAttributeText(ariaLabel))
        }
    var current = selected
    val buttons = mutableListOf<Pair<T, Button>>()

    fun paint() {
        buttons.forEach { (value, segmentButton) ->
            val active = value == current
            segmentButton.style = if (active) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY
            segmentButton.setAttribute("aria-pressed", active.toString())
        }
    }
    options.forEach { (value, label) ->
        val segmentButton = group.button(label, style = ButtonStyle.OUTLINEPRIMARY)
        buttons.add(value to segmentButton)
        segmentButton.onClick {
            if (value != current) {
                current = value
                paint()
                onSelect(value)
            }
        }
    }
    paint()
    return group
}

/**
 * Container fuer mehrere [tableActionButton]s in derselben Tabellenzelle.
 *
 * `flex-nowrap` ist der eigentliche Punkt: mit dem vorherigen `flex-wrap` sind zwei Knoepfe in
 * einer schmalen Aktionsspalte untereinander gerutscht -- genau der Zeilen-Stapel, den diese Welle
 * beseitigt. Mit Icon-Knoepfen (~34 px) passen auch vier Aktionen nebeneinander.
 */
fun Container.tableActionGroup(): HPanel =
    hPanel(spacing = 4) {
        addCssClasses("flex-nowrap align-items-center")
    }

/** One column header of a [standardTable]; [numeric] right-aligns it like its `td`s ([numCell]). */
class TableHeader(
    val title: String,
    val numeric: Boolean = false,
)

/**
 * The one table of the guideline: striped + hover + small rows, wrapped in Bootstrap's horizontally
 * scrolling frame (`ResponsiveType.RESPONSIVE`) so a wide table scrolls itself, never the page. Every
 * `table(...)` call in the client goes through this helper or [dataTable] (the `ClientUiGuidelineTripwireTest`
 * rules R14/R15 enforce it for migrated files).
 *
 * Headers are always built as [HeaderCell] widgets (never `headerNames`): mixing both is unsafe because
 * the `headerNames` setter empties the header row, and [dataTable] needs widget headers for its sort
 * buttons. [headers] may be empty when the caller adds its own header cells.
 */
fun Container.standardTable(headers: List<TableHeader>): Table {
    val standard =
        table(
            types = setOf(TableType.STRIPED, TableType.HOVER, TableType.SMALL),
            responsiveType = ResponsiveType.RESPONSIVE,
        )
    headers.forEach { header -> standard.addHeaderCell(plainHeaderCell(header)) }
    return standard
}

internal fun plainHeaderCell(header: TableHeader): HeaderCell =
    HeaderCell(content = header.title, scope = Scope.COL) {
        if (header.numeric) addCssClass(NUMERIC_CELL_CLASS)
    }

/** CSS class of right-aligned tabular-figure cells (`theme.css` `.lapis-num`). */
internal const val NUMERIC_CELL_CLASS = "lapis-num"

/**
 * A numeric table cell: right-aligned, tabular figures. [content] as plain text, or build the content
 * in [init] (e.g. `moneySpan(amount)`).
 */
fun Row.numCell(
    content: String? = null,
    init: (Cell.() -> Unit)? = null,
): Cell =
    cell(content = content) {
        addCssClass(NUMERIC_CELL_CLASS)
        init?.invoke(this)
    }

// ============================================================================================
// Report grammar (Welle V1.4.27, W3 "Pseudo-Tabellen ablösen")
// ============================================================================================

/** CSS classes of the report grammar (`theme.css`, "Berichtsgrammatik"). */
internal const val REPORT_TABLE_CLASS = "lapis-report-table"
internal const val SECTION_ROW_CLASS = "lapis-section-row"
internal const val SUBSECTION_ROW_CLASS = "lapis-section-row-sub"
internal const val TOTAL_ROW_CLASS = "lapis-total-row"
internal const val TOTAL_ROW_STRONG_CLASS = "lapis-total-row-strong"
internal const val BALANCE_ROW_CLASS = "lapis-balance-row"
internal const val NOTE_ROW_CLASS = "lapis-note-row"
internal const val REPORT_CAPTION_HIDDEN_CLASS = "lapis-report-caption-hidden"

/**
 * A **document** table -- the second of the guideline's two table grammars (the first is [dataTable]).
 * Criterion: a row is only right in the context of its neighbours (running balance, number sequence, a
 * total underneath). So: a real `<table>` in the responsive frame ([standardTable]) with a visible
 * `<caption>` -- and NEVER sort buttons, NEVER the card list. A card list renders only `rows`, so a total or
 * balance row would silently vanish on a phone; a report scrolls sideways below 768 px instead (deliberate,
 * see `ui-ux-guideline.adoc`).
 *
 * Goes through [standardTable] (no `table(` literal of its own: `ClientUiGuidelineTripwireTest` counts the
 * calls). Bootstrap's reboot sets `caption-side: bottom`; its own utility `caption-top` puts the title where a
 * heading belongs, without new CSS.
 */
fun Container.reportTable(
    caption: String,
    headers: List<TableHeader>,
    captionVisible: Boolean = true,
): Table {
    val report = standardTable(headers)
    report.caption = caption
    report.addCssClasses("$REPORT_TABLE_CLASS caption-top")
    // Audit V1.4.27 (MINOR-2): a caption that only repeats the heading or the header row right above the table is
    // visual noise, but it is still the table's ACCESSIBLE NAME -- so it is hidden visually, not removed
    // (`captionVisible = false`, the `theme.css` rule `.lapis-report-caption-hidden` = Bootstrap's `visually-hidden`
    // recipe for the `<caption>`; KVision's `Table` has no API for classes on the caption itself).
    if (!captionVisible) report.addCssClass(REPORT_CAPTION_HIDDEN_CLASS)
    return report
}

/** Paints [rows] -- the one place where the report grammar turns [ReportRow]s into table rows. */
fun Table.reportRows(
    rows: List<ReportRow>,
    headers: List<TableHeader>,
) {
    rows.forEach { data -> reportRow(data, headers) }
}

/**
 * One [ReportRow]. [extra] runs for figure rows ([ReportRowKind.DATA]/[ReportRowKind.BALANCE]/[ReportRowKind.TOTAL])
 * after the cells and is where a screen adds its own action cell (the detail toggle of a sphere row).
 */
fun Table.reportRow(
    data: ReportRow,
    headers: List<TableHeader>,
    extra: (Row.() -> Unit)? = null,
): Row =
    when (data.kind) {
        ReportRowKind.SECTION -> sectionRow(data.cells.first().text, headers.size)
        ReportRowKind.SUBSECTION -> sectionRow(data.cells.first().text, headers.size, sub = true)
        ReportRowKind.NOTE -> noteRow(data.cells.first().text, headers.size)
        ReportRowKind.DATA, ReportRowKind.BALANCE, ReportRowKind.TOTAL ->
            row(className = reportRowClass(data)) {
                // D4: the label of a sum/balance row is the row HEADER of that row (`<th scope="row">`), so assistive
                // technology can read "Summe Einnahmen, Betrag, 1513 EUR"; the first text cell is the label (the
                // opening/closing balance of a ledger has blank cells in front of it).
                val labelIndex =
                    if (data.kind == ReportRowKind.TOTAL || data.kind == ReportRowKind.BALANCE) {
                        data.cells.indexOfFirst { it is ReportCell.Text }
                    } else {
                        -1
                    }
                data.cells.forEachIndexed { index, reportCell ->
                    if (index == labelIndex) {
                        paintRowHeaderCell(reportCell as ReportCell.Text)
                    } else {
                        paintReportCell(
                            reportCell,
                            numericColumn =
                                headers.getOrNull(index)?.numeric == true,
                        )
                    }
                }
                extra?.invoke(this)
            }
    }

private fun reportRowClass(data: ReportRow): String? =
    when (data.kind) {
        ReportRowKind.TOTAL -> if (data.strong) "$TOTAL_ROW_CLASS $TOTAL_ROW_STRONG_CLASS" else TOTAL_ROW_CLASS
        ReportRowKind.BALANCE -> BALANCE_ROW_CLASS
        else -> null
    }

private fun Row.paintRowHeaderCell(label: ReportCell.Text) {
    val header =
        HeaderCell(scope = Scope.ROW) {
            span(label.text) {
                if (label.muted) addCssClass("text-muted")
                if (label.italic) addCssClass("fst-italic")
            }
        }
    add(header)
}

private fun Row.paintReportCell(
    reportCell: ReportCell,
    numericColumn: Boolean,
) {
    val numeric = numericColumn || reportCell is ReportCell.Money || reportCell is ReportCell.Ltr
    cell {
        if (numeric) addCssClass(NUMERIC_CELL_CLASS)
        when (reportCell) {
            ReportCell.Blank -> Unit
            is ReportCell.Text ->
                span(reportCell.text) {
                    if (reportCell.muted) addCssClass("text-muted")
                    if (reportCell.italic) addCssClass("fst-italic")
                }
            is ReportCell.Money -> {
                val amount = moneySpan(reportCell.amount, warnIfNegative = reportCell.warnIfNegative)
                if (reportCell.emphasize) amount.addCssClasses("text-danger fw-bold")
            }
            is ReportCell.Ltr -> ltrSpan(reportCell.amount, warnIfNegative = reportCell.warnIfNegative)
            is ReportCell.Badge -> paintBadge(reportCell)
            is ReportCell.Badges -> {
                val group = tableActionGroup()
                reportCell.items.forEach { badge -> group.paintBadge(badge) }
            }
        }
    }
}

private fun Container.paintBadge(badge: ReportCell.Badge) {
    if (badge.filled) statusBadge(badge.text, badge.color) else typeBadge(badge.text, badge.color)
}

/**
 * A section heading inside the body of a report: `<th colspan="N">` and NO `scope`. A report has ONE `<tbody>`, so a
 * `scope="rowgroup"` would let "Aktiva" claim the "Passiva" rows below it as well (Audit V1.4.27 D3: the scope of a
 * `rowgroup` header reaches to the end of its `<tbody>`); a plain spanning header row names the block visually
 * and asserts nothing it cannot keep. (One `<tbody>` per section would make `rowgroup` right -- KVision's `Table`
 * has no per-section body, so this stays a heading row.) [sub] adds the indent class of a sub-section (both
 * classes: the base styling stays).
 */
fun Table.sectionRow(
    label: String,
    columnCount: Int,
    sub: Boolean = false,
): Row =
    row(className = if (sub) "$SECTION_ROW_CLASS $SUBSECTION_ROW_CLASS" else SECTION_ROW_CLASS) {
        val heading = HeaderCell(content = label)
        heading.setAttribute("colspan", columnCount.toString())
        add(heading)
    }

/** A note between figure rows (a `div` between `tr`s is invalid markup and would be moved out by the browser). */
fun Table.noteRow(
    text: String,
    columnCount: Int,
): Row =
    row(className = NOTE_ROW_CLASS) {
        cell(content = text) { setAttribute("colspan", columnCount.toString()) }
    }

/**
 * An expandable detail row: `<tr id="[rowId]"><td colspan="N">`, initially collapsed. [content] fills the cell
 * (nested tables are fine). [rowId] must be stable and unique on the screen (`lapis-detail-<dtoId>`), otherwise
 * `aria-controls` of the toggle points at a foreign or vanished row. Pair it with [expandToggleButton] and
 * flip it with [setExpanded].
 *
 * Collapsed = Bootstrap's `d-none`, NOT KVision's `hide()`: a hidden KVision widget is removed from the DOM,
 * and `aria-controls` must name an element that exists.
 */
fun Table.detailRow(
    rowId: String,
    columnCount: Int,
    content: (Container) -> Unit,
): Row =
    row {
        id = rowId
        val detailCell = cell { setAttribute("colspan", columnCount.toString()) }
        content(detailCell)
        setExpanded(false)
    }

/** Shows/collapses a [detailRow] (`d-none`, the row stays in the document -- see [detailRow]). */
fun Row.setExpanded(expanded: Boolean) {
    if (expanded) removeCssClass("d-none") else addCssClass("d-none")
}

/**
 * The toggle of a [detailRow]: an icon button whose name says WHICH object it opens (`Details %1 ein-/ausblenden`,
 * [objectLabel] is an already resolved label), `aria-expanded` follows the state, `aria-controls` names the row.
 *
 * **Known limit (Audit V1.4.27 D7, deliberately not fixed):** the `title` and the `aria-label` are resolved ONCE, in
 * the language of the moment the toggle is built. `aria-label` goes through `setAttribute` and so around KVision's
 * patch cycle (see [tableActionTooltip]) -- a language switch re-renders `tr(...)` markers but cannot reach an
 * attribute that was set from an already resolved string; the tooltip and the accessible name of an OPEN report
 * therefore stay in the old language until the report is reloaded (the visible cell texts, captions and headers
 * do follow). The same holds for the `aria-label` of every [tableActionButton] on every screen (its `title` follows
 * only where the caller passed a `tr(...)` string).
 */
fun Container.expandToggleButton(
    objectLabel: String,
    detailRowId: String,
    onToggle: (Boolean) -> Unit,
): Button {
    val toggle = tableActionButton("fas fa-chevron-down", gettext("Details %1 ein-/ausblenden", objectLabel))
    toggle.setAttribute("aria-expanded", "false")
    toggle.setAttribute("aria-controls", detailRowId)
    var expanded = false
    toggle.onClick {
        expanded = !expanded
        toggle.setAttribute("aria-expanded", expanded.toString())
        toggle.icon = if (expanded) "fas fa-chevron-up" else "fas fa-chevron-down"
        onToggle(expanded)
    }
    return toggle
}

/**
 * Wrapper for a [segmentedControl] with more than three options: below 768 px the group scrolls sideways
 * instead of wrapping into a ragged block (`theme.css`).
 */
fun Container.segmentedScroll(init: Div.() -> Unit): Div =
    div(className = "lapis-segmented-scroll") {
        init()
    }

/** Label/value list (`<dl>`, CSS grid) -- replaces `width = 220.px` label cells. Fill it with [detailEntry]. */
fun Container.detailList(): Tag = tag(TAG.DL, className = "lapis-detail-list")

/** One `<dt>`/`<dd>` pair of a [detailList]; [value] builds the content of the `<dd>`. */
fun Tag.detailEntry(
    label: String,
    value: Container.() -> Unit,
) {
    tag(TAG.DT, content = label)
    tag(TAG.DD) { value() }
}
