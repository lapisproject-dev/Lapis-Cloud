package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.rpc.IPartyReportService
import org.w3c.dom.events.Event

/** The tabs of the party report page. New tabs are appended: the remembered tab is read back by name. */
internal enum class PartyReportTab { OVERVIEW, SHEET, POSITIONS, REPORT, AUSWEISE, ERLAEUTERUNGEN, KONTENZUORDNUNG, KONSOLIDIERUNG }

/**
 * The state the tabs share (V1.9.225): the report year and the unit picked for entry, plus a probe the page asks before something would
 * throw typed-in values away (changing the year, the unit or the tab, closing the browser tab).
 */
internal class PartyReportContext(
    var year: Int,
) {
    var unit: PartgUnitDto? = null
    var hasUnsavedChanges: () -> Boolean = { false }

    /** V1.9.256: the scope of the report export (`ALL` or a unit key). */
    var reportScope: String = network.lapis.cloud.shared.domain.PartgReportScope.ALL_KEY

    /** V1.9.256: jumps to the entry tab of a unit (set by the page). */
    var openSheetFor: (PartgUnitDto) -> Unit = {}

    /** V1.9.269: switches to a tab (set by the page); the link of a plausibility hint uses it. */
    var openTab: (PartyReportTab) -> Unit = {}

    /** V1.9.283: the block of the tab "Ausweise" that a hint link asks to open; consumed (reset) when the tab is rendered. */
    var annexFocusBlock: AnnexBlock? = null
}

private const val TAB_STORAGE_KEY = "lapis.partyReport.tab"

private fun rememberedTab(): PartyReportTab =
    (
        runCatching {
            window.localStorage
                .getItem(
                    TAB_STORAGE_KEY,
                )?.let { PartyReportTab.valueOf(it) }
        }.getOrNull() ?: PartyReportTab.OVERVIEW
    ).takeIf { it in partgVisibleTabs(AppState.session) } ?: PartyReportTab.OVERVIEW

private fun rememberTab(tab: PartyReportTab) {
    runCatching { window.localStorage.setItem(TAB_STORAGE_KEY, tab.name) }
}

/**
 * V1.9.225 "Rechenschaftsbericht (PartG), R1" -- the page for the finance board of a political party: what was spent and received, per reporting
 * unit and year, typed in or imported from Excel, and (R2, V1.9.256) the draft of the report as PDF or Excel. The draft is no official report; the
 * texts say so plainly.
 *
 * Reachable for BOARD and ADMIN when the organisation is a political party (`SessionInfoDto.politicalParty`); the server enforces both on every
 * call. Six tabs: the overview of all units, entry of one unit, the separate statements (A, B, member count), the notes, the positions with the organisation's own hints, and the report draft.
 */
