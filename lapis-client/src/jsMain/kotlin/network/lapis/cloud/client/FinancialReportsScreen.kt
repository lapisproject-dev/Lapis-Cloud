package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AnnualFinancialStatementDto
import network.lapis.cloud.shared.domain.BalanceSheetDto
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
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("Finanzberichte")

    val toggleRow = root.hPanel(spacing = 8)
    val guvButton = toggleRow.button("GuV", style = ButtonStyle.OUTLINEPRIMARY)
    val bilanzButton = toggleRow.button("Bilanz", style = ButtonStyle.OUTLINEPRIMARY)
    val jahresabschlussButton = toggleRow.button("Jahresabschluss", style = ButtonStyle.OUTLINEPRIMARY)
    val contentPanel = root.vPanel(spacing = 10)

    guvButton.onClick {
        contentPanel.removeAll()
        renderIncomeStatementView(contentPanel)
    }
    bilanzButton.onClick {
        contentPanel.removeAll()
        renderBalanceSheetView(contentPanel)
    }
    jahresabschlussButton.onClick {
        contentPanel.removeAll()
        renderAnnualFinancialStatementView(contentPanel)
    }

    renderIncomeStatementView(contentPanel)
}

// ============================================================================================
// GuV (Gewinn- und Verlustrechnung / Income Statement)
// ============================================================================================

private fun renderIncomeStatementView(panel: SimplePanel) {
    panel.h2("Gewinn- und Verlustrechnung (GuV)")
    val filterControls = panel.dateRangeFilter()
    // `to` is a required LocalDate server-side (unlike the Journal/Hauptbuch/Kassenbuch filters'
    // optional `to`) -- pre-filled to today so the first render shows a meaningful report instead
    // of an immediate validation error before the treasurer has touched anything.
    filterControls.toInput.value = todayIso()
    val loadButton = panel.button("Laden", style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 8)

    fun load() {
        errorBox.hide()
        val to = filterControls.parseTo()
        if (to == null) {
            errorBox.content = "Bitte ein gültiges \"Bis\"-Datum angeben (JJJJ-MM-TT)."
            errorBox.show()
            return
        }
        val from = filterControls.parseFrom()
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val statement =
                guarded { rpcService<IAccountingService>().getIncomeStatement(from, to) } ?: return@launch
            resultPanel.removeAll()
            renderIncomeStatementBody(resultPanel, statement)
        }
    }
    loadButton.onClick { load() }
    load()
}

private fun renderIncomeStatementBody(
    panel: SimplePanel,
    statement: IncomeStatementDto,
) {
    panel.div(periodRangeCaption(statement.from, statement.to)) { addCssClasses("text-muted small") }
    renderStatementLineTable(panel, "Einnahmen", statement.incomeLines, statement.totalIncome)
    renderStatementLineTable(panel, "Ausgaben", statement.expenseLines, statement.totalExpense)

    val negative = isNegative(statement.result)
    val resultRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-2 align-items-center") }
    resultRow.div("Ergebnis") { addCssClasses("flex-grow-1") }
    resultRow.moneySpan(statement.result, warnIfNegative = true).width = 130.px
    resultQualifierLabel(negative)?.let { qualifier ->
        panel.div(qualifier) { addCssClasses("text-danger small") }
    }
}

// ============================================================================================
// Bilanz (Balance Sheet)
// ============================================================================================

