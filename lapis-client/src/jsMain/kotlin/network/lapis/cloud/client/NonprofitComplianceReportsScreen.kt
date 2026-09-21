package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
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
import io.kvision.table.Row
import io.kvision.table.Table
import io.kvision.table.cell
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.ReserveMovementDto
import network.lapis.cloud.shared.domain.SphereAmountDto
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
 * The Vier-Sphären-Ergebnisrechnung table reuses [renderStatementSectionTable] from
 * `FinancialReportsScreen.kt` verbatim for each sphere's expanded income/expense detail (D7:
 * "literally the same StatementLineDto shape") -- see that file's own KDoc for why the function
 * was made non-private for this screen's sake.
 */
fun renderNonprofitComplianceReportsScreen(container: SimplePanel) {
    val root = container.dataScreenRoot(spacing = 14)
    root.h1(tr("Gemeinnützigkeits-Berichte"))

    // Welle V1.4.27 (W3): a segmented control with an active state instead of three outline buttons that never
    // said which report was showing. "USt-Voranmeldung -- Vorschau" is Welle V1.4.13 "USt-Voranmeldung
    // (Nachweishilfe)".
    val options =
        listOf(
            NonprofitReportView.FOUR_SPHERE to tr("Vier-Sphären-Ergebnisrechnung"),
            NonprofitReportView.USE_OF_FUNDS to tr("Mittelverwendungsrechnung"),
            NonprofitReportView.VAT_RETURN to tr("USt-Voranmeldung — Vorschau"),
        )
    lateinit var contentPanel: SimplePanel
    root.segmentedScroll {
        segmentedControl(options = options, selected = NonprofitReportView.FOUR_SPHERE, ariaLabel = tr("Berichtsart")) { view ->
            contentPanel.removeAll()
            when (view) {
                NonprofitReportView.FOUR_SPHERE -> renderFourSphereIncomeStatementView(contentPanel)
                NonprofitReportView.USE_OF_FUNDS -> renderUseOfFundsView(contentPanel)
                NonprofitReportView.VAT_RETURN -> renderVatReturnPreviewView(contentPanel)
            }
        }
    }
    contentPanel = root.vPanel(spacing = 10)

    renderFourSphereIncomeStatementView(contentPanel)
}

private enum class NonprofitReportView { FOUR_SPHERE, USE_OF_FUNDS, VAT_RETURN }

private val FOUR_SPHERE_HEADERS =
    listOf(
        TableHeader(title = tr("Sphäre")),
        TableHeader(title = tr("Einnahmen"), numeric = true),
        TableHeader(title = tr("Ausgaben"), numeric = true),
        TableHeader(title = tr("Ergebnis"), numeric = true),
        TableHeader(title = tr("Details")),
    )

private val USE_OF_FUNDS_HEADERS =
    listOf(
        TableHeader(title = tr("Geschäftsjahr")),
        TableHeader(title = tr("Mittelzufluss"), numeric = true),
        TableHeader(title = tr("Mittelverwendung"), numeric = true),
        TableHeader(title = tr("Rücklagenzuführung"), numeric = true),
        TableHeader(title = tr("Mittelvortrag"), numeric = true),
        TableHeader(title = tr("davon überfällig"), numeric = true),
        TableHeader(title = tr("Details")),
    )

private val RESERVE_MOVEMENT_HEADERS =
    listOf(
        TableHeader(title = tr("Rücklagenart")),
        TableHeader(title = tr("Zuführung/Auflösung"), numeric = true),
        TableHeader(title = tr("Schlussstand"), numeric = true),
    )

private val SPHERE_AMOUNT_HEADERS =
    listOf(
        TableHeader(title = tr("Sphäre")),
        TableHeader(title = tr("Betrag"), numeric = true),
    )

