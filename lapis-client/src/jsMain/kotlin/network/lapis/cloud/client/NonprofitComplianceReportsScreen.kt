package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.ReserveMovementDto
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.SphereAmountDto
import network.lapis.cloud.shared.domain.SphereResultDto
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto
import network.lapis.cloud.shared.domain.UseOfFundsYearDto
import network.lapis.cloud.shared.rpc.IAccountingService
import kotlin.time.Clock

/**
 * Accounting UI wave, screen 3 of 5 -- "Gemeinnützigkeits-Berichte" (Vier-Sphären-Ergebnisrechnung
 * + Mittelverwendungsrechnung/Rücklagen), per the approved plan + UI/UX-Design-Team review on
 * `feature/accounting-ui`. See plan "Screen 3 -- NonprofitComplianceReportsScreen.kt" and design
 * decisions D4 (Mittelverwendungsrechnung's honest framing), D5/D6 (money formatting), D7
 * (four-sphere layout), D9 (`sphereLabel`/`sphereColor`/`reserveTypeLabel`/`reserveTypeColor` from
 * the shared `AccountingLabels.kt`).
 *
 * Purely read-only, same posture as `FinancialReportsScreen.kt`: `getFourSphereIncomeStatement`
 * and `getUseOfFundsStatement` are both `ACCOUNTING_READ_ROLES` (TREASURER/BOARD/ADMIN) server-side
 * with no mutating counterpart anywhere in this screen, matching `Routing.kt`'s route guard for
 * [Routes.COMPLIANCE_REPORTS] exactly -- no `canManage` split, a BOARD caller sees the identical
 * screen a TREASURER does.
 *
 * Every monetary figure below is a [Decimal] returned verbatim by `IAccountingService` and
 * rendered through [formatMoney]/[moneySpan] (`Money.kt`) -- this screen never parses, sums, or
 * re-rounds a figure the server has already computed. [hasOverdueAmount] and D6's own
 * `warnIfNegative` gate are the only two places this screen inspects a raw [Decimal]'s value at
 * all, and both do so via a **typed** numeric comparison ([Decimal.toDouble]), never string
 * inspection of the rendered text.
 *
 * D4's "Nachweis-Hilfe, keine automatisierte Compliance-Entscheidung" banner ([mittelverwendungsBannerText])
 * is rendered unconditionally, above the fiscal-year filter controls, before the first RPC call
 * even resolves (with `timelyUseYears = null` showing a loading placeholder instead of ever
 * guessing/hardcoding the figure) -- and is never hidden again once loaded, per Steve Jobs' final
 * review ("no 'don't show this again' checkbox... that tension doesn't go away").
 *
 * The Vier-Sphären-Ergebnisrechnung table reuses [renderStatementLineTable] from
 * `FinancialReportsScreen.kt` verbatim for each sphere's expanded income/expense detail (D7:
 * "literally the same StatementLineDto shape") -- see that file's own KDoc for why the function
 * was made non-private for this screen's sake.
 */
fun renderNonprofitComplianceReportsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("Gemeinnützigkeits-Berichte")

    val toggleRow = root.hPanel(spacing = 8)
    val fourSphereButton = toggleRow.button("Vier-Sphären-Ergebnisrechnung", style = ButtonStyle.OUTLINEPRIMARY)
    val useOfFundsButton = toggleRow.button("Mittelverwendungsrechnung", style = ButtonStyle.OUTLINEPRIMARY)
    val contentPanel = root.vPanel(spacing = 10)

    fourSphereButton.onClick {
        contentPanel.removeAll()
        renderFourSphereIncomeStatementView(contentPanel)
    }
    useOfFundsButton.onClick {
        contentPanel.removeAll()
        renderUseOfFundsView(contentPanel)
    }

    renderFourSphereIncomeStatementView(contentPanel)
}

// ============================================================================================
// Vier-Sphären-Ergebnisrechnung
// ============================================================================================