private fun renderBalanceSheetView(panel: SimplePanel) {
    panel.h2("Bilanz")
    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val asOfInput = filterRow.text(value = todayIso(), label = "Stichtag (JJJJ-MM-TT)")
    val loadButton = filterRow.button("Laden", style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 8)

    fun load() {
        errorBox.hide()
        val asOf = runCatching { LocalDate.parse(asOfInput.value.orEmpty().trim()) }.getOrNull()
        if (asOf == null) {
            errorBox.content = "Bitte einen gültigen Stichtag angeben (JJJJ-MM-TT)."
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val sheet = guarded { rpcService<IAccountingService>().getBalanceSheet(asOf) } ?: return@launch
            resultPanel.removeAll()
            renderBalanceSheetBody(resultPanel, sheet)
        }
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
private fun renderBalanceSheetBody(
    panel: SimplePanel,
    sheet: BalanceSheetDto,
) {
    panel.div("Stichtag: ${sheet.asOf}") { addCssClasses("text-muted small") }

    panel.p("Aktiva") { addCssClass("fw-bold") }
    renderStatementLineTable(panel, "Aktiva", sheet.assetLines, sheet.totalAssets, showSectionLabel = false)

    panel.p("Passiva") { addCssClass("fw-bold") }
    renderStatementLineTable(
        panel,
        "Verbindlichkeiten",
        sheet.liabilityLines,
        sheet.totalLiabilities,
    )
    renderStatementLineTable(
        panel,
        "Eigenkapital (gebucht)",
        sheet.equityLines,
        sheet.bookedEquity,
    )
    val accumulatedRow = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
    accumulatedRow.div("Kumuliertes Ergebnis (Σ Einnahmen − Ausgaben seit Gründung)") { addCssClasses("flex-grow-1 fst-italic") }
    accumulatedRow.moneySpan(sheet.accumulatedResult, warnIfNegative = true).width = 130.px

    val totalPassivaRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1 align-items-center") }
    totalPassivaRow.div("Summe Passiva + Eigenkapital") { addCssClasses("flex-grow-1") }
    totalPassivaRow.moneySpan(sheet.totalEquityAndLiabilities).width = 130.px

    val balanceRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center mt-2") }
    balanceRow.div("Summe Aktiva = Summe Passiva + Eigenkapital?") { addCssClasses("flex-grow-1") }
    balanceRow.statusBadge(balancedLabel(sheet.balanced), balancedColor(sheet.balanced))
}

// ============================================================================================
// Jahresabschluss (Annual Financial Statement)
// ============================================================================================

private fun renderAnnualFinancialStatementView(panel: SimplePanel) {
    panel.h2("Jahresabschluss")
    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val filterControls = filterRow.fiscalYearFilter(currentYear = currentYear())
    val loadButton = filterRow.button("Laden", style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 10)

    fun load() {
        errorBox.hide()
        val fiscalYear = filterControls.parseYear()
        if (fiscalYear == null) {
            errorBox.content = "Bitte ein gültiges Geschäftsjahr angeben (z. B. 2026)."
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val statement =
                guarded { rpcService<IAccountingService>().getAnnualFinancialStatement(fiscalYear) } ?: return@launch
            resultPanel.removeAll()
            renderAnnualFinancialStatementBody(resultPanel, statement)
        }
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
private fun renderAnnualFinancialStatementBody(
    panel: SimplePanel,
    statement: AnnualFinancialStatementDto,
) {
    panel.div(
        "Geschäftsjahr ${statement.fiscalYear} · ${statement.periodStart} bis ${statement.periodEnd}",
    ) { addCssClasses("text-muted small") }

    panel.p("Gewinn- und Verlustrechnung") { addCssClass("fw-bold") }
    renderIncomeStatementBody(panel, statement.incomeStatement)

    panel.p("Bilanz") { addCssClass("fw-bold") }
    renderBalanceSheetBody(panel, statement.balanceSheet)

    panel.p("Kennzahlen") { addCssClass("fw-bold") }
    val periodResultRow = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
    periodResultRow.div("Jahresergebnis (dieses Geschäftsjahr)") { addCssClasses("flex-grow-1") }
    periodResultRow.moneySpan(statement.periodResult, warnIfNegative = true).width = 130.px

    val accumulatedResultRow = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
    accumulatedResultRow.div("Kumuliertes Ergebnis (seit Gründung)") { addCssClasses("flex-grow-1") }
    accumulatedResultRow.moneySpan(statement.accumulatedResult, warnIfNegative = true).width = 130.px

    panel.div(
        "Diese beiden Werte sind im ersten Geschäftsjahr identisch und weichen ab dem zweiten Jahr " +
            "bewusst voneinander ab -- siehe Erläuterung im Datenmodell.",
    ) { addCssClasses("text-muted small") }
}

// ============================================================================================
// Shared line-table rendering (StatementLineDto) -- used by GuV/Bilanz and their Jahresabschluss
// embedding
// ============================================================================================

/**
 * [showSectionLabel] suppresses the "Summe {title}" footer wording only for the Bilanz's Aktiva
 * section, whose own `panel.p("Aktiva")` heading is rendered by the caller one line above and
 * would otherwise be immediately followed by a redundant "Summe Aktiva" section title -- the
 * footer row itself (with the actual total) is always rendered regardless.
 *
 * Deliberately not `private` -- `NonprofitComplianceReportsScreen.kt`'s Vier-Sphären-
 * Ergebnisrechnung expands each sphere row into exactly this same `StatementLineDto`
 * income/expense rendering (design decision D7: "literally the same StatementLineDto shape"),
 * so it is reused here rather than duplicated.
 */
fun renderStatementLineTable(
    panel: SimplePanel,
    title: String,
    lines: List<StatementLineDto>,
    total: Decimal,
    showSectionLabel: Boolean = true,
) {
    if (showSectionLabel) {
        panel.p(title) { addCssClass("fw-bold") }
    }
    if (lines.isEmpty()) {
        panel.p("Keine Buchungen in diesem Abschnitt.") { addCssClasses("text-muted small") }
    } else {
        val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
        headerRow.div("Konto") { addCssClasses("flex-grow-1") }
        headerRow.div("Kontenklasse") { width = 110.px }
        headerRow.div("Betrag") { width = 130.px }
        lines.sortedBy { it.accountNumber }.forEach { line ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1") }
            row.div("${line.accountNumber} · ${line.name}") { addCssClasses("flex-grow-1") }
            row.div(line.accountClass.toString()) { width = 110.px }
            row.div(formatMoney(line.balance)) { width = 130.px }
        }
    }
    val totalRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
    totalRow.div("Summe $title") { addCssClasses("flex-grow-1") }
    totalRow.div("") { width = 110.px }
    totalRow.div(formatMoney(total)) { width = 130.px }
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
): String = "Zeitraum: ${from?.toString() ?: "seit Gründung"} bis $to"

/** D6: the only place this screen inspects a raw [Decimal]'s sign -- a **typed** numeric
 * comparison ([Decimal.toDouble] against `0.0`), never string/regex inspection of the rendered
 * text, matching [Money.kt]'s own rule for [moneySpan]'s `warnIfNegative` styling. */
fun isNegative(amount: Decimal): Boolean = amount.toDouble() < 0.0

/** Only ever called with a GuV/Jahresabschluss [IncomeStatementDto.result]/
 * [AnnualFinancialStatementDto.periodResult] -- a negative result there is specifically a
 * Jahresfehlbetrag (net loss), the one qualifier this screen adds beyond the bare figure. */
fun resultQualifierLabel(negative: Boolean): String? = if (negative) "(Jahresfehlbetrag)" else null

fun balancedLabel(balanced: Boolean): String = if (balanced) "Bilanz ausgeglichen" else "Bilanz NICHT ausgeglichen"

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
