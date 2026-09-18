package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.ResponsiveType
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.CostCenterReportDto
import network.lapis.cloud.shared.domain.CostCenterResultDto
import network.lapis.cloud.shared.rpc.IAccountingService
import kotlin.time.Clock

/**
 * Accounting UI wave, screen 4 of 5 -- "Kostenstellen" (cost-center CRUD + report), per the
 * approved plan + UI/UX-Design-Team review on `feature/accounting-ui`. See plan "Screen 4 --
 * CostCentersScreen.kt" and design decisions D5, D6, D9 (`activeStatusBadge`), D12, D14. No
 * cross-links into Crowdfunding/Auction this wave, per the task's explicit scope limit.
 *
 * Role gating (verified against `AccountingService.kt`, plan's role-gating table):
 * `createCostCenter`/`deactivateCostCenter` are `TREASURY_ROLES` (TREASURER/ADMIN);
 * `listCostCenters`/`getCostCenterReport` are `ACCOUNTING_READ_ROLES` (TREASURER/BOARD/ADMIN).
 * `Routing.kt` already gates the whole `/cost-centers` route on TREASURER/BOARD/ADMIN; the
 * narrower `canManage = AppState.hasRole(TREASURER, ADMIN)` additionally hides/disables every
 * mutating affordance for a BOARD caller, mirroring `LedgerScreen.kt`'s exact posture.
 *
 * Every monetary figure below is a `Decimal` returned verbatim by `IAccountingService` and
 * rendered through [formatMoney]/[moneySpan] (`Money.kt`) -- this screen never parses, sums, or
 * re-rounds a figure the server has already computed, including [CostCenterReportDto.totalIncome]/
 * [CostCenterReportDto.totalExpense]/[CostCenterReportDto.result], which already reconcile
 * server-side across the named cost centers plus the unassigned bucket (see that DTO's own KDoc)
 * -- this screen displays them as-is rather than re-summing [CostCenterResultDto] rows itself.
 *
 * **Nachtrag Design-Team-Sitzung 2026-09-18 (Nachmittag), siehe `DataScreenLayout.kt` KDoc:**
 * Uebersicht und Bericht zeigten bis dahin dieselben Objekte in zwei verschiedenen Grammatiken --
 * oben Karten (`vPanel("border rounded p-2")`), unten ein Tabellen-Raster. Die Uebersicht ist jetzt
 * eine echte Bootstrap-Tabelle ([dataScreenRoot], `TableType.STRIPED`/`HOVER`,
 * `ResponsiveType.RESPONSIVE`, Icon-Aktionsspalte -- Muster von [renderLedgerScreen]'s
 * Kontenplan-Tabelle uebernommen), inklusive einer neuen Live-Suche ueber Code und Name
 * ([filterCostCenters], analog [filterLedgerAccounts]). Das Berichtsraster selbst behaelt seine
 * `hPanel`-Zeilenstruktur (kein Bootstrap-`Table`, weil D14s Nicht-zugeordnet-/Gesamt-Sonderzeilen
 * mit ihren abweichenden Stilen einer echten `<table>` keinen Mehrwert brächten), bekommt aber
 * einen `table-responsive`-Scroll-Wrapper, damit es auf 375 px nicht mehr aus dem jetzt frei
 * schrumpfenden Root herauslaeuft (vorher schuetzte die feste 800-px-Root-Breite davor).
 */
