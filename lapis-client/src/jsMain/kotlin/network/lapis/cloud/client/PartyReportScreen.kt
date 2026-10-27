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

/** The three tabs of the party report page. */
internal enum class PartyReportTab { OVERVIEW, SHEET, POSITIONS }

/**
 * The state the three tabs share (V1.9.225): the report year and the unit picked for entry, plus a probe the page asks before something would
 * throw typed-in values away (changing the year, the unit or the tab, closing the browser tab).
 */
internal class PartyReportContext(
    var year: Int,
) {
    var unit: PartgUnitDto? = null
    var hasUnsavedChanges: () -> Boolean = { false }
}

private const val TAB_STORAGE_KEY = "lapis.partyReport.tab"

private fun rememberedTab(): PartyReportTab =
    runCatching { window.localStorage.getItem(TAB_STORAGE_KEY)?.let { PartyReportTab.valueOf(it) } }.getOrNull() ?: PartyReportTab.OVERVIEW

private fun rememberTab(tab: PartyReportTab) {
    runCatching { window.localStorage.setItem(TAB_STORAGE_KEY, tab.name) }
}

/**
 * V1.9.225 "Rechenschaftsbericht (PartG), R1" -- the page for the finance board of a political party: what was spent and received, per reporting
 * unit and year, typed in or imported from Excel. It creates NO report and NO PDF (that is a later wave); the intro says so plainly.
 *
 * Reachable for BOARD and ADMIN when the organisation is a political party (`SessionInfoDto.politicalParty`); the server enforces both on every
 * call. Three tabs: the overview of all units, entry of one unit, and the positions with the organisation's own hints.
 */
fun renderPartyReportScreen(container: SimplePanel) {
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
                "Hier erfassen Sie die Zahlen für den Rechenschaftsbericht. Das ist eine Arbeitshilfe, keine Rechtsberatung: Der Bericht " +
                    "selbst und seine Prüfung durch den Wirtschaftsprüfer folgen in einem späteren Schritt.",
            ),
        )
    }

    val context = PartyReportContext(year = organizationToday().year - 1)
    val yearToolbar = root.lapisToolbar()
    val years = (organizationToday().year downTo 2001).map { it.toString() to it.toString() }
    val segmentHost = root.simplePanel()
    val tabPanel = root.vPanel(spacing = 10)
    var tab = rememberedTab()

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

    fun paintSegments() {
        segmentHost.removeAll()
        val options =
            listOf(
                PartyReportTab.OVERVIEW to tr("Übersicht"),
                PartyReportTab.SHEET to tr("Werte erfassen"),
                PartyReportTab.POSITIONS to tr("Positionen"),
            )
        segmentHost.segmentedScroll {
            segmentedControl(options = options, selected = tab, ariaLabel = tr("Bereich")) { chosen ->
                if (chosen != tab) guardedChange(proceed = { showTab(chosen) }, revert = {})
            }
        }
    }

    fun renderTab() {
        tabPanel.removeAll()
        context.hasUnsavedChanges = { false }
        when (tab) {
            PartyReportTab.OVERVIEW ->
                tabPanel.renderPartyReportOverview(context) { unit ->
                    context.unit = unit
                    showTab(PartyReportTab.SHEET)
                }
            PartyReportTab.SHEET -> tabPanel.renderPartyReportSheetEditor(context)
            PartyReportTab.POSITIONS -> tabPanel.renderPartyReportPositions()
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
