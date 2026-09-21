package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnnualFinancialStatementDto
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.DatevExportPreviewDto
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.StatementLineDto
import network.lapis.cloud.shared.rpc.IAccountingService
import kotlin.time.Clock

/**
 * Accounting UI wave, screen 2 of 5 -- "Finanzberichte" (GuV / Bilanz / Jahresabschluss), per the
 * approved plan + UI/UX-Design-Team review on `feature/accounting-ui`. Kassenbuch, which the
 * task's own original scope grouped alongside these three, was deliberately moved to
 * `LedgerScreen.kt` instead (screen 1) -- see the approved plan's "Screen file layout" section
 * ("one grouping deviation"): Kassenbuch is a per-`LedgerAccountDto` drill-down that shares a
 * navigation flow with the General Ledger (Hauptbuch), not with these org-wide, date/fiscal-year-
 * scoped statements.
 *
 * Purely read-only -- no `canManage` split anywhere on this screen, because `IAccountingService`
 * has no mutating method among [IAccountingService.getIncomeStatement],
 * [IAccountingService.getBalanceSheet], [IAccountingService.getAnnualFinancialStatement]. All three
 * are `ACCOUNTING_READ_ROLES` (TREASURER/BOARD/ADMIN) server-side, matching `Routing.kt`'s route
 * guard for [Routes.FINANCIAL_REPORTS] exactly -- a BOARD caller (who is never in `TREASURY_ROLES`)
 * sees the full screen with no reduced affordances, unlike `LedgerScreen.kt`.
 *
 * Design decision D5/D6 (Money.kt): every monetary figure below is a [Decimal] returned verbatim
 * by `IAccountingService` and rendered through [formatMoney]/[moneySpan] -- this screen never
 * parses, sums, or re-rounds a figure the server has already computed. The one place this screen
 * touches a raw [Decimal] value at all is [isNegative], a **typed** numeric comparison
 * ([Decimal.toDouble] against `0.0`, never string inspection) used only to decide whether
 * [moneySpan]'s `warnIfNegative` styling applies and whether to show the "(Jahresfehlbetrag)"
 * qualifier -- both are presentation-only decisions, the displayed digits themselves are untouched.
 *
 * [renderIncomeStatementBody]/[renderBalanceSheetBody] are shared between this screen's own
 * GuV/Bilanz tabs and [renderAnnualFinancialStatementView]'s embedded rendering of
 * [AnnualFinancialStatementDto.incomeStatement]/[AnnualFinancialStatementDto.balanceSheet] -- one
 * rendering per DTO shape, never duplicated, so the Jahresabschluss view can never drift from the
 * plain GuV/Bilanz views' own presentation.
 */
fun renderFinancialReportsScreen(container: SimplePanel) {
    val root =
        container.dataScreenRoot(spacing = 14)
    root.h1(tr("Finanzberichte"))

    // Welle V1.4.27 (W3): ONE segmented control with an active state (R20/R48) instead of five outline buttons
    // that never said which report was showing. The fourth/fifth entries keep their history: V1.4.5.2
    // "DATEV-Format-Export" and V1.4.5.3/.4 "Buchhaltungs-Export" (lexoffice AND sevDesk behind ONE entry, the
    // provider is chosen inside the view -- no sixth entry). `Routes.FINANCIAL_REPORTS` is already
    // `ACCOUNTING_READ_ROLES`-gated, i.e. exactly `DatevAuthzUi.PREVIEW_ROLES`; the narrower file-download and
    // TREASURER/ADMIN tiers are enforced INSIDE the views (see `DatevAuthzUi`/`AccountingExportAuthzUi`).
    val options =
        listOf(
            FinancialReportView.GUV to tr("GuV"),
            FinancialReportView.BILANZ to tr("Bilanz"),
            FinancialReportView.JAHRESABSCHLUSS to tr("Jahresabschluss"),
            FinancialReportView.DATEV to tr("DATEV-Export"),
            FinancialReportView.ACCOUNTING_EXPORT to tr("Buchhaltungs-Export"),
        )
    lateinit var contentPanel: SimplePanel
    root.segmentedScroll {
        segmentedControl(options = options, selected = FinancialReportView.GUV, ariaLabel = tr("Berichtsart")) { view ->
            contentPanel.removeAll()
            when (view) {
                FinancialReportView.GUV -> renderIncomeStatementView(contentPanel)
                FinancialReportView.BILANZ -> renderBalanceSheetView(contentPanel)
                FinancialReportView.JAHRESABSCHLUSS -> renderAnnualFinancialStatementView(contentPanel)
                FinancialReportView.DATEV -> renderDatevExportView(contentPanel)
                FinancialReportView.ACCOUNTING_EXPORT -> renderAccountingExportView(contentPanel)
            }
        }
    }
    contentPanel = root.vPanel(spacing = 10)

    renderIncomeStatementView(contentPanel)
}