private val VAT_RATE_HEADERS =
    listOf(
        TableHeader(title = tr("Satz")),
        TableHeader(title = tr("Brutto"), numeric = true),
        TableHeader(title = tr("Netto"), numeric = true),
        TableHeader(title = tr("USt"), numeric = true),
        TableHeader(title = tr("Anzahl"), numeric = true),
    )

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
    // Welle V1.4.27 (W3): dataSection instead of a stuck "Wird geladen ..." on a failed load; the date validation
    // stays in front of the reload so an invalid date keeps its own message.
    val section =
        panel.dataSection<FourSphereIncomeStatementDto>(
            isEmpty = { false },
            load = {
                filterControls.parseTo()?.let { to ->
                    guarded { rpcService<IAccountingService>().getFourSphereIncomeStatement(filterControls.parseFrom(), to) }
                }
            },
            render = { body, statement -> renderFourSphereIncomeStatementBody(body, statement, captionVisible = false) },
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

/**
 * D7: one table, sphere as leftmost column, [FourSphereIncomeStatementDto.spheres] rendered in
 * the exact order the server returned it (that DTO's own KDoc: "always exactly four ... in that
 * enum's declaration order") -- never re-sorted here.
 *
 * Each row expands in place (no separate detail screen): all four spheres are always present, so the fixed
 * four-row layout stays intact while the underlying [network.lapis.cloud.shared.domain.StatementLineDto]
 * line items are offered on demand -- in a collapsed `<tr>` under the sphere row (`colspan`, two inner tables
 * Einnahmen/Ausgaben). The toggle names its sphere, so the four buttons have four different accessible names.
 */
internal fun renderFourSphereIncomeStatementBody(
    panel: SimplePanel,
    statement: FourSphereIncomeStatementDto,
    captionVisible: Boolean = true,
) {
    panel.div(periodRangeCaption(statement.from, statement.to)) { addCssClasses("text-muted small") }

    // The standalone view's `h2` carries the same words -> caption only for assistive technology (audit MINOR-2).
    val report =
        panel.reportTable(
            caption = tr("Vier-Sphären-Ergebnisrechnung"),
            headers = FOUR_SPHERE_HEADERS,
            captionVisible = captionVisible,
        )
    val rows = fourSphereRows(statement)
    statement.spheres.zip(rows).forEach { (sphere, data) ->
        val rowId = "lapis-sphere-${sphere.sphere.name}"
        lateinit var detail: Row
        report.reportRow(data, FOUR_SPHERE_HEADERS) {
            cell { expandToggleButton(sphereLabel(sphere.sphere), rowId) { expanded -> detail.setExpanded(expanded) } }
        }
        detail =
            report.detailRow(rowId, FOUR_SPHERE_HEADERS.size) { detailCell ->
                renderStatementSectionTable(detailCell, tr("Einnahmen"), sphere.incomeLines, sphere.totalIncome)
                renderStatementSectionTable(detailCell, tr("Ausgaben"), sphere.expenseLines, sphere.totalExpense)
            }
    }
    // The sum row gets an empty action cell: every `td` paints the total-row background/top rule (`theme.css`),
    // so a missing last cell would cut the line off in front of the "Details" column.
    report.reportRow(rows.last(), FOUR_SPHERE_HEADERS, extra = { cell {} })
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

    fun validRange(): Pair<Int, Int>? {
        val fromFiscalYear = fromYearControls.parseYear()
        val toFiscalYear = toYearControls.parseYear()
        return if (fromFiscalYear == null || toFiscalYear == null || fromFiscalYear > toFiscalYear) null else fromFiscalYear to toFiscalYear
    }
    val section =
        panel.dataSection<UseOfFundsStatementDto>(
            isEmpty = { false },
            // The banner takes the server's own figure the moment a load resolves, also when the year list is empty.
            onSettled = { statement -> statement?.let { bannerBox.content = mittelverwendungsBannerText(it.timelyUseYears) } },
            load = {
                validRange()?.let { (fromFiscalYear, toFiscalYear) ->
                    guarded { rpcService<IAccountingService>().getUseOfFundsStatement(fromFiscalYear, toFiscalYear) }
                }
            },
            render = { body, statement -> renderUseOfFundsBody(body, statement, captionVisible = false) },
        )

    fun load() {
        errorBox.hide()
        if (validRange() == null) {
            errorBox.content = tr("Bitte ein gültiges \"Von\"- und \"Bis\"-Geschäftsjahr angeben (Von ≤ Bis).")
            errorBox.show()
            return
        }
        section.reload()
    }
    loadButton.onClick { load() }
    load()
}

internal fun renderUseOfFundsBody(
    panel: SimplePanel,
    statement: UseOfFundsStatementDto,
    captionVisible: Boolean = true,
) {
    panel.div(useOfFundsPeriodCaption(statement.fromFiscalYear, statement.toFiscalYear)) {
        addCssClasses("text-muted small")
    }

    // Same words as the standalone view's `h2` -> caption only for assistive technology (audit MINOR-2).
    val report =
        panel.reportTable(
            caption = tr("Mittelverwendungsrechnung (§55/§62 AO)"),
            headers = USE_OF_FUNDS_HEADERS,
            captionVisible = captionVisible,
        )
    val rows = useOfFundsRows(statement)
    rows.filter { it.kind == ReportRowKind.NOTE }.forEach { report.reportRow(it, USE_OF_FUNDS_HEADERS) }
    // Never re-sorted -- [UseOfFundsStatementDto.years] KDoc: "one UseOfFundsYearDto per fiscal
    // year in [fromFiscalYear, toFiscalYear]", already in that order.
    statement.years.zip(rows.filter { it.kind == ReportRowKind.DATA }).forEach { (year, data) ->
        renderUseOfFundsYearRow(report, year, data)
    }
    // Empty action cell, see the Vier-Sphaeren sum row: keeps the total-row rule unbroken up to the last column.
    report.reportRow(rows.first { it.kind == ReportRowKind.TOTAL }, USE_OF_FUNDS_HEADERS, extra = { cell {} })

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

/** One fiscal-year row plus its collapsed detail row: the reserve movements and (informational, D-plan item 3)
 * the per-sphere disaggregation. The overdue amount is emphasised by the row model ([ReportCell.Money.emphasize]). */
private fun renderUseOfFundsYearRow(
    report: Table,
    year: UseOfFundsYearDto,
    data: ReportRow,
) {
    val rowId = "lapis-year-${year.fiscalYear}"
    lateinit var detail: Row
    report.reportRow(data, USE_OF_FUNDS_HEADERS) {
        cell { expandToggleButton(year.fiscalYear.toString(), rowId) { expanded -> detail.setExpanded(expanded) } }
    }
    detail =
        report.detailRow(rowId, USE_OF_FUNDS_HEADERS.size) { detailCell ->
            renderReserveMovementsTable(detailCell, year.reserveMovements)
            renderSphereAmountTable(detailCell, tr("Mittelzufluss nach Sphäre (informativ)"), year.receivedBySphere)
            renderSphereAmountTable(detailCell, tr("Mittelverwendung nach Sphäre (informativ)"), year.usedBySphere)
        }
}

/** D4: the `FREIE_RUECKLAGE` row gets its own inline "(gesetzliche Obergrenze hier nicht geprüft)" caveat --
 * Norman's "constraints visible at point of use", not just once in the top banner -- as a note row right
 * after the affected row. */
internal fun renderReserveMovementsTable(
    panel: Container,
    movements: List<ReserveMovementDto>,
) {
    val report = panel.reportTable(caption = tr("Rücklagenbewegungen (§62 AO)"), headers = RESERVE_MOVEMENT_HEADERS)
    report.reportRows(reserveMovementRows(movements), RESERVE_MOVEMENT_HEADERS)
}

internal fun renderSphereAmountTable(
    panel: Container,
    title: String,
    amounts: List<SphereAmountDto>,
) {
    val report = panel.reportTable(caption = title, headers = SPHERE_AMOUNT_HEADERS)
    report.reportRows(sphereAmountRows(amounts), SPHERE_AMOUNT_HEADERS)
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
    // getVatReturnPreview requires BOTH bounds (unlike listJournal/getFourSphereIncomeStatement's
    // optional `from` -- "seit Gründung" has no defensible meaning for a UStVA-shaped period,
    // which is always a specific Voranmeldungszeitraum, never an open-ended one).
    val section =
        panel.dataSection<VatReturnPreviewDto>(
            isEmpty = { false },
            load = {
                val to = filterControls.parseTo()
                val from = filterControls.parseFrom()
                if (to == null || from == null) {
                    null
                } else {
                    guarded { rpcService<IAccountingService>().getVatReturnPreview(from = from, to = to) }
                }
            },
            render = { body, preview -> renderVatReturnPreviewBody(body, preview) },
        )

    fun load() {
        errorBox.hide()
        if (filterControls.parseTo() == null || filterControls.parseFrom() == null) {
            errorBox.content = tr("Bitte ein gültiges \"Von\"- und \"Bis\"-Datum angeben (JJJJ-MM-TT).")
            errorBox.show()
            return
        }
        section.reload()
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
internal fun renderVatAdminGateSection(root: SimplePanel) {
    val gatePanel = root.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    // Audit V1.4.27 (F): `dataSection` instead of "Wird geladen ..." forever (`?: return@launch`) when the load fails --
    // the gate shows the error state with "Erneut versuchen" (and reloads itself after every change).
    lateinit var section: DataSection
    section =
        gatePanel.dataSection<VatSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IVatService>().getVatSettings() } },
            render = { body, settings -> renderVatGateSummary(body, settings, onChanged = { section.reload() }) },
        )
    section.reload()
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
 * same testability reasoning those two give. Baut [OrganizationSettingsInput] seit Audit-Fund M2
 * nicht mehr selbst, sondern über [toInput].
 */
internal fun OrganizationSettingsDto.toInputWithKleinunternehmerFlag(newValue: Boolean) =
    // Audit-Fund M2: ein Feld umschalten, alles andere über [toInput] unverändert mitschicken --
    // statt 26 Felder von Hand abzuschreiben (siehe [toInput] KDoc).
    toInput().copy(isKleinunternehmer = newValue)

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

internal fun renderVatRateLinesTable(
    panel: Container,
    title: String,
    lines: List<VatRateLineDto>,
    total: Decimal,
) {
    if (lines.isEmpty()) {
        panel.p(title) { addCssClasses("fw-bold small mt-2") }
        panel.p(tr("Keine Buchungen in dieser Kategorie.")) { addCssClasses("text-muted small") }
        return
    }
    val report = panel.reportTable(caption = title, headers = VAT_RATE_HEADERS)
    report.reportRows(vatRateLineRows(lines, total), VAT_RATE_HEADERS)
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
