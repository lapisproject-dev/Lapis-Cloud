package network.lapis.cloud.client

import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.h3
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IOrganizationTimeZoneService
import org.w3c.dom.HTMLDetailsElement
import org.w3c.dom.HTMLElement

/*
 * V1.9.221 -- the ADMIN page "Organisation": the settings that apply to the whole organization, gathered in one place instead of cards
 * scattered over the member administration. Five sections, each a native `<details>` (closed at first, keyboard and screen reader work
 * without any script): general (time zone, public member count), video conference, recording & transmission, member finder, tenders.
 *
 * Rules of the page (docs/features/organisation-settings.adoc):
 *  - The `<summary>` shows an icon (decoration, `aria-hidden`), the title and ONE line with the current values, so most visits need no opening.
 *  - The content of a section is built when it is opened for the first time, never twice, so a section nobody opens makes no RPC call.
 *  - A saved change refreshes the summary lines; the section stays open.
 *  - Deep link: `#/organization?section=<key>` opens that section and scrolls to it. Old names are resolved by [resolveOrganizationSection];
 *    an unknown key is ignored. The key is only ever looked up in a fixed map, never rendered or interpreted as HTML.
 *  - The page is ADMIN-only (route gate); every RPC behind it enforces ADMIN again on the server.
 */

/** The keys of the sections, in page order. They are also the `section` values of a deep link and the ids (`org-<key>`) of the sections. */
internal val ORGANIZATION_SECTION_KEYS = listOf("allgemein", "videokonferenz", "aufzeichnung", "mitglieder-finder", "ausschreibungen")

/** Names under which a single setting is linked to; each resolves to the section that holds it. */
internal val ORGANIZATION_SECTION_ALIASES =
    mapOf(
        "zeitzone" to "allgemein",
        "mitgliederzahl" to "allgemein",
        "overlays" to "videokonferenz",
        "stream" to "aufzeichnung",
    )

/** The section a deep link [raw] points at, or null when it is missing or unknown (then the page simply opens nothing). */
internal fun resolveOrganizationSection(raw: String?): String? {
    val key = raw?.trim()?.lowercase().orEmpty()
    return when {
        key in ORGANIZATION_SECTION_KEYS -> key
        else -> ORGANIZATION_SECTION_ALIASES[key]
    }
}

/** What the summary lines are made of. Filled by [loadSummaries]; a field stays null when its call failed. */
private class OrganizationSummaryState {
    var settings: OrganizationSettingsDto? = null
    var zoneId: String? = null
}

private class OrganizationSection(
    val key: String,
    val icon: String,
    val title: String,
    val summary: (OrganizationSummaryState) -> String,
    /** Builds the content into the panel; the second argument refreshes the summary lines after a saved change. */
    val build: (SimplePanel, () -> Unit) -> Unit,
)

private fun onOff(value: Boolean): String = if (value) gettext("an") else gettext("aus")

private fun organizationSections(): List<OrganizationSection> =
    listOf(
        OrganizationSection(
            key = "allgemein",
            icon = "fas fa-clock",
            title = tr("Allgemein"),
            summary = { state ->
                val settings = state.settings
                if (settings == null && state.zoneId == null) {
                    ""
                } else {
                    gettext(
                        "Zeitzone: %1 · Mitgliederzahl öffentlich: %2",
                        state.zoneId ?: "–",
                        settings?.let { onOff(it.showPublicMemberCount) } ?: "–",
                    )
                }
            },
            build = { panel, refresh ->
                renderOrganizationTimeZoneSettings(panel) { refresh() }
                panel.h3(tr("Öffentliche Sichtbarkeit")) { addCssClasses("h6 mt-3") }
                renderPublicMemberCountSwitch(panel) { refresh() }
            },
        ),
        OrganizationSection(
            key = "videokonferenz",
            icon = "fas fa-video",
            title = tr("Videokonferenz"),
            summary = { state ->
                state.settings?.let {
                    gettext("Rauschunterdrückung: %1 · Overlays: %2", onOff(it.noiseSuppressionDefault), onOff(it.videoOverlaysOffered))
                } ?: ""
            },
            build = { panel, refresh -> renderConferenceVideoSettings(panel) { refresh() } },
        ),
        OrganizationSection(
            key = "aufzeichnung",
            icon = "fas fa-circle-dot",
            title = tr("Aufzeichnung & Übertragung"),
            summary = { state ->
                state.settings?.let {
                    gettext(
                        "Pulpit-Aufzeichnung: %1 · Veranstaltungen: %2",
                        onOff(it.pulpitRecordingEnabled),
                        onOff(it.eventCaptureEnabled),
                    )
                } ?: ""
            },
            build = { panel, refresh -> renderConferenceRecordingSettings(panel) { refresh() } },
        ),
        OrganizationSection(
            key = "mitglieder-finder",
            icon = "fas fa-magnifying-glass-location",
            title = tr("Mitglieder-Finder"),
            summary = { state -> state.settings?.let { gettext("Mitglieder-Finder: %1", onOff(it.memberFinderEnabled)) } ?: "" },
            build = { panel, refresh -> renderMemberFinderSettings(panel) { refresh() } },
        ),
        OrganizationSection(
            key = "ausschreibungen",
            icon = "fas fa-gavel",
            title = tr("Ausschreibungen"),
            summary = { state ->
                state.settings?.let { gettext("Standard-Sichtbarkeit der Angebote: %1", tenderVisibilityLabel(it.tenderDefaultVisibility)) }
                    ?: ""
            },
            build = { panel, refresh -> renderTenderDefaultsSettings(panel) { refresh() } },
        ),
    )