private fun renderFourSphereIncomeStatementView(panel: SimplePanel) {
    panel.h2("Vier-Sphären-Ergebnisrechnung")
    panel.div(
        "Re-Aggregation derselben Einnahmen/Ausgaben-Buchungen wie die GuV nach den vier " +
            "§§ 51-68 AO Gemeinnützigkeitssphären -- kein eigener Berichtszeitraum, die Summen " +
            "stimmen für denselben Zeitraum exakt mit der GuV überein.",
    ) { addCssClasses("text-muted small") }

    val filterControls = panel.dateRangeFilter()
    // `to` is a required LocalDate server-side (matches the GuV's own filter shape) -- pre-filled
    // to today so the first render already shows a meaningful report.
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
                guarded { rpcService<IAccountingService>().getFourSphereIncomeStatement(from, to) } ?: return@launch
            resultPanel.removeAll()
            renderFourSphereIncomeStatementBody(resultPanel, statement)
        }
    }
    loadButton.onClick { load() }
    load()
}

/**
 * D7: one table, sphere as leftmost column, [FourSphereIncomeStatementDto.spheres] rendered in
 * the exact order the server returned it (that DTO's own KDoc: "always exactly four ... in that
 * enum's declaration order") -- never re-sorted here.
 */
private fun renderFourSphereIncomeStatementBody(
    panel: SimplePanel,
    statement: FourSphereIncomeStatementDto,
) {
    panel.div(periodRangeCaption(statement.from, statement.to)) { addCssClasses("text-muted small") }

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div("Sphäre") { width = 260.px }
    headerRow.div("Einnahmen") { width = 120.px }
    headerRow.div("Ausgaben") { width = 120.px }
    headerRow.div("Ergebnis") { width = 120.px }
    headerRow.div("") { addCssClasses("flex-grow-1") }

    statement.spheres.forEach { sphere -> renderSphereRow(panel, sphere) }

    val footerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
    footerRow.div("Gesamt") { width = 260.px }
    footerRow.moneySpan(statement.totalIncome).width = 120.px
    footerRow.moneySpan(statement.totalExpense).width = 120.px
    footerRow.moneySpan(statement.result, warnIfNegative = true).width = 120.px
    footerRow.div("") { addCssClasses("flex-grow-1") }
}

/**
 * D7: each row expands in place (no separate detail screen) -- all four spheres are always
 * present, so an accordion keeps the fixed four-row layout intact while still offering the
 * underlying [network.lapis.cloud.shared.domain.StatementLineDto] line items on demand.
 */
private fun renderSphereRow(
    panel: SimplePanel,
    sphere: SphereResultDto,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border-bottom py-1") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val sphereCell = headerRow.div { width = 260.px }
    sphereCell.typeBadge(sphereLabel(sphere.sphere), sphereColor(sphere.sphere))
    headerRow.moneySpan(sphere.totalIncome).width = 120.px
    headerRow.moneySpan(sphere.totalExpense).width = 120.px
    headerRow.moneySpan(sphere.result, warnIfNegative = true).width = 120.px
    val toggleButton =
        headerRow.button("Details ein-/ausblenden", style = ButtonStyle.OUTLINESECONDARY) {
            addCssClasses("flex-grow-1")
        }

    val detailPanel = row.vPanel(spacing = 4) { hide() }
    var expanded = false
    toggleButton.onClick {
        expanded = !expanded
        if (expanded) {
            detailPanel.removeAll()
            renderStatementLineTable(detailPanel, "Einnahmen", sphere.incomeLines, sphere.totalIncome)
            renderStatementLineTable(detailPanel, "Ausgaben", sphere.expenseLines, sphere.totalExpense)
            detailPanel.show()
        } else {
            detailPanel.hide()
        }
    }
}

// ============================================================================================
// Mittelverwendungsrechnung (§55/§62 AO)
// ============================================================================================

private fun renderUseOfFundsView(panel: SimplePanel) {
    panel.h2("Mittelverwendungsrechnung (§55/§62 AO)")

    // D4: persistent, non-dismissible, above even the filter controls -- see file KDoc. Rendered
    // immediately with `timelyUseYears = null` (a loading placeholder, never a hardcoded "2")
    // and updated to the server's own value the instant the first load resolves.
    val bannerBox =
        panel.div(mittelverwendungsBannerText(null)) {
            addCssClasses("alert alert-warning")
        }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val fromYearControls = filterRow.fiscalYearFilter(currentYear = currentYear(), label = "Von (Geschäftsjahr)")
    val toYearControls = filterRow.fiscalYearFilter(currentYear = currentYear(), label = "Bis (Geschäftsjahr)")
    val loadButton = filterRow.button("Laden", style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 10)

    fun load() {
        errorBox.hide()
        val fromFiscalYear = fromYearControls.parseYear()
        val toFiscalYear = toYearControls.parseYear()
        if (fromFiscalYear == null || toFiscalYear == null || fromFiscalYear > toFiscalYear) {
            errorBox.content = "Bitte ein gültiges \"Von\"- und \"Bis\"-Geschäftsjahr angeben (Von ≤ Bis)."
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val statement =
                guarded { rpcService<IAccountingService>().getUseOfFundsStatement(fromFiscalYear, toFiscalYear) } ?: return@launch
            bannerBox.content = mittelverwendungsBannerText(statement.timelyUseYears)
            resultPanel.removeAll()
            renderUseOfFundsBody(resultPanel, statement)
        }
    }
    loadButton.onClick { load() }
    load()
}