private enum class FinancialReportView { GUV, BILANZ, JAHRESABSCHLUSS, DATEV, ACCOUNTING_EXPORT }

/** Column headers of every statement-line table (GuV, Bilanz, Vier-Sphaeren details). */
private val STATEMENT_HEADERS =
    listOf(
        TableHeader(title = tr("Konto")),
        TableHeader(title = tr("Kontenklasse")),
        TableHeader(title = tr("Betrag"), numeric = true),
    )

private val KEY_FIGURE_HEADERS =
    listOf(
        TableHeader(title = tr("Kennzahl")),
        TableHeader(title = tr("Betrag"), numeric = true),
    )

// ============================================================================================
// DATEV-Export (Welle V1.4.5.2)
// ============================================================================================

/**
 * Modeless "Prüfen"-then-review flow (Raskin/Tesler: no dialog) -- both date fields are PFLICHT
 * (unlike the GuV/Kassenbuch filters' optional `from`), because [IAccountingService
 * .previewDatevExport]'s KDoc explains WHY: the DATEV `Belegdatum` field carries no year, so the
 * period must fit inside one calendar year. Pre-filled to Jan 1 of the current year through today,
 * same "show a meaningful first render" reasoning [renderIncomeStatementView] already applies.
 *
 * The download link/button is rendered but `disabled` (not hidden) while `!exportable` -- a
 * treasurer should see WHERE the export will eventually appear, not hunt for it once the period is
 * fixed. BOARD never sees the download control at all -- see [DatevAuthzUi.canDownload].
 */