/** Reads what the summary lines show. Each call is independent: a failure leaves that part empty, the sections stay usable. */
private suspend fun loadSummaries(state: OrganizationSummaryState) {
    guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }?.let { state.settings = it }
    guarded { rpcService<IOrganizationTimeZoneService>().getOrganizationTimeZone() }?.let { state.zoneId = it.zoneId }
}

/** The page Organisation. [section] is the (unchecked) `section` value of the deep link; see [resolveOrganizationSection]. */
fun renderOrganizationScreen(
    container: SimplePanel,
    section: String? = null,
) {
    val root = container.pageShell(PageWidth.FORM, spacing = 14)
    root.pageHeader(tr("Organisation"), intro = PageIntros.forRoute(Routes.ORGANIZATION))
    val state = OrganizationSummaryState()
    val specs = organizationSections()
    val summaryLines = mutableMapOf<String, Span>()

    fun showSummaries() = specs.forEach { summaryLines[it.key]?.content = it.summary(state) }

    fun refresh() {
        AppScope.launch {
            loadSummaries(state)
            showSummaries()
        }
    }

    val wanted = resolveOrganizationSection(section)
    specs.forEach { spec ->
        val details = Tag(TAG.DETAILS, className = "lapis-settings-section border rounded")
        details.setAttribute("id", "org-${spec.key}")
        val head = Tag(TAG.SUMMARY, className = "lapis-settings-summary")
        head.add(Span("", className = "${spec.icon} lapis-settings-icon").apply { setAttribute("aria-hidden", "true") })
        head.add(Span(spec.title, className = "lapis-settings-title"))
        val line = Span("", className = "lapis-settings-value text-muted small text-truncate")
        summaryLines[spec.key] = line
        head.add(line)
        details.add(head)
        val body = SimplePanel(className = "lapis-settings-body p-3")
        details.add(body)
        root.add(details)

        var built = false

        fun ensureBuilt() {
            if (built) return
            built = true
            spec.build(body) { refresh() }
        }

        if (spec.key == wanted) {
            details.setAttribute("open", "")
            ensureBuilt()
            whenElementReady(details) { element -> scrollToStart(element) }
        }
        whenElementReady(details) { element ->
            element.addEventListener("toggle", { if ((element as? HTMLDetailsElement)?.open == true) ensureBuilt() })
        }
    }
    refresh()
}

/**
 * The pointer that replaces the cards on the member administration (ADMIN only): the settings moved, and there is no old address to redirect
 * from (the cards never had a route of their own), so it is a line with a link instead of a redirect.
 */
internal fun renderOrganizationMovedHint(root: SimplePanel) {
    val band = root.vPanel(spacing = 4) { addCssClasses("border rounded p-3 mt-2") }
    band.p(
        tr(
            "Die organisationsweiten Einstellungen (Zeitzone, Videokonferenz, Aufzeichnung, Mitglieder-Finder, Ausschreibungen) " +
                "finden Sie jetzt unter Organisation.",
        ),
    ) { addCssClasses("mb-0") }
    band.link(tr("Zu Organisation"), url = "#${Routes.ORGANIZATION}")
}

private const val ELEMENT_RETRIES = 60
private const val ELEMENT_RETRY_MS = 16

/** KVision creates the DOM element asynchronously: calls [action] with it as soon as it exists (polled for about a second). */
private fun whenElementReady(
    widget: Tag,
    action: (HTMLElement) -> Unit,
) {
    var attempt = 0

    fun tick() {
        val element = widget.getElement()
        if (element != null) {
            action(element)
        } else if (attempt++ < ELEMENT_RETRIES) {
            window.setTimeout({ tick() }, ELEMENT_RETRY_MS)
        }
    }
    tick()
}

private fun scrollToStart(element: HTMLElement) {
    val reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    val options = js("({ block: 'start' })")
    options.behavior = if (reduced) "auto" else "smooth"
    element.asDynamic().scrollIntoView(options)
}