internal fun renderPartyReportScreen(
    container: SimplePanel,
    requestedTab: PartyReportTab? = null,
) {
    val root = container.dataScreenRoot(spacing = 14)
    root.pageHeader(tr("Rechenschaftsbericht nach Parteiengesetz"), intro = PageIntros.forRoute(Routes.PARTY_REPORT))
    if (AppState.session?.politicalParty != true) {
        root.p(
            tr("Der Rechenschaftsbericht ist nur für politische Parteien verfügbar. Die Einstellung finden Sie in den Organisationsdaten."),
        )
        return
    }
    root.div(className = "alert alert-info mb-0") {
        setAttribute("role", "note")
        div(
            tr(
                "Hier erfassen Sie die Zahlen für den Rechenschaftsbericht und erzeugen daraus einen Entwurf. Das ist eine Arbeitshilfe, " +
                    "keine Rechtsberatung: Der Entwurf ersetzt nicht den geprüften Bericht.",
            ),
        )
    }

    val context = PartyReportContext(year = organizationToday().year - 1)
    val yearToolbar = root.lapisToolbar()
    val years = (organizationToday().year downTo 2001).map { it.toString() to it.toString() }
    val segmentHost = root.simplePanel()
    val tabPanel = root.vPanel(spacing = 10)
    var tab = requestedTab?.takeIf { it in partgVisibleTabs(AppState.session) } ?: rememberedTab()
    if (requestedTab != null) {
        // the deep link has done its job: the address goes back to the plain page (a reload keeps the remembered tab)
        runCatching { window.history.replaceState(null, "", "#" + Routes.PARTY_REPORT) }
    }

    fun guardedChange(
        proceed: () -> Unit,
        revert: () -> Unit,
    ) {
        if (!context.hasUnsavedChanges()) {
            proceed()
            return
        }
        revert()
        confirmDialog(
            title = tr("Eingaben verwerfen?"),
            message = tr("Sie haben Werte eingegeben, die noch nicht gespeichert sind. Wenn Sie jetzt wechseln, gehen sie verloren."),
            confirmLabel = tr("Verwerfen"),
            cancelLabel = tr("Weiter bearbeiten"),
            onConfirm = proceed,
        )
    }

    lateinit var showTab: (PartyReportTab) -> Unit
    context.openSheetFor = { unit ->
        context.unit = unit
        showTab(PartyReportTab.SHEET)
    }
    context.openTab = { chosen -> guardedChange(proceed = { showTab(chosen) }, revert = {}) }

    fun tabLabel(candidate: PartyReportTab): String =
        when (candidate) {
            PartyReportTab.OVERVIEW -> tr("Übersicht")
            PartyReportTab.SHEET -> tr("Werte erfassen")
            PartyReportTab.AUSWEISE -> tr("Ausweise")
            PartyReportTab.ERLAEUTERUNGEN -> tr("Erläuterungen")
            PartyReportTab.POSITIONS -> tr("Positionen")
            PartyReportTab.REPORT -> tr("Bericht")
            PartyReportTab.KONTENZUORDNUNG -> tr("Kontenzuordnung")
            PartyReportTab.KONSOLIDIERUNG -> tr("Konsolidierung")
        }

    fun paintSegments() {
        segmentHost.removeAll()
        val options = partgVisibleTabs(AppState.session).map { it to tabLabel(it) }
        // wide screens: the segmented buttons; below 640 px: a select (the CSS shows exactly one of the two, so the other one is not in
        // the accessibility tree)
        segmentHost.div(className = "lapis-partg-tabs-wide") {
            segmentedScroll {
                segmentedControl(options = options, selected = tab, ariaLabel = tr("Bereich")) { chosen ->
                    if (chosen != tab) guardedChange(proceed = { showTab(chosen) }, revert = {})
                }
            }
        }
        segmentHost.div(className = "lapis-partg-tabs-narrow") {
            val tabSelect =
                select(
                    options = options.map { (value, label) -> value.name to label },
                    value = tab.name,
                    label = tr("Bereich"),
                )
            tabSelect.subscribe {
                val chosen = tabSelect.value?.let { name -> PartyReportTab.entries.firstOrNull { it.name == name } } ?: return@subscribe
                if (chosen != tab) {
                    guardedChange(proceed = { showTab(chosen) }, revert = { tabSelect.value = tab.name })
                }
            }
        }
    }

    fun renderTab() {
        tabPanel.removeAll()
        context.hasUnsavedChanges = { false }
        when (tab) {
            PartyReportTab.OVERVIEW ->
                tabPanel.renderPartyReportOverview(
                    context = context,
                    onEdit = { unit ->
                        context.unit = unit
                        showTab(PartyReportTab.SHEET)
                    },
                    onReport = { showTab(PartyReportTab.REPORT) },
                )
            PartyReportTab.SHEET -> tabPanel.renderPartyReportSheetEditor(context)
            PartyReportTab.POSITIONS -> tabPanel.renderPartyReportPositions()
            PartyReportTab.REPORT -> tabPanel.renderPartyReportExport(context)
            PartyReportTab.AUSWEISE -> tabPanel.renderPartyReportAnnex(context)
            PartyReportTab.ERLAEUTERUNGEN -> tabPanel.renderPartyReportNotes(context)
            PartyReportTab.KONTENZUORDNUNG -> tabPanel.renderPartyReportMappingTab(context)
            PartyReportTab.KONSOLIDIERUNG -> tabPanel.renderPartyReportConsolidation(context)
        }
    }
    showTab = { chosen ->
        tab = chosen
        rememberTab(chosen)
        paintSegments()
        renderTab()
    }

    val yearSelect = yearToolbar.select(options = years, value = context.year.toString(), label = tr("Rechnungsjahr"))
    yearSelect.subscribe {
        val chosen = yearSelect.value?.toIntOrNull() ?: return@subscribe
        if (chosen == context.year) return@subscribe
        guardedChange(
            proceed = {
                context.year = chosen
                renderTab()
            },
            revert = { yearSelect.value = context.year.toString() },
        )
    }

    paintSegments()
    renderTab()

    // Closing or reloading the browser tab with typed-in values: the browser's own question.
    val beforeUnload: (Event) -> Unit = { event ->
        if (context.hasUnsavedChanges()) {
            event.preventDefault()
            event.asDynamic().returnValue = ""
        }
    }
    window.addEventListener("beforeunload", beforeUnload)
    // an empty marker whose destroy hook removes the listener (hook registered BEFORE the widget is added: addWithLifecycle)
    root.addWithLifecycle(Div(), onDestroy = { window.removeEventListener("beforeunload", beforeUnload) })
}

/** The service as the page uses it; one place, so a test can see which calls the page makes. */
internal fun partyReportRpc() = rpcService<IPartyReportService>()

internal fun unitLabel(unit: PartgUnitDto): String = partgUnitLabel(unit.level, unit.chapterName)

internal fun yearHint(year: Int): String = gettext("Rechnungsjahr %1", year)
