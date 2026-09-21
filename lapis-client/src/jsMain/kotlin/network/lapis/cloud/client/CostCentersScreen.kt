package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.cell
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
 * ([filterCostCenters], analog [filterLedgerAccounts]).
 *
 * **Welle V1.4.27 (W3):** die Uebersicht ist jetzt ein [dataTable] (Liste: sortierbar, Kartenliste unter
 * 768 px), der Bericht darunter ein [reportTable] (Dokument: Nicht-zugeordnet-Zeile und Gesamtzeile gehoeren
 * zu den Nachbarzeilen, deshalb weder Sortierung noch Kartenliste). Beides sind echte Tabellen -- die
 * hPanel-Pseudo-Tabelle mit ihrem handgebauten `table-responsive`-Wrapper ist entfallen.
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
    val statusRegion = root.dataStatusRegion()
    val listPanel = root.vPanel(spacing = 6)

    // Zuletzt geladene, ungefilterte Kostenstellenliste -- die Live-Suche filtert auf dieser Kopie,
    // statt pro Tastendruck erneut `listCostCenters` zu rufen (Muster von `LedgerScreen.kt`).
    var loadedCostCenters: List<CostCenterDto> = emptyList()

    // Vorwaertsreferenz analog `LedgerScreen.refreshAccounts`: `renderCostCenterList` reicht das
    // Neuladen als `onChanged` an jede Zeile weiter, `refreshList` ruft seinerseits
    // `renderCostCenterList` -- eine echte Zyklus-Beziehung.
    var refreshList: () -> Unit = {}

    var generation = 0
    var loading = false
    // Fehlerzustand des Abrufs: solange er steht, darf die Suche ihn weder wegzeichnen noch die Liste des
    // vorigen Filters darüber malen (Richtlinie P7).
    var failed = false
    // Welle V1.4.27 (W3): der eine aktive Sortierzustand. Die Liste ist VOLLSTÄNDIG geladen, sortiert also
    // clientseitig über eine reine Funktion ([sortCostCenters]) -- `listCostCenters` kennt keinen Sortierparameter.
    var costCenterSort = SortState(key = COST_CENTER_SORT_CODE, direction = SortDirection.ASC)
    var pendingSortFocus: String? = null

    fun renderCostCenterList(query: String) {
        if (loading) return
        if (failed) return
        listPanel.removeAll()
        val sortFocusKey = pendingSortFocus
        pendingSortFocus = null
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
        // Welle V1.4.27 (W3): `dataTable` statt der von Hand gebauten `table(...)` -- kompakte Dichte, Kartenliste
        // unter 768 px, Sortierköpfe. Dieselbe Grammatik wie der Bericht darunter (siehe `renderCostCenterReportBody`:
        // dort ein Dokument, hier eine Liste).
        listPanel.dataTable(
            columns = costCenterColumns(),
            rows = sortCostCenters(filtered, costCenterSort),
            sort = costCenterSort,
            onSort = { clicked ->
                val next = clicked ?: costCenterSort
                costCenterSort = next
                pendingSortFocus = next.key
                renderCostCenterList(costCenterSearchInput.value.orEmpty())
            },
            actions = { actions, costCenter -> actions.renderCostCenterActions(costCenter, canManage) { refreshList() } },
            focusSortKey = sortFocusKey,
        )
    }

    refreshList = {
        generation++
        val mine = generation
        listPanel.removeAll()
        statusRegion.showLoading()
        loading = true
        failed = false
        AppScope.launch {
            val costCenters =
                guarded {
                    rpcService<IAccountingService>().listCostCenters(activeOnly = !includeInactiveCheck.value)
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat übernommen
            statusRegion.clearStatus()
            loading = false
            if (costCenters == null) {
                failed = true
                loadedCostCenters = emptyList()
                listPanel.dataErrorState(onRetry = { refreshList() })
                return@launch
            }
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

/** Sortierschluessel der Kostenstellenliste (Welle V1.4.27 / W3). */
internal const val COST_CENTER_SORT_CODE = "code"
internal const val COST_CENTER_SORT_DESCRIPTION = "description"

/**
 * Clientseitige Sortierung der vollstaendig geladenen Kostenstellenliste (pur, siehe `CostCentersScreenTest`).
 * Code und Beschreibung ohne Beruecksichtigung der Gross-/Kleinschreibung; Sekundaerschluessel ist immer der
 * Code, damit die Ordnung bei gleichen Werten stabil bleibt. Eine fehlende Beschreibung sortiert als leerer Text.
 */
internal fun sortCostCenters(
    costCenters: List<CostCenterDto>,
    sort: SortState,
): List<CostCenterDto> {
    val byKey: Comparator<CostCenterDto> =
        when (sort.key) {
            COST_CENTER_SORT_DESCRIPTION -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.description.orEmpty() }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.code }
        }
    val comparator = byKey.thenBy(String.CASE_INSENSITIVE_ORDER) { it.code }
    return costCenters.sortedWith(if (sort.direction == SortDirection.ASC) comparator else comparator.reversed())
}

/**
 * Spalten der Kostenstellenliste / Kartenliste. Kostenstelle (Code · Name) ist die Identitaet der Zeile und damit
 * der Kartentitel. Eine leere Beschreibung rendert nichts, damit die Kartenliste das leere Begriff/Wert-Paar
 * weglaesst.
 */
private fun costCenterColumns(): List<DataColumn<CostCenterDto>> =
    listOf(
        DataColumn(
            title = tr("Kostenstelle"),
            primary = true,
            sortKey = COST_CENTER_SORT_CODE,
            cell = { container, costCenter -> container.span(costCenterLabel(costCenter)) { addCssClass("fw-bold") } },
        ),
        DataColumn(
            title = tr("Beschreibung"),
            sortKey = COST_CENTER_SORT_DESCRIPTION,
            cell = { container, costCenter ->
                costCenter.description?.takeIf { it.isNotBlank() }?.let { description ->
                    container.div(description) {
                        addCssClasses("text-muted small text-truncate")
                        // `text-truncate` (overflow:hidden + white-space:nowrap) hat ohne begrenzte Breite
                        // keine sichtbare Wirkung. `maxWidth` statt `width`, damit kurze Beschreibungen nicht
                        // unnoetig Platz belegen (Konvention siehe `DataScreenLayout.kt` KDoc).
                        maxWidth = 320.px
                        title = description
                    }
                }
            },
        ),
        DataColumn(
            title = tr("Status"),
            cell = { container, costCenter -> container.activeStatusBadge(costCenter.active) },
        ),
    )

/** Zeilenaktionen -- Rollen-Gate (`canManage`), Statusbedingung und Bestaetigungsdialog unveraendert. */
private fun Container.renderCostCenterActions(
    costCenter: CostCenterDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    if (!canManage || !costCenter.active) return
    val actionRow = tableActionGroup()
    val deactivateButton = actionRow.tableActionButton("fas fa-ban", tr("Deaktivieren"), ButtonStyle.OUTLINEDANGER)
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
            // Der Zeilenknopf sperrt sich für die Dauer des Aufrufs (vorher `runGuardedAction(null)`: ein Schutz nur über die Einmal-Sperre des Dialogs).
            runGuardedAction(deactivateButton) {
                val result = guarded { rpcService<IAccountingService>().deactivateCostCenter(costCenter.id) }
                if (result != null) {
                    notifyInfo(tr("Kostenstelle wurde deaktiviert."))
                    onChanged()
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
internal fun renderCostCenterCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val form = root.lapisForm()
    // Das Beispiel steht im Hinweis, nie im Label (W4c).
    val codeField = form.textField(label = tr("Code"), required = true, hint = tr("Eindeutig. Beispiel: SOMMERFEST-2027."))
    val nameField = form.textField(label = tr("Name"), required = true)
    val descriptionField = form.textField(label = tr("Beschreibung"))

    val createButton = Button(tr("Kostenstelle anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val code = codeField.value.trim()
            val name = nameField.value.trim()
            val description = descriptionField.value.trim().takeIf { it.isNotBlank() }
            val result =
                guarded {
                    rpcService<IAccountingService>().createCostCenter(
                        CostCenterInput(code = code, name = name, description = description, active = true),
                    )
                }
            if (result != null) {
                notifySuccess(gettext("Kostenstelle \"%1 · %2\" wurde angelegt.", code, name))
                codeField.reset()
                nameField.reset()
                descriptionField.reset()
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
internal fun renderCostCenterReportView(panel: SimplePanel) {
    // W4c: der Zeitraum ist ein [LapisForm] -- der Fehler steht am Feld (vorher ein Sammelsatz über dem Knopf). "Bis" ist Pflicht und
    // vorbelegt, "Von" bleibt leer = seit Gründung.
    val form = panel.lapisForm()
    val fromField =
        form.textField(label = tr("Von"), hint = tr("Beispiel: 2026-03-14."), rule = { FormRules.isoDate(it) })
    val toField =
        form.textField(
            label = tr("Bis"),
            value = todayIso(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            requiredMessage = tr("Bitte ein gültiges \"Bis\"-Datum angeben."),
            rule = { FormRules.isoDate(it) },
        )
    val filterControls = DateRangeFilterControls(fromField.control as Text, toField.control as Text)
    val loadButton = Button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    form.buttons(primary = loadButton)
    // Welle V1.4.27 (W3): dataSection instead of a stuck "Wird geladen ..." on a failed load; the date validation
    // stays in front of the reload so an invalid date keeps its own message.
    val section =
        panel.dataSection<CostCenterReportDto>(
            isEmpty = { false },
            load = {
                filterControls.parseTo()?.let { to ->
                    guarded { rpcService<IAccountingService>().getCostCenterReport(filterControls.parseFrom(), to) }
                }
            },
            render = { body, report -> renderCostCenterReportBody(body, report, captionVisible = false) },
        )

    loadButton.onClick { if (form.validateAndReport()) section.reload() }
    // Der erste Abruf läuft mit den Vorbelegungen (Von leer, Bis heute: beides gültig) OHNE `validateAndReport()`: das markierte jedes Feld
    // als "schon abgesendet", und das frisch gezeigte Formular startete nie unberührt.
    section.reload()
}

private val COST_CENTER_REPORT_HEADERS =
    listOf(
        TableHeader(title = tr("Kostenstelle")),
        TableHeader(title = tr("Einnahmen"), numeric = true),
        TableHeader(title = tr("Ausgaben"), numeric = true),
        TableHeader(title = tr("Ergebnis"), numeric = true),
    )

/**
 * D14: the "Nicht zugeordnet" bucket is rendered as the last row after every code-sorted named
 * cost center (already sorted server-side, see [CostCenterReportDto.costCenters] KDoc), as a balance row (muted+italic,
 * thin top line) so it does not read as just another named cost center, then the server's grand total as a sum row.
 * [CostCenterReportDto.totalIncome]/[totalExpense]/[result] are the server's own already-reconciled figures (named
 * cost centers + unassigned bucket) -- rendered verbatim, never re-summed from [CostCenterResultDto] rows here.
 *
 * Welle V1.4.27 (W3): a [reportTable] -- the hand-built `table-responsive` wrapper with `minWidth` is gone, the
 * table's own responsive frame scrolls it on a phone (a report is a document, it scrolls sideways).
 */
internal fun renderCostCenterReportBody(
    panel: Container,
    report: CostCenterReportDto,
    captionVisible: Boolean = true,
) {
    panel.div(periodRangeCaption(report.from, report.to)) { addCssClasses("text-muted small") }
    // The screen's `h2 "Kostenstellenbericht"` stands right above the filters: the caption is the accessible name only.
    val table =
        panel.reportTable(
            caption = tr("Kostenstellenbericht"),
            headers = COST_CENTER_REPORT_HEADERS,
            captionVisible = captionVisible,
        )
    table.reportRows(costCenterReportRows(report), COST_CENTER_REPORT_HEADERS)
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
