package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Overflow
import io.kvision.form.check.checkBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.ReserveMovementDto
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.SphereAmountDto
import network.lapis.cloud.shared.domain.SphereResultDto
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto
import network.lapis.cloud.shared.domain.UseOfFundsYearDto
import network.lapis.cloud.shared.domain.VatComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.VatComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.VatFilingPeriodicity
import network.lapis.cloud.shared.domain.VatNotApplicableReason
import network.lapis.cloud.shared.domain.VatRateLineDto
import network.lapis.cloud.shared.domain.VatReturnPreviewDto
import network.lapis.cloud.shared.domain.VatSettingsDto
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IVatService
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
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Gemeinnützigkeits-Berichte"))

    val toggleRow = root.hPanel(spacing = 8)
    val fourSphereButton = toggleRow.button(tr("Vier-Sphären-Ergebnisrechnung"), style = ButtonStyle.OUTLINEPRIMARY)
    val useOfFundsButton = toggleRow.button(tr("Mittelverwendungsrechnung"), style = ButtonStyle.OUTLINEPRIMARY)
    // Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)".
    val vatReturnButton = toggleRow.button(tr("USt-Voranmeldung — Vorschau"), style = ButtonStyle.OUTLINEPRIMARY)
    val contentPanel = root.vPanel(spacing = 10)

    fourSphereButton.onClick {
        contentPanel.removeAll()
        renderFourSphereIncomeStatementView(contentPanel)
    }
    useOfFundsButton.onClick {
        contentPanel.removeAll()
        renderUseOfFundsView(contentPanel)
    }
    vatReturnButton.onClick {
        contentPanel.removeAll()
        renderVatReturnPreviewView(contentPanel)
    }

    renderFourSphereIncomeStatementView(contentPanel)
}

// ============================================================================================
// Vier-Sphären-Ergebnisrechnung
// ============================================================================================