fun renderCostCentersScreen(container: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)

    val root = container.dataScreenRoot(spacing = 14)
    root.h1(tr("Kostenstellen"))

    // ---- List (Kostenstellen-Übersicht) ----------------------------------------------------
    root.h2(tr("Übersicht"))
    // Design-Team-Welle 2026-09-18: Live-Suche analog `LedgerScreen.kt`s Kontenplan-Suche -- rein
    // clientseitige Filterung ohne RPC-Roundtrip pro Tastendruck, Suchfeld ausserhalb des
    // Listen-Panels (sonst risse jeder Tastendruck das eigene Eingabefeld samt Fokus ab, siehe
    // dortiges KDoc).
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-end flex-wrap") }
    val costCenterSearchInput = filterRow.text(label = tr("Kostenstelle suchen (Code oder Name)"))
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Kostenstellen anzeigen"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val listPanel = root.vPanel(spacing = 6)

    // Zuletzt geladene, ungefilterte Kostenstellenliste -- die Live-Suche filtert auf dieser Kopie,
    // statt pro Tastendruck erneut `listCostCenters` zu rufen (Muster von `LedgerScreen.kt`).
    var loadedCostCenters: List<CostCenterDto> = emptyList()

    // Vorwaertsreferenz analog `LedgerScreen.refreshAccounts`: `renderCostCenterList` reicht das
    // Neuladen als `onChanged` an jede Zeile weiter, `refreshList` ruft seinerseits
    // `renderCostCenterList` -- eine echte Zyklus-Beziehung.
    var refreshList: () -> Unit = {}

    fun renderCostCenterList(query: String) {
        listPanel.removeAll()
        if (loadedCostCenters.isEmpty()) {
            listPanel.p(tr("Noch keine Kostenstellen angelegt."))
            return
        }
        val filtered = filterCostCenters(loadedCostCenters, query)
        if (filtered.isEmpty()) {
            listPanel.p(gettext("Keine Kostenstelle passt zu \"%1\".", query.trim()))
            return
        }
        if (query.isNotBlank()) {
            listPanel.div(gettext("%1 von %2 Kostenstellen", filtered.size, loadedCostCenters.size)) {
                addCssClasses("text-muted small")
            }
        }
        val table =
            listPanel.table(
                headerNames = listOf(tr("Kostenstelle"), tr("Beschreibung"), tr("Status"), tr("Aktionen")),
                types = setOf(TableType.STRIPED, TableType.HOVER),
                responsiveType = ResponsiveType.RESPONSIVE,
            )
        filtered.forEach { costCenter ->
            renderCostCenterRow(table, costCenter, canManage) { refreshList() }
        }
    }

    refreshList = {
        listPanel.removeAll()
        AppScope.launch {
            val costCenters =
                guarded {
                    rpcService<IAccountingService>().listCostCenters(activeOnly = !includeInactiveCheck.value)
                } ?: return@launch
            loadedCostCenters = costCenters
            renderCostCenterList(costCenterSearchInput.value.orEmpty())
        }
    }
    refreshButton.onClick { refreshList() }

    // Kein Debounce -- rein clientseitige Filterung (gleiche Entscheidung wie `LedgerScreen.kt`).
    // `isInitialSearchEvent`-Guard noetig, weil KVisions `subscribe` bei der Registrierung sofort
    // einmal synthetisch mit dem aktuellen Wert feuert.
    var isInitialSearchEvent = true
    costCenterSearchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        renderCostCenterList(value.orEmpty())
    }

    refreshList()

    if (canManage) {
        root.h2(tr("Neue Kostenstelle anlegen"))
        renderCostCenterCreationForm(root) { refreshList() }
    }

    // ---- Report -----------------------------------------------------------------------------
    root.h2(tr("Kostenstellenbericht"))
    renderCostCenterReportView(root)
}

// ============================================================================================
// List row + creation form
// ============================================================================================

/**
 * Design-Team-Welle 2026-09-18 (Nachmittag): vorher ein `vPanel("border rounded p-2")`-Kartenrow,
 * jetzt eine echte Tabellenzeile -- Muster von `LedgerScreen.renderAccountRow` uebernommen. Die
 * Aktionsspalte wird immer gerendert (auch leer, wenn weder Icon-Knopf noch etwas anderes greift),
 * damit alle Zeilen dieselbe Spaltenzahl behalten.
 */