private fun renderUseOfFundsBody(
    panel: SimplePanel,
    statement: UseOfFundsStatementDto,
) {
    panel.div(useOfFundsPeriodCaption(statement.fromFiscalYear, statement.toFiscalYear)) {
        addCssClasses("text-muted small")
    }

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div("Geschäftsjahr") { width = 100.px }
    headerRow.div("Mittelzufluss") { width = 110.px }
    headerRow.div("Mittelverwendung") { width = 130.px }
    headerRow.div("Rücklagenzuführung") { width = 140.px }
    headerRow.div("Mittelvortrag") { width = 110.px }
    headerRow.div("davon überfällig") { width = 110.px }
    headerRow.div("") { addCssClasses("flex-grow-1") }

    if (statement.years.isEmpty()) {
        panel.p("Keine Buchungen im gewählten Zeitraum.") { addCssClasses("text-muted small") }
    }
    // Never re-sorted -- [UseOfFundsStatementDto.years] KDoc: "one UseOfFundsYearDto per fiscal
    // year in [fromFiscalYear, toFiscalYear]", already in that order.
    statement.years.forEach { year -> renderUseOfFundsYearRow(panel, year) }

    val totalRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
    totalRow.div("Gesamt") { width = 100.px }
    totalRow.moneySpan(statement.totalFundsReceived).width = 110.px
    totalRow.moneySpan(statement.totalFundsUsed).width = 130.px
    totalRow.moneySpan(statement.totalFundsAllocatedToReserves, warnIfNegative = true).width = 140.px
    totalRow.moneySpan(statement.closingTimelyUseObligation).width = 110.px
    totalRow.moneySpan(statement.closingOverdue).width = 110.px
    totalRow.div("") { addCssClasses("flex-grow-1") }

    panel.div(
        "„Mittelvortrag\" ist der am Ende von ${statement.toFiscalYear} verbleibende §55-AO-" +
            "Zeitwert-Topf (die Fristablauf-Uhr läuft seit dem frühesten Geschäftsjahr mit " +
            "Aktivität, nicht erst ab ${statement.fromFiscalYear}) -- „davon überfällig\" ist der " +
            "Anteil, dessen gesetzliche Frist bereits abgelaufen ist.",
    ) { addCssClasses("text-muted small") }
}

/** D4's `FREIE_RUECKLAGE` inline caveat, plus an expand-in-place detail panel with the
 * reserve-movements and (informational, D-plan item 3) per-sphere disaggregation. */
private fun renderUseOfFundsYearRow(
    panel: SimplePanel,
    year: UseOfFundsYearDto,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border-bottom py-1") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(year.fiscalYear.toString()) { width = 100.px }
    headerRow.moneySpan(year.fundsReceived).width = 110.px
    headerRow.moneySpan(year.fundsUsed).width = 130.px
    headerRow.moneySpan(year.fundsAllocatedToReserves, warnIfNegative = true).width = 140.px
    headerRow.moneySpan(year.timelyUseObligationRemaining).width = 110.px
    val overdueSpan = headerRow.moneySpan(year.overdueAmount)
    overdueSpan.width = 110.px
    if (hasOverdueAmount(year.overdueAmount)) overdueSpan.addCssClass("text-danger fw-bold")
    val toggleButton =
        headerRow.button("Details ein-/ausblenden", style = ButtonStyle.OUTLINESECONDARY) {
            addCssClasses("flex-grow-1")
        }

    val detailPanel = row.vPanel(spacing = 8) { hide() }
    var expanded = false
    toggleButton.onClick {
        expanded = !expanded
        if (expanded) {
            detailPanel.removeAll()
            renderReserveMovementsTable(detailPanel, year.reserveMovements)
            renderSphereAmountTable(detailPanel, "Mittelzufluss nach Sphäre (informativ)", year.receivedBySphere)
            renderSphereAmountTable(detailPanel, "Mittelverwendung nach Sphäre (informativ)", year.usedBySphere)
            detailPanel.show()
        } else {
            detailPanel.hide()
        }
    }
}