private fun renderFourSphereIncomeStatementView(panel: SimplePanel) {
    panel.h2(tr("Vier-Sphären-Ergebnisrechnung"))
    panel.div(
        tr(
            "Re-Aggregation derselben Einnahmen/Ausgaben-Buchungen wie die GuV nach den vier " +
                "§§ 51-68 AO Gemeinnützigkeitssphären -- kein eigener Berichtszeitraum, die Summen " +
                "stimmen für denselben Zeitraum exakt mit der GuV überein.",
        ),
    ) { addCssClasses("text-muted small") }

    val filterControls = panel.dateRangeFilter()
    // `to` is a required LocalDate server-side (matches the GuV's own filter shape) -- pre-filled
    // to today so the first render already shows a meaningful report.
    filterControls.toInput.value = todayIso()
    val loadButton = panel.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
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
            errorBox.content = tr("Bitte ein gültiges \"Bis\"-Datum angeben (JJJJ-MM-TT).")
            errorBox.show()
            return
        }
        val from = filterControls.parseFrom()
        resultPanel.removeAll()
        resultPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
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
    headerRow.div(tr("Sphäre")) { width = 260.px }
    headerRow.div(tr("Einnahmen")) { width = 120.px }
    headerRow.div(tr("Ausgaben")) { width = 120.px }
    headerRow.div(tr("Ergebnis")) { width = 120.px }
    headerRow.div("") { addCssClasses("flex-grow-1") }

    statement.spheres.forEach { sphere -> renderSphereRow(panel, sphere) }

    val footerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
    footerRow.div(tr("Gesamt")) { width = 260.px }
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
        headerRow.button(tr("Details ein-/ausblenden"), style = ButtonStyle.OUTLINESECONDARY) {
            addCssClasses("flex-grow-1")
        }

    val detailPanel = row.vPanel(spacing = 4) { hide() }
    var expanded = false
    toggleButton.onClick {
        expanded = !expanded
        if (expanded) {
            detailPanel.removeAll()
            renderStatementLineTable(detailPanel, tr("Einnahmen"), sphere.incomeLines, sphere.totalIncome)
            renderStatementLineTable(detailPanel, tr("Ausgaben"), sphere.expenseLines, sphere.totalExpense)
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
    panel.h2(tr("Mittelverwendungsrechnung (§55/§62 AO)"))

    // D4: persistent, non-dismissible, above even the filter controls -- see file KDoc. Rendered
    // immediately with `timelyUseYears = null` (a loading placeholder, never a hardcoded "2")
    // and updated to the server's own value the instant the first load resolves.
    val bannerBox =
        panel.div(mittelverwendungsBannerText(null)) {
            addCssClasses("alert alert-warning")
        }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val fromYearControls = filterRow.fiscalYearFilter(currentYear = currentYear(), label = tr("Von (Geschäftsjahr)"))
    val toYearControls = filterRow.fiscalYearFilter(currentYear = currentYear(), label = tr("Bis (Geschäftsjahr)"))
    val loadButton = filterRow.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
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
            errorBox.content = tr("Bitte ein gültiges \"Von\"- und \"Bis\"-Geschäftsjahr angeben (Von ≤ Bis).")
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
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
    headerRow.div(tr("Geschäftsjahr")) { width = 100.px }
    headerRow.div(tr("Mittelzufluss")) { width = 110.px }
    headerRow.div(tr("Mittelverwendung")) { width = 130.px }
    headerRow.div(tr("Rücklagenzuführung")) { width = 140.px }
    headerRow.div(tr("Mittelvortrag")) { width = 110.px }
    headerRow.div(tr("davon überfällig")) { width = 110.px }
    headerRow.div("") { addCssClasses("flex-grow-1") }

    if (statement.years.isEmpty()) {
        panel.p(tr("Keine Buchungen im gewählten Zeitraum.")) { addCssClasses("text-muted small") }
    }
    // Never re-sorted -- [UseOfFundsStatementDto.years] KDoc: "one UseOfFundsYearDto per fiscal
    // year in [fromFiscalYear, toFiscalYear]", already in that order.
    statement.years.forEach { year -> renderUseOfFundsYearRow(panel, year) }

    val totalRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
    totalRow.div(tr("Gesamt")) { width = 100.px }
    totalRow.moneySpan(statement.totalFundsReceived).width = 110.px
    totalRow.moneySpan(statement.totalFundsUsed).width = 130.px
    totalRow.moneySpan(statement.totalFundsAllocatedToReserves, warnIfNegative = true).width = 140.px
    totalRow.moneySpan(statement.closingTimelyUseObligation).width = 110.px
    totalRow.moneySpan(statement.closingOverdue).width = 110.px
    totalRow.div("") { addCssClasses("flex-grow-1") }

    panel.div(
        gettext(
            "„Mittelvortrag\" ist der am Ende von %1 verbleibende §55-AO-Zeitwert-Topf (die " +
                "Fristablauf-Uhr läuft seit dem frühesten Geschäftsjahr mit Aktivität, nicht erst ab " +
                "%2) -- „davon überfällig\" ist der Anteil, dessen gesetzliche Frist bereits " +
                "abgelaufen ist.",
            statement.toFiscalYear,
            statement.fromFiscalYear,
        ),
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
    if (hasOverdueAmount(year.overdueAmount)) overdueSpan.addCssClasses("text-danger fw-bold")
    val toggleButton =
        headerRow.button(tr("Details ein-/ausblenden"), style = ButtonStyle.OUTLINESECONDARY) {
            addCssClasses("flex-grow-1")
        }

    val detailPanel = row.vPanel(spacing = 8) { hide() }
    var expanded = false
    toggleButton.onClick {
        expanded = !expanded
        if (expanded) {
            detailPanel.removeAll()
            renderReserveMovementsTable(detailPanel, year.reserveMovements)
            renderSphereAmountTable(detailPanel, tr("Mittelzufluss nach Sphäre (informativ)"), year.receivedBySphere)
            renderSphereAmountTable(detailPanel, tr("Mittelverwendung nach Sphäre (informativ)"), year.usedBySphere)
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
    panel.p(tr("Rücklagenbewegungen (§62 AO)")) { addCssClasses("fw-bold small") }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1 small") }
    headerRow.div(tr("Rücklagenart")) { addCssClasses("flex-grow-1") }
    headerRow.div(tr("Zuführung/Auflösung")) { width = 140.px }
    headerRow.div(tr("Schlussstand")) { width = 120.px }

    movements.forEach { movement ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        val labelCell = row.div { addCssClasses("flex-grow-1") }
        labelCell.typeBadge(reserveTypeLabel(movement.reserveType), reserveTypeColor(movement.reserveType))
        row.moneySpan(movement.allocated, warnIfNegative = true).width = 140.px
        row.moneySpan(movement.closingBalance).width = 120.px

        if (movement.reserveType == ReserveType.FREIE_RUECKLAGE) {
            panel.div(tr("(gesetzliche Obergrenze hier nicht geprüft)")) { addCssClasses("text-muted small ps-2") }
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
    headerRow.div(tr("Sphäre")) { addCssClasses("flex-grow-1") }
    headerRow.div(tr("Betrag")) { width = 120.px }

    amounts.forEach { entry ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        val labelCell = row.div { addCssClasses("flex-grow-1") }
        labelCell.typeBadge(sphereLabel(entry.sphere), sphereColor(entry.sphere))
        row.moneySpan(entry.amount).width = 120.px
    }
}

// ============================================================================================
// USt-Voranmeldung (Nachweishilfe) -- Welle V1.4.13
// ============================================================================================

private fun renderVatReturnPreviewView(panel: SimplePanel) {
    panel.h2(tr("USt-Voranmeldung — Vorschau (Nachweishilfe)"))
    panel.div(
        tr(
            "Keine Übermittlung an ELSTER. Diese Ansicht ist eine Nachweishilfe aus in Lapis Cloud " +
                "erfassten Buchungen für Vorstand und Steuerberatung -- keine Voranmeldung im Sinne des § 18 UStG.",
        ),
    ) { addCssClasses("alert alert-warning") }

    if (AppState.hasRole(AccountRole.ADMIN)) {
        renderVatAdminGateSection(panel)
    }

    // Both bounds required -- see load()'s own comment. fromLabel overridden to drop the shared
    // helper's default "(optional)" suffix, which would be misleading here.
    val filterControls = panel.dateRangeFilter(fromLabel = tr("Von (JJJJ-MM-TT)"))
    filterControls.fromInput.value = "${currentYear()}-01-01"
    filterControls.toInput.value = todayIso()
    val loadButton = panel.button(tr("Berechnen"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 8)

    fun load() {
        errorBox.hide()
        // getVatReturnPreview requires BOTH bounds (unlike listJournal/getFourSphereIncomeStatement's
        // optional `from` -- "seit Gründung" has no defensible meaning for a UStVA-shaped period,
        // which is always a specific Voranmeldungszeitraum, never an open-ended one).
        val to = filterControls.parseTo()
        val from = filterControls.parseFrom()
        if (to == null || from == null) {
            errorBox.content = tr("Bitte ein gültiges \"Von\"- und \"Bis\"-Datum angeben (JJJJ-MM-TT).")
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val preview =
                guarded { rpcService<IAccountingService>().getVatReturnPreview(from = from, to = to) } ?: return@launch
            resultPanel.removeAll()
            renderVatReturnPreviewBody(resultPanel, preview)
        }
    }
    loadButton.onClick { load() }
    load()
}

/**
 * ADMIN-only USt-Gate (aktivieren/deaktivieren), analog zum Aufbau in `DunningSettingsScreen.kt`
 * (Status-Zeile + Disclaimer-Modal vor `enableVat`). Bewusst DIREKT in dieser Ansicht platziert
 * (nicht auf einem eigenen Screen), weil es hier -- und nur hier -- einen konkreten Kontext gibt,
 * in dem die Aktivierung sofort etwas verändert.
 */
private fun renderVatAdminGateSection(root: SimplePanel) {
    val gatePanel = root.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    gatePanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadSettings() {
        gatePanel.removeAll()
        gatePanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<IVatService>().getVatSettings() } ?: return@launch
            gatePanel.removeAll()
            renderVatGateSummary(gatePanel, settings, onChanged = ::loadSettings)
        }
    }
    loadSettings()
}

private fun renderVatGateSummary(
    panel: SimplePanel,
    settings: VatSettingsDto,
    onChanged: () -> Unit,
) {
    val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("USt-Modul:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (settings.vatEnabled) tr("Aktiviert") else tr("Deaktiviert"),
        if (settings.vatEnabled) "success" else "secondary",
    )
    if (settings.vatEnabled && settings.isKleinunternehmer) {
        statusRow.div(tr("(Kleinunternehmer -- Berechnung bleibt ausgesetzt)")) { addCssClasses("text-muted small") }
    }

    // Review MINOR fix (V1.4.13 follow-up): isKleinunternehmer had no client Bedienpfad at all --
    // ADMIN-writable server-side (OrganizationSettingsService.updateOrganizationSettings treats it
    // as an "ordinary ADMIN-writable configuration field", NOT a disclaimer-gated feature switch
    // like vatEnabled/auctionEnabled -- see that method's own inline comment) but only reachable via
    // direct DB/RPC access, so a Kleinunternehmer org that activated the USt-Modul via the button
    // below had no way to record its own §19-UStG status and got a real Zahllast-Vorschau instead
    // of the "Berechnung bleibt ausgesetzt" short-circuit. Wired here, next to the status text it
    // already controls, via the SAME generic wholesale-replace path (IOrganizationSettingsService.
    // updateOrganizationSettings) LedgerScreen.kt/PoliticianScreen.kt already use for their own
    // "ordinary tier" fields -- no disclaimer needed, this is a factual statement the org makes
    // about itself, not a risk-bearing feature gate.
    val kleinunternehmerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center mt-1") }
    val kleinunternehmerToggle =
        kleinunternehmerRow.checkBox(value = settings.isKleinunternehmer, label = tr("Kleinunternehmer nach § 19 UStG"))
    kleinunternehmerToggle.onClick {
        val newValue = kleinunternehmerToggle.value == true
        kleinunternehmerToggle.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    val orgSettings = rpcService<IOrganizationSettingsService>().getOrganizationSettings()
                    rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                        orgSettings.toInputWithKleinunternehmerFlag(newValue),
                    )
                }
            kleinunternehmerToggle.disabled = false
            if (result != null) {
                notifySuccess(
                    if (newValue) {
                        tr("Kleinunternehmer-Status gesetzt.")
                    } else {
                        tr("Kleinunternehmer-Status entfernt.")
                    },
                )
                onChanged()
            } else {
                kleinunternehmerToggle.value = !newValue
            }
        }
    }

    val actionsRow = panel.hPanel(spacing = 8) { addCssClasses("mt-1") }
    if (settings.vatEnabled) {
        val disableButton = actionsRow.button(tr("USt-Modul deaktivieren"), style = ButtonStyle.OUTLINEDANGER)
        disableButton.onClick {
            disableButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IVatService>().disableVat() }
                disableButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("USt-Modul deaktiviert."))
                    onChanged()
                }
            }
        }
    } else {
        val enableButton = actionsRow.button(tr("USt-Modul aktivieren …"), style = ButtonStyle.PRIMARY)
        enableButton.onClick {
            enableButton.disabled = true
            AppScope.launch {
                val disclaimer = guarded { rpcService<IVatService>().getVatComplianceDisclaimer() }
                enableButton.disabled = false
                if (disclaimer != null) {
                    vatEnableDisclaimerModal(disclaimer) {
                        AppScope.launch {
                            val result =
                                guarded {
                                    rpcService<IVatService>().enableVat(
                                        VatComplianceAcknowledgmentInput(
                                            disclaimerVersion = disclaimer.version,
                                            disclaimerSha256 = disclaimer.sha256,
                                        ),
                                    )
                                }
                            if (result != null) {
                                notifySuccess(tr("USt-Modul aktiviert."))
                                onChanged()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wholesale-replace helper for the Kleinunternehmer checkbox wired in [renderVatGateSummary] above --
 * same "never silently drop/reset a field" contract as `LedgerScreen.kt`'s
 * `toInputWithPaymentAccountMapping` and `PoliticianScreen.kt`'s `toInputWithPoliticianRankingEnabled`
 * (both of which had exactly this bug for `isKleinunternehmer` itself until the same review round
 * that added this toggle -- see their own KDoc/inline comments). `internal` (not `private`) for the
 * same testability reasoning those two give.
 */
internal fun OrganizationSettingsDto.toInputWithKleinunternehmerFlag(newValue: Boolean) =
    OrganizationSettingsInput(
        name = name,
        street = street,
        postalCode = postalCode,
        city = city,
        country = country,
        bankIban = bankIban,
        bankBic = bankBic,
        taxExemptionAuthority = taxExemptionAuthority,
        taxExemptionDate = taxExemptionDate,
        isPoliticalParty = isPoliticalParty,
        postalMailEnabled = postalMailEnabled,
        politicianRankingEnabled = politicianRankingEnabled,
        paymentBankAccountId = paymentBankAccountId,
        paymentFeeAccountId = paymentFeeAccountId,
        contributionIncomeAccountId = contributionIncomeAccountId,
        donationIncomeAccountId = donationIncomeAccountId,
        eventIncomeAccountId = eventIncomeAccountId,
        eventIncomeSphere = eventIncomeSphere,
        datevBeraterNummer = datevBeraterNummer,
        datevMandantNummer = datevMandantNummer,
        travelExpenseAccountId = travelExpenseAccountId,
        volunteerAllowanceAccountId = volunteerAllowanceAccountId,
        isKleinunternehmer = newValue,
    )

private fun vatEnableDisclaimerModal(
    disclaimer: VatComplianceDisclaimerDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = gettext("USt-Modul aktivieren -- rechtlicher Hinweis (Version %1)", disclaimer.version))
    modal.div(
        tr(
            "Bitte lesen Sie den folgenden rechtlichen Hinweistext vollständig, bevor Sie das USt-Modul " +
                "aktivieren. Diese Plattform führt keine automatisierte Rechtsberatung durch -- die steuerliche " +
                "Einordnung liegt bei Ihrer Organisation bzw. deren Steuerberatung.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    modal.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 300.px
        overflow = Overflow.AUTO
        disclaimer.text.lines().forEach { line -> p(line) { addCssClasses("small mb-1") } }
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Gelesen -- USt-Modul aktivieren"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

private fun renderVatReturnPreviewBody(
    panel: SimplePanel,
    preview: VatReturnPreviewDto,
) {
    panel.div(periodRangeCaption(preview.from, preview.to)) { addCssClasses("text-muted small") }

    if (!preview.applicable) {
        panel.div(vatNotApplicableText(preview.notApplicableReason)) { addCssClasses("alert alert-light border") }
        return
    }

    panel.div(
        tr("Steuerbare Umsätze aus in Lapis Cloud erfassten POSTED-Buchungen im Zeitraum. Beträge sind BRUTTO inkl. USt."),
    ) { addCssClasses("text-muted small") }

    renderVatRateLinesTable(panel, tr("Umsatzsteuer (Ausgangsseite)"), preview.outputVatLines, preview.totalOutputVat)
    renderVatRateLinesTable(panel, tr("Vorsteuer (Eingangsseite)"), preview.inputVatLines, preview.totalInputVat)

    val balanceRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-2 mt-1 align-items-center") }
    balanceRow.div(vatBalanceLabel(preview.balance)) { addCssClasses("flex-grow-1") }
    balanceRow.moneySpan(preview.balance, warnIfNegative = false)

    if (preview.unclassifiedPostingCount > 0) {
        panel.div(
            gettext(
                "%1 Buchungen ohne USt-Einordnung (Σ brutto %2).",
                preview.unclassifiedPostingCount,
                formatMoney(preview.unclassifiedGrossTotal),
            ),
        ) { addCssClasses("alert alert-light border mt-1") }
    }

    panel.div(vatFilingPeriodicityText(preview.filingPeriodicity)) { addCssClasses("text-muted small mt-1") }
    if (preview.exemptionOnRequestPossible) {
        panel.div(
            tr("Eine Befreiung von der Voranmeldungspflicht ist nur auf Antrag und im Ermessen des Finanzamts möglich."),
        ) { addCssClasses("text-muted small") }
    }
}

private fun renderVatRateLinesTable(
    panel: SimplePanel,
    title: String,
    lines: List<VatRateLineDto>,
    total: Decimal,
) {
    panel.p(title) { addCssClasses("fw-bold small mt-2") }
    if (lines.isEmpty()) {
        panel.p(tr("Keine Buchungen in dieser Kategorie.")) { addCssClasses("text-muted small") }
        return
    }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1 small") }
    headerRow.div(tr("Satz")) { width = 100.px }
    headerRow.div(tr("Brutto")) { width = 120.px }
    headerRow.div(tr("Netto")) { width = 120.px }
    headerRow.div(tr("USt")) { width = 120.px }
    headerRow.div(tr("Anzahl")) { width = 80.px }
    headerRow.div("") { addCssClasses("flex-grow-1") }

    lines.forEach { line ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center small") }
        row.div(vatRateLabel(line.rate)) { width = 100.px }
        row.moneySpan(line.grossTotal).width = 120.px
        row.moneySpan(line.netTotal).width = 120.px
        row.moneySpan(line.vatTotal).width = 120.px
        row.div(line.postingCount.toString()) { width = 80.px }
        row.div("") { addCssClasses("flex-grow-1") }
    }
    val totalRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1 small") }
    totalRow.div(tr("Gesamt")) { width = 100.px }
    totalRow.div("") { width = 120.px }
    totalRow.div("") { width = 120.px }
    totalRow.moneySpan(total).width = 120.px
    totalRow.div("") { width = 80.px }
    totalRow.div("") { addCssClasses("flex-grow-1") }
}

// Not `private` -- covered by NonprofitComplianceReportsScreenTest.kt, same posture as
// mittelverwendungsBannerText/useOfFundsPeriodCaption/hasOverdueAmount below (top-level `private`
// in Kotlin is file-scoped, not module-scoped, so a test in a different file could not otherwise
// reach these).
fun vatNotApplicableText(reason: VatNotApplicableReason?): String =
    when (reason) {
        VatNotApplicableReason.VAT_DISABLED ->
            tr(
                "Das Umsatzsteuer-Modul ist für diese Organisation nicht aktiviert. Es wird keine Umsatzsteuer " +
                    "berechnet oder ausgewiesen.",
            )
        VatNotApplicableReason.KLEINUNTERNEHMER ->
            tr(
                "Diese Organisation ist als Kleinunternehmer nach § 19 UStG eingetragen. Es wird keine " +
                    "Umsatzsteuer berechnet oder ausgewiesen -- auch nicht für Buchungen, die einen Steuersatz tragen.",
            )
        null -> tr("Für diesen Zeitraum wird keine Umsatzsteuer berechnet oder ausgewiesen.")
    }

fun vatBalanceLabel(balance: Decimal): String =
    when {
        balance.toDouble() > 0.0 -> tr("Zahllast")
        balance.toDouble() < 0.0 -> tr("Erstattungsanspruch")
        else -> tr("Keine Zahllast")
    }

fun vatFilingPeriodicityText(periodicity: VatFilingPeriodicity): String =
    when (periodicity) {
        VatFilingPeriodicity.MONTHLY -> tr("Voranmeldungszeitraum (informativ): monatlich (Vorjahres-Zahllast über 9.000 €).")
        VatFilingPeriodicity.QUARTERLY -> tr("Voranmeldungszeitraum (informativ): vierteljährlich.")
        VatFilingPeriodicity.UNKNOWN ->
            tr("Lapis Cloud deckt das Vorjahr nicht vollständig ab -- Voranmeldungszeitraum nicht ableitbar.")
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
    return gettext(
        "Diese Auswertung ist eine Nachweis-Hilfe für den Vorstand nach §§ 55/62 AO -- keine " +
            "automatisierte Compliance-Entscheidung. Sie prüft nicht die Freie-Rücklage-Obergrenze, " +
            "wendet nicht automatisch die Kleinorganisationen-Ausnahme (≤ 45.000 € gemäß § 55 Abs. 1 " +
            "Nr. 5 Satz 4 AO) an und bestätigt nicht den Fortbestand der Gemeinnützigkeit. Die " +
            "verwendete Frist von %1 Jahren ist gegen die aktuelle AO-Auslegung zu prüfen.",
        years,
    )
}

fun useOfFundsPeriodCaption(
    fromFiscalYear: Int,
    toFiscalYear: Int,
): String = gettext("Zeitraum: Geschäftsjahr %1 bis %2", fromFiscalYear, toFiscalYear)

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