private fun renderDatevExportView(panel: SimplePanel) {
    panel.h2(tr("DATEV-Buchungsstapel-Export"))
    val role = AppState.session?.role
    val filterControls = panel.dateRangeFilter(fromLabel = tr("Von (JJJJ-MM-TT)"), toLabel = tr("Bis (JJJJ-MM-TT)"))
    filterControls.fromInput.value = "${currentYear()}-01-01"
    filterControls.toInput.value = todayIso()
    val checkButton = panel.button(tr("Prüfen"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 8)

    fun check() {
        errorBox.hide()
        val from = filterControls.parseFrom()
        val to = filterControls.parseTo()
        if (from == null || to == null) {
            errorBox.content = tr("Bitte Von- und Bis-Datum angeben (JJJJ-MM-TT) -- beide sind für den DATEV-Export Pflicht.")
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p(tr("Wird geprüft …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val preview = guarded { rpcService<IAccountingService>().previewDatevExport(from, to) } ?: return@launch
            resultPanel.removeAll()
            renderDatevExportPreviewBody(resultPanel, preview, role)
        }
    }
    checkButton.onClick { check() }
    if (DatevAuthzUi.canPreview(role)) check()
}

private fun renderDatevExportPreviewBody(
    panel: SimplePanel,
    preview: DatevExportPreviewDto,
    role: AccountRole?,
) {
    panel.div(periodRangeCaption(preview.from, preview.to)) { addCssClasses("text-muted small") }

    val summaryRow = panel.hPanel(spacing = 16) { addCssClasses("flex-wrap") }
    summaryRow.div(gettext("Buchungen: %1", preview.entryCount))
    summaryRow.div(gettext("Zeilen: %1", preview.rowCount))
    summaryRow.div(gettext("Σ Soll: %1", formatMoney(preview.debitTotal)))
    summaryRow.div(gettext("Σ Haben: %1", formatMoney(preview.creditTotal)))
    preview.derivedSachkontenlaenge?.let { length ->
        summaryRow.div(gettext("Sachkontenlänge: %1", length))
    }

    if (preview.transliteratedEntryCount > 0) {
        panel.div(
            gettext(
                "%1 Buchungstext(e) werden für DATEV umgeschrieben (Sonderzeichen außerhalb des Windows-1252-Zeichensatzes).",
                preview.transliteratedEntryCount,
            ),
        ) { addCssClasses("text-muted small") }
    }
    if (preview.leadingZeroAccountCount > 0) {
        panel.div(
            tr(
                "Hinweis: mindestens eine Kontonummer im Zeitraum beginnt mit einer führenden Null -- " +
                    "prüfen Sie, dass Ihr Kontenrahmen beim Steuerberater ohne führende Nullen geführt wird.",
            ),
        ) { addCssClasses("text-warning small") }
    }

    if (preview.blockers.isNotEmpty()) {
        panel.p(tr("Dieser Zeitraum kann nicht exportiert werden:")) { addCssClasses("fw-bold text-danger") }
        preview.blockers.forEach { blocker ->
            val blockerBox = panel.div { addCssClasses("text-danger small mb-1") }
            blockerBox.div(datevExportBlockerLabel(blocker.kind)) { addCssClass("fw-bold") }
            blockerBox.div(blocker.detail)
        }
    }

    if (DatevAuthzUi.canDownload(role)) {
        // `Link` has no `disabled` state of its own (unlike a form Button) -- while the period is
        // NOT exportable, this renders the SAME caption as plain, non-clickable text instead of an
        // anchor, so a treasurer sees WHERE the download will appear without a dead link that would
        // just come back 409 if clicked.
        if (DatevAuthzUi.canDownloadNow(role, preview.exportable)) {
            panel.link(
                tr("Buchungsstapel herunterladen (.csv)"),
                url = DatevHttp.buchungsstapelUrl(preview.from, preview.to),
                target = "_blank",
            )
        } else {
            panel.div(tr("Buchungsstapel herunterladen (.csv)")) { addCssClasses("text-muted") }
        }
    }
}

// ============================================================================================
// GuV (Gewinn- und Verlustrechnung / Income Statement)
// ============================================================================================

private fun renderIncomeStatementView(panel: SimplePanel) {
    panel.h2(tr("Gewinn- und Verlustrechnung (GuV)"))
    val filterControls = panel.dateRangeFilter()
    // `to` is a required LocalDate server-side (unlike the Journal/Hauptbuch/Kassenbuch filters'
    // optional `to`) -- pre-filled to today so the first render shows a meaningful report instead
    // of an immediate validation error before the treasurer has touched anything.
    filterControls.toInput.value = todayIso()
    val loadButton = panel.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    // Welle V1.4.27 (W3): `dataSection` instead of "Wird geladen ..." forever on a failed load -- the failure is
    // now a visible error state with a retry (Lehre 7). The date validation stays BEFORE the reload in the click
    // handler: an invalid date must show its own message, not the generic "could not be loaded" alert.
    val section =
        panel.dataSection<IncomeStatementDto>(
            isEmpty = { false },
            load = {
                filterControls.parseTo()?.let { to ->
                    guarded { rpcService<IAccountingService>().getIncomeStatement(filterControls.parseFrom(), to) }
                }
            },
            render = { body, statement -> renderIncomeStatementBody(body, statement, captionVisible = false) },
        )

    fun load() {
        errorBox.hide()
        if (filterControls.parseTo() == null) {
            errorBox.content = tr("Bitte ein gültiges \"Bis\"-Datum angeben (JJJJ-MM-TT).")
            errorBox.show()
            return
        }
        section.reload()
    }
    loadButton.onClick { load() }
    load()
}

internal fun renderIncomeStatementBody(
    panel: SimplePanel,
    statement: IncomeStatementDto,
    captionVisible: Boolean = true,
) {
    panel.div(periodRangeCaption(statement.from, statement.to)) { addCssClasses("text-muted small") }
    // ONE table: Einnahmen and Ausgaben are section rows, the result a sum row IN the table (before: a freestanding
    // row under two tables whose columns did not line up with it).
    // [captionVisible] is `false` in the standalone view (its `h2` above the filters carries the same words, audit
    // MINOR-2) and `true` inside the Jahresabschluss, where the caption is the only name of the embedded table.
    val report =
        panel.reportTable(
            caption = tr("Gewinn- und Verlustrechnung (GuV)"),
            headers = STATEMENT_HEADERS,
            captionVisible = captionVisible,
        )
    report.reportRows(incomeStatementRows(statement), STATEMENT_HEADERS)
    resultQualifierLabel(isNegative(statement.result))?.let { qualifier ->
        panel.div(qualifier) { addCssClasses("text-danger small") }
    }
}

// ============================================================================================
// Bilanz (Balance Sheet)
// ============================================================================================

private fun renderBalanceSheetView(panel: SimplePanel) {
    panel.h2(tr("Bilanz"))
    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val asOfInput = filterRow.text(value = todayIso(), label = tr("Stichtag (JJJJ-MM-TT)"))
    val loadButton = filterRow.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    fun parseAsOf(): LocalDate? = runCatching { LocalDate.parse(asOfInput.value.orEmpty().trim()) }.getOrNull()
    val section =
        panel.dataSection<BalanceSheetDto>(
            isEmpty = { false },
            load = { parseAsOf()?.let { asOf -> guarded { rpcService<IAccountingService>().getBalanceSheet(asOf) } } },
            render = { body, sheet -> renderBalanceSheetBody(body, sheet, captionVisible = false) },
        )

    fun load() {
        errorBox.hide()
        if (parseAsOf() == null) {
            errorBox.content = tr("Bitte einen gültigen Stichtag angeben (JJJJ-MM-TT).")
            errorBox.show()
            return
        }
        section.reload()
    }
    loadButton.onClick { load() }
    load()
}

/**
 * [BalanceSheetDto.balanced] is guaranteed `true` by the server's own Σdebit = Σcredit invariant
 * (see that DTO's KDoc: "asserted as a regression guard") -- surfaced here as a visible sanity
 * badge anyway, per the plan, purely as an at-a-glance treasurer signal, not because this screen
 * doubts the figure.
 */
internal fun renderBalanceSheetBody(
    panel: SimplePanel,
    sheet: BalanceSheetDto,
    captionVisible: Boolean = true,
) {
    panel.div(gettext("Stichtag: %1", sheet.asOf)) { addCssClasses("text-muted small") }

    // ONE table: Aktiva, then Passiva with its two sub-sections, the accumulated result and both totals.
    val report = panel.reportTable(caption = tr("Bilanz"), headers = STATEMENT_HEADERS, captionVisible = captionVisible)
    report.reportRows(balanceSheetRows(sheet), STATEMENT_HEADERS)

    val balanceRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center mt-2") }
    balanceRow.div(tr("Summe Aktiva = Summe Passiva + Eigenkapital?")) { addCssClasses("flex-grow-1") }
    balanceRow.statusBadge(balancedLabel(sheet.balanced), balancedColor(sheet.balanced))
}

// ============================================================================================
// Jahresabschluss (Annual Financial Statement)
// ============================================================================================

private fun renderAnnualFinancialStatementView(panel: SimplePanel) {
    panel.h2(tr("Jahresabschluss"))
    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val filterControls = filterRow.fiscalYearFilter(currentYear = currentYear())
    val loadButton = filterRow.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val section =
        panel.dataSection<AnnualFinancialStatementDto>(
            isEmpty = { false },
            load = {
                filterControls.parseYear()?.let { fiscalYear ->
                    guarded { rpcService<IAccountingService>().getAnnualFinancialStatement(fiscalYear) }
                }
            },
            render = { body, statement -> renderAnnualFinancialStatementBody(body, statement) },
        )

    fun load() {
        errorBox.hide()
        if (filterControls.parseYear() == null) {
            errorBox.content = tr("Bitte ein gültiges Geschäftsjahr angeben (z. B. 2026).")
            errorBox.show()
            return
        }
        section.reload()
    }
    loadButton.onClick { load() }
    load()
}

/**
 * [AnnualFinancialStatementDto.periodResult] and [AnnualFinancialStatementDto.accumulatedResult]
 * are rendered as two distinct, separately-labeled rows -- per that DTO's own KDoc ("these
 * coincide in the very first fiscal year and legitimately diverge from the second year on"), this
 * screen must never merge them into one figure.
 */
internal fun renderAnnualFinancialStatementBody(
    panel: SimplePanel,
    statement: AnnualFinancialStatementDto,
) {
    panel.div(
        gettext("Geschäftsjahr %1 · %2 bis %3", statement.fiscalYear, statement.periodStart, statement.periodEnd),
    ) { addCssClasses("text-muted small") }

    // The embedded GuV/Bilanz are the very same bodies as the standalone views -- their table captions name them.
    renderIncomeStatementBody(panel, statement.incomeStatement)
    renderBalanceSheetBody(panel, statement.balanceSheet)

    val keyFigures = panel.reportTable(caption = tr("Kennzahlen"), headers = KEY_FIGURE_HEADERS)
    keyFigures.reportRows(annualKeyFigureRows(statement), KEY_FIGURE_HEADERS)

    panel.div(
        tr(
            "Diese beiden Werte sind im ersten Geschäftsjahr identisch und weichen ab dem zweiten Jahr " +
                "bewusst voneinander ab -- siehe Erläuterung im Datenmodell.",
        ),
    ) { addCssClasses("text-muted small") }
}

// ============================================================================================
// Shared statement-section table (StatementLineDto) -- the Vier-Sphaeren details reuse it
// ============================================================================================

/**
 * One statement section (income OR expense lines of one sphere) as its own [reportTable]: the caption is the
 * [title], the total row "Summe {title}". Deliberately not `private` -- `NonprofitComplianceReportsScreen.kt`'s
 * Vier-Sphaeren-Ergebnisrechnung expands each sphere row into exactly this rendering (design decision D7:
 * "literally the same StatementLineDto shape"), so it is reused rather than duplicated. [title] is a `tr(...)`
 * string and goes into the total label untouched ([trFormat], see the note in `ReportRows.kt`).
 */
internal fun renderStatementSectionTable(
    panel: Container,
    title: String,
    lines: List<StatementLineDto>,
    total: Decimal,
) {
    val report = panel.reportTable(caption = title, headers = STATEMENT_HEADERS)
    report.reportRows(statementSectionRows(title, lines, total, showSectionLabel = false), STATEMENT_HEADERS)
}

// ============================================================================================
// Pure helpers -- covered by FinancialReportsScreenTest.kt
// ============================================================================================

/**
 * `from == null` means "seit Gründung" server-side (see `IAccountingService.getIncomeStatement`
 * KDoc) -- rendered as plain German prose here rather than an empty/blank field, so the caption
 * never lets that meaning silently disappear into "Zeitraum: bis 2026-12-31".
 */
fun periodRangeCaption(
    from: LocalDate?,
    to: LocalDate,
): String = gettext("Zeitraum: %1 bis %2", from?.toString() ?: gettext("seit Gründung"), to)

/** D6: the only place this screen inspects a raw [Decimal]'s sign -- a **typed** numeric
 * comparison ([Decimal.toDouble] against `0.0`), never string/regex inspection of the rendered
 * text, matching [Money.kt]'s own rule for [moneySpan]'s `warnIfNegative` styling. */
fun isNegative(amount: Decimal): Boolean = amount.toDouble() < 0.0

/** Only ever called with a GuV/Jahresabschluss [IncomeStatementDto.result]/
 * [AnnualFinancialStatementDto.periodResult] -- a negative result there is specifically a
 * Jahresfehlbetrag (net loss), the one qualifier this screen adds beyond the bare figure. */
fun resultQualifierLabel(negative: Boolean): String? = if (negative) gettext("(Jahresfehlbetrag)") else null

fun balancedLabel(balanced: Boolean): String = if (balanced) gettext("Bilanz ausgeglichen") else gettext("Bilanz NICHT ausgeglichen")

fun balancedColor(balanced: Boolean): String = if (balanced) "success" else "danger"

/** Mirrors `LedgerScreen.kt`'s own private `todayIso()` -- no shared date-util file exists in this
 * client (each screen that needs "today as JJJJ-MM-TT" carries its own copy). */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

private fun currentYear(): Int =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date.year