/** D4: the `FREIE_RUECKLAGE` row gets its own inline "(gesetzliche Obergrenze hier nicht
 * geprüft)" caveat -- Norman's "constraints visible at point of use", not just once in the
 * top banner. */
private fun renderReserveMovementsTable(
    panel: SimplePanel,
    movements: List<ReserveMovementDto>,
) {
    panel.p("Rücklagenbewegungen (§62 AO)") { addCssClasses("fw-bold small") }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1 small") }
    headerRow.div("Rücklagenart") { addCssClasses("flex-grow-1") }
    headerRow.div("Zuführung/Auflösung") { width = 140.px }
    headerRow.div("Schlussstand") { width = 120.px }

    movements.forEach { movement ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        val labelCell = row.div { addCssClasses("flex-grow-1") }
        labelCell.typeBadge(reserveTypeLabel(movement.reserveType), reserveTypeColor(movement.reserveType))
        row.moneySpan(movement.allocated, warnIfNegative = true).width = 140.px
        row.moneySpan(movement.closingBalance).width = 120.px

        if (movement.reserveType == ReserveType.FREIE_RUECKLAGE) {
            panel.div("(gesetzliche Obergrenze hier nicht geprüft)") { addCssClasses("text-muted small ps-2") }
        }
    }
}

private fun renderSphereAmountTable(
    panel: SimplePanel,
    title: String,
    amounts: List<SphereAmountDto>,
) {
    panel.p(title) { addCssClasses("fw-bold small") }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("border-bottom pb-1 small") }
    headerRow.div("Sphäre") { addCssClasses("flex-grow-1") }
    headerRow.div("Betrag") { width = 120.px }

    amounts.forEach { entry ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        val labelCell = row.div { addCssClasses("flex-grow-1") }
        labelCell.typeBadge(sphereLabel(entry.sphere), sphereColor(entry.sphere))
        row.moneySpan(entry.amount).width = 120.px
    }
}

// ============================================================================================
// Pure helpers -- covered by NonprofitComplianceReportsScreenTest.kt
// ============================================================================================

/**
 * D4: the exact required banner copy, with `{timelyUseYears}` interpolated live from
 * [UseOfFundsStatementDto.timelyUseYears] -- never a hardcoded "2". [timelyUseYears] is `null`
 * only in the brief window between this view's initial render and the first RPC response
 * resolving, in which case an ellipsis placeholder is shown instead of ever guessing a number.
 */
fun mittelverwendungsBannerText(timelyUseYears: Int?): String {
    val years = timelyUseYears?.toString() ?: "…"
    return "Diese Auswertung ist eine Nachweis-Hilfe für den Vorstand nach §§ 55/62 AO -- keine " +
        "automatisierte Compliance-Entscheidung. Sie prüft nicht die Freie-Rücklage-Obergrenze, " +
        "wendet nicht automatisch die Kleinorganisationen-Ausnahme (≤ 45.000 € gemäß § 55 Abs. 1 " +
        "Nr. 5 Satz 4 AO) an und bestätigt nicht den Fortbestand der Gemeinnützigkeit. Die " +
        "verwendete Frist von $years Jahren ist gegen die aktuelle AO-Auslegung zu prüfen."
}

fun useOfFundsPeriodCaption(
    fromFiscalYear: Int,
    toFiscalYear: Int,
): String = "Zeitraum: Geschäftsjahr $fromFiscalYear bis $toFiscalYear"

/** The only place besides D6's own `warnIfNegative` gate where this screen inspects a raw
 * [Decimal]'s value -- a **typed** numeric comparison ([Decimal.toDouble] against `0.0`), never
 * string/regex inspection of the rendered text, matching [isNegative]'s (`FinancialReportsScreen.kt`)
 * own rule. [UseOfFundsYearDto.overdueAmount] is documented as never negative, so this checks for
 * "positive" (i.e. there IS an overdue amount), not "negative". */
fun hasOverdueAmount(amount: Decimal): Boolean = amount.toDouble() > 0.0

/** Mirrors `LedgerScreen.kt`/`FinancialReportsScreen.kt`'s own private `todayIso()` -- no shared
 * date-util file exists in this client. */
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
