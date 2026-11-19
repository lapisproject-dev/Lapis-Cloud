package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.text.Text
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartnerCarpoolHintDto
import network.lapis.cloud.shared.domain.PartnerCarpoolHintPageDto

/*
 * V1.9.317 "Mitfahrzentrale-Foederation" W5 -- the hints of partner organisations, shown to ACTIVE members below the own list. Four facts per row
 * (day, the two postcode areas, the host of the origin) and NOTHING to act on: no link, no button, no booking. Every foreign value (host, areas)
 * goes through the `untrusted*` helpers. A failure of the availability call means "show nothing" (a FRIEND or a passenger is refused there).
 */

/** What the list shows right now. */
internal sealed interface PartnerHintsState {
    data object Loading : PartnerHintsState

    /** [items] stay visible; [target] is the page that failed and the one a retry asks for. */
    class Failed(
        val items: List<PartnerCarpoolHintDto>,
        val target: Int,
    ) : PartnerHintsState

    class Loaded(
        val items: List<PartnerCarpoolHintDto>,
        val hasMore: Boolean,
        val page: Int,
    ) : PartnerHintsState
}

/**
 * The section. Built only after the exchange is known to be on ([mountPartnerCarpoolHints]); [loadPage] is the RPC (a lambda, so a test can feed it).
 * Known limit of offset paging: rows can shift between two requests, which can skip or repeat an entry; a repeat looks identical and does no harm.
 */
internal class PartnerCarpoolHintsSection(
    container: Container,
    private val loadPage: suspend (Int) -> PartnerCarpoolHintPageDto,
) {
    private val box = container.vPanel(spacing = 4)
    private val filter: Text
    private val listBox: SimplePanel
    private val footer: SimplePanel
    private val items = mutableListOf<PartnerCarpoolHintDto>()
    private var page = 0
    private var hasMore = false
    private var failed = false
    private var failedTarget = 0
    private var loading = false

    init {
        box.h2(tr("Angebote von Partnerorganisationen")) { addCssClass("h5") }
        box.div(
            tr(
                "Kurzhinweise von Partnerorganisationen: nur Tag und PLZ-Gebiete, keine Namen und keine Kontaktdaten. " +
                    "Einzelheiten gibt es nur bei der jeweiligen Organisation.",
            ),
            className = "text-muted small",
        )
        filter = Text(label = tr("PLZ-Gebiet"))
        filter.input.setAttribute("inputmode", "numeric")
        filter.input.setAttribute("maxlength", "2")
        box.add(filter)
        listBox = box.vPanel(spacing = 2)
        footer = box.vPanel(spacing = 2)
        filter.subscribe {
            val clean = PartnerCarpoolHintsUiLogic.sanitizeFilter(filter.value.orEmpty())
            if (clean != filter.value.orEmpty()) filter.value = clean
            renderList()
        }
    }

    /** The first page. */
    fun start() {
        items.clear()
        page = 0
        hasMore = false
        fetch(0)
    }

    private fun fetch(target: Int) {
        if (loading) return
        loading = true
        failed = false
        renderList()
        AppScope.launch {
            val result = runCatching { loadPage(target) }
            loading = false
            val answer = result.getOrNull()
            if (answer == null) {
                failed = true
                failedTarget = target
            } else {
                if (target == 0) items.clear()
                items += answer.items
                page = target
                hasMore = answer.hasMore
            }
            renderList()
        }
    }

    /** Draws the current state; synchronous, so a test can drive it with [showState]. */
    private fun renderList() {
        val state: PartnerHintsState =
            when {
                loading && items.isEmpty() -> PartnerHintsState.Loading
                failed -> PartnerHintsState.Failed(items = items.toList(), target = failedTarget)
                else -> PartnerHintsState.Loaded(items = items.toList(), hasMore = hasMore, page = page)
            }
        showState(state)
    }

    /** Renders [state]; the list is rebuilt from the loaded items only (a filter change never asks the server). */
    internal fun showState(state: PartnerHintsState) {
        listBox.removeAll()
        footer.removeAll()
        when (state) {
            PartnerHintsState.Loading ->
                listBox.div(tr("Die Hinweise werden geladen …"), className = "text-muted small") {
                    setAttribute("role", "status")
                }
            is PartnerHintsState.Failed -> {
                // A failed page means further pages exist: an empty filter result is not definitive.
                if (state.items.isNotEmpty()) renderItems(state.items, hasMore = true)
                footer.div(
                    tr("Die Hinweise konnten nicht geladen werden."),
                    className = "text-danger small",
                ) { setAttribute("role", "alert") }
                val retry = newActionButton(ActionIcon.REFRESH, tr("Erneut versuchen"))
                retry.onClick { fetch(state.target) }
                footer.add(retry)
            }
            is PartnerHintsState.Loaded -> {
                renderItems(state.items, state.hasMore)
                if (PartnerCarpoolHintsUiLogic.canLoadMore(hasMore = state.hasMore, page = state.page)) {
                    val more = newActionButton(ActionIcon.NEXT, tr("Weitere laden"))
                    more.onClick { fetch(state.page + 1) }
                    footer.add(more)
                }
            }
        }
    }

    private fun renderItems(
        all: List<PartnerCarpoolHintDto>,
        hasMore: Boolean,
    ) {
        val needle = PartnerCarpoolHintsUiLogic.sanitizeFilter(filter.value.orEmpty())
        val visible = all.filter { PartnerCarpoolHintsUiLogic.matches(it, needle) }
        val empty =
            PartnerCarpoolHintsUiLogic.emptyText(
                totalLoaded = all.size,
                visible = visible.size,
                filter = needle,
                hasMore = hasMore,
            )
        if (empty != null) listBox.div(empty, className = "text-muted small")
        visible.forEach { renderRow(listBox, it) }
    }

    private fun renderRow(
        into: SimplePanel,
        hint: PartnerCarpoolHintDto,
    ) {
        val row = into.vPanel(spacing = 0) { addCssClasses("border-bottom pb-1") }
        val first = row.div(className = "d-flex flex-wrap gap-2")
        first.untrustedSpan(formatDate(hint.departureDate), className = "fw-bold")
        first.untrustedSpan(PartnerCarpoolHintsUiLogic.routeText(hint))
        row.untrustedDiv(PartnerCarpoolHintsUiLogic.originText(hint.originHost), className = "text-muted small text-truncate") {
            setAttribute("title", hint.originHost)
        }
    }
}

/**
 * Mounts the section below [container] only for a member and only after the availability call says the exchange is on. A failure of that call
 * (a FRIEND or a passenger is refused there) means nothing is shown, never an error.
 */
internal fun mountPartnerCarpoolHints(
    container: Container,
    isMember: Boolean,
    readEnabled: suspend () -> Boolean,
    loadPage: suspend (Int) -> PartnerCarpoolHintPageDto,
) {
    if (!isMember) return
    val slot = container.vPanel()
    AppScope.launch {
        val enabled = runCatching { readEnabled() }.getOrDefault(false)
        if (enabled) PartnerCarpoolHintsSection(slot, loadPage).start()
    }
}