private fun renderCostCenterRow(
    table: Table,
    costCenter: CostCenterDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    table.row {
        cell(costCenterLabel(costCenter)) { addCssClass("fw-bold") }
        val descriptionCell = cell()
        costCenter.description?.takeIf { it.isNotBlank() }?.let { description ->
            descriptionCell.div(description) {
                addCssClasses("text-muted small text-truncate")
                // `text-truncate` (overflow:hidden + white-space:nowrap) hat ohne begrenzte Breite
                // keine sichtbare Wirkung -- die Tabellenzelle waechst sonst einfach mit dem Inhalt.
                // `maxWidth` statt `width`, damit kurze Beschreibungen nicht unnoetig Platz belegen
                // (Konvention siehe `DataScreenLayout.kt` KDoc).
                maxWidth = 320.px
                title = description
            }
        }
        cell { activeStatusBadge(costCenter.active) }

        val actionsCell = cell()
        val actionRow = actionsCell.tableActionGroup()
        if (canManage && costCenter.active) {
            val deactivateButton =
                actionRow.tableActionButton("fas fa-ban", tr("Deaktivieren"), ButtonStyle.OUTLINEDANGER)
            deactivateButton.onClick {
                confirmDialog(
                    title = tr("Kostenstelle deaktivieren"),
                    message =
                        gettext(
                            "\"%1 · %2\" wirklich deaktivieren? Bestehende Buchungen bleiben erhalten, die " +
                                "Kostenstelle steht aber für neue Buchungen nicht mehr zur Verfügung.",
                            costCenter.code,
                            costCenter.name,
                        ),
                    confirmLabel = tr("Deaktivieren"),
                ) {
                    AppScope.launch {
                        val result = guarded { rpcService<IAccountingService>().deactivateCostCenter(costCenter.id) }
                        if (result != null) {
                            notifyInfo(tr("Kostenstelle wurde deaktiviert."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Client-side non-blank pre-check only (`Validation.isNonBlank`, mirroring `LedgerScreen`'s own
 * account-creation form) -- the server remains the authority on a duplicate/blank
 * [CostCenterInput.code] (`BadRequestException`/`ConflictException`, see `IAccountingService
 * .createCostCenter` KDoc); a race or a validation this client does not mirror correctly still
 * surfaces through `guarded()`'s generic error toast.
 */
private fun renderCostCenterCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val codeInput = panel.text(label = tr("Code (eindeutig, z. B. SOMMERFEST-2027)"))
    val nameInput = panel.text(label = tr("Name"))
    val descriptionInput = panel.text(label = tr("Beschreibung (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button(tr("Kostenstelle anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val code = codeInput.value.orEmpty().trim()
        val name = nameInput.value.orEmpty().trim()
        val description = descriptionInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (!Validation.isNonBlank(code) || !Validation.isNonBlank(name)) {
            errorBox.content = tr("Bitte Code und Name angeben.")
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IAccountingService>().createCostCenter(
                        CostCenterInput(code = code, name = name, description = description, active = true),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Kostenstelle \"%1 · %2\" wurde angelegt.", code, name))
                codeInput.value = null
                nameInput.value = null
                descriptionInput.value = null
                onCreated()
            }
        }
    }
}

// ============================================================================================
// Report (getCostCenterReport)
// ============================================================================================

/**
 * `to` is a required `LocalDate` server-side (like `getIncomeStatement`, unlike the Journal/
 * Hauptbuch/Kassenbuch filters' optional `to`) -- pre-filled to today so the first render shows a
 * meaningful report instead of an immediate validation error before the treasurer has touched
 * anything, mirroring `FinancialReportsScreen.renderIncomeStatementView`'s identical precedent.
 */
private fun renderCostCenterReportView(panel: SimplePanel) {
    val filterControls = panel.dateRangeFilter()
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
            val report =
                guarded { rpcService<IAccountingService>().getCostCenterReport(from, to) } ?: return@launch
            resultPanel.removeAll()
            renderCostCenterReportBody(resultPanel, report)
        }
    }
    loadButton.onClick { load() }
    load()
}

/**
 * D14: the "Nicht zugeordnet" bucket is rendered as the last row after every code-sorted named
 * cost center (already sorted server-side, see [CostCenterReportDto.costCenters] KDoc), styled
 * muted+italic with a thin top border so it does not read as just another named cost center, then
 * a heavier bold-bordered grand-total row below that. [CostCenterReportDto.totalIncome]/
 * [totalExpense]/[result] are the server's own already-reconciled figures (named cost centers +
 * unassigned bucket) -- rendered verbatim, never re-summed from [CostCenterResultDto] rows here.
 */
private fun renderCostCenterReportBody(
    panel: SimplePanel,
    report: CostCenterReportDto,
) {
    panel.div(periodRangeCaption(report.from, report.to)) { addCssClasses("text-muted small") }

    // Design-Team-Welle 2026-09-18 (Nachmittag): `table-responsive`-Wrapper, damit das Raster auf
    // 375 px nicht mehr aus dem jetzt frei schrumpfenden `dataScreenRoot()` herauslaeuft -- vorher
    // schuetzte die feste 800-px-Root-Breite davor (siehe `DataScreenLayout.kt` KDoc). `minWidth`
    // deckt die schmalste Spaltenkombination (flex-grow-1-Spalte + 3 × 120 px + Abstaende) ab, bevor
    // eine Zeile innerhalb des scrollbaren Rahmens umbricht.
    val scrollWrapper = panel.div { addCssClass("table-responsive") }
    val reportGrid = scrollWrapper.vPanel(spacing = 0) { minWidth = 640.px }

    if (report.costCenters.isEmpty()) {
        reportGrid.p(tr("Keine Kostenstelle mit Buchungen im gewählten Zeitraum.")) { addCssClasses("text-muted small") }
    } else {
        val headerRow = reportGrid.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
        headerRow.div(tr("Kostenstelle")) { addCssClasses("flex-grow-1") }
        headerRow.div(tr("Einnahmen")) { width = 120.px }
        headerRow.div(tr("Ausgaben")) { width = 120.px }
        headerRow.div(tr("Ergebnis")) { width = 120.px }

        report.costCenters.forEach { costCenter ->
            val row = reportGrid.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            row.div(costCenterResultLabel(costCenter)) { addCssClasses("flex-grow-1") }
            row.div(formatMoney(costCenter.totalIncome)) { width = 120.px }
            row.div(formatMoney(costCenter.totalExpense)) { width = 120.px }
            row.moneySpan(costCenter.result, warnIfNegative = true).width = 120.px
        }
    }

    // D14: "Nicht zugeordnet" -- muted/italic, thin top border, visually distinct from a named row.
    val unassignedRow =
        reportGrid.hPanel(spacing = 8) {
            addCssClasses("border-top py-1 align-items-center fst-italic text-muted")
        }
    unassignedRow.div(tr("— Nicht zugeordnet —")) { addCssClasses("flex-grow-1") }
    unassignedRow.div(formatMoney(report.unassignedIncome)) { width = 120.px }
    unassignedRow.div(formatMoney(report.unassignedExpense)) { width = 120.px }
    unassignedRow.moneySpan(report.unassignedResult, warnIfNegative = true).width = 120.px

    val totalRow = reportGrid.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1 align-items-center") }
    totalRow.div(tr("Gesamt")) { addCssClasses("flex-grow-1") }
    totalRow.div(formatMoney(report.totalIncome)) { width = 120.px }
    totalRow.div(formatMoney(report.totalExpense)) { width = 120.px }
    totalRow.moneySpan(report.result, warnIfNegative = true).width = 120.px
}

// ============================================================================================
// Pure helpers -- covered by CostCentersScreenTest.kt
// ============================================================================================

/**
 * Reines, DOM-unabhaengiges Filter-Praedikat der Kostenstellen-Live-Suche -- testbar ohne
 * Rendering-Harness (analog [filterLedgerAccounts] in `LedgerScreen.kt`).
 *
 * Gesucht wird ueber **Code UND Name**, gleiche Zwei-Zugriffswege-Begruendung wie beim Kontenplan.
 * `ignoreCase = true`: der Gelegenheitsnutzer tippt nicht auf Grossschreibung.
 */
internal fun filterCostCenters(
    costCenters: List<CostCenterDto>,
    query: String,
): List<CostCenterDto> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return costCenters
    return costCenters.filter {
        it.code.contains(trimmed, ignoreCase = true) || it.name.contains(trimmed, ignoreCase = true)
    }
}

/** Shared "code · name" label formatting for a [CostCenterDto] row -- factored out so the list
 * row and (via [costCenterResultLabel]) the report table can never drift into two slightly
 * different renderings of the same identity. */
fun costCenterLabel(costCenter: CostCenterDto): String = gettext("%1 · %2", costCenter.code, costCenter.name)

/** Same "code · name" identity as [costCenterLabel], but for a [CostCenterResultDto] row (the
 * report table's shape, which does not carry the full [CostCenterDto]) -- kept as a separate
 * function rather than a shared interface, since the two DTOs otherwise have no supertype in
 * common and coercing one would be more machinery than the one-line duplication it avoids. */
fun costCenterResultLabel(costCenter: CostCenterResultDto): String = gettext("%1 · %2", costCenter.code, costCenter.name)

/** Mirrors `LedgerScreen.kt`/`FinancialReportsScreen.kt`'s own private `todayIso()` -- no shared
 * date-util file exists in this client (each screen that needs "today as JJJJ-MM-TT" carries its
 * own copy). */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()
