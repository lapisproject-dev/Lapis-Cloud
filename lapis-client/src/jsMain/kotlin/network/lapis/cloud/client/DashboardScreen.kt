package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.Link
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import network.lapis.cloud.shared.rpc.IDashboardService
import org.w3c.dom.events.Event

/** A signal list that is older than this is fetched again when the tab becomes visible (V1.9.224). */
internal const val DASHBOARD_REFRESH_MS = 60_000

/**
 * The post-login landing page. Since V1.9.224 it answers one question -- "do I need to do anything today?" -- and shows ONLY what the menu cannot
 * tell: status chips, for an ADMIN the "Gerade angemeldet" block, and "Für Sie", one card per thing that concerns the person right now (a missing
 * vote, an outbid auction, an overdue contribution). The destinations themselves are the menu; the dashboard no longer repeats them as tiles.
 * Account actions (password, membership card, leaving) live on "Meine Daten" ([Routes.DSGVO_RIGHTS]); signing out is in the account menu.
 */
fun renderDashboardScreen(container: SimplePanel) = renderDashboardScreen(container, liveOnlineCountsRpc(), WindowOnlineCountsScheduler)

/** The one RPC of "Für Sie", injectable so a DOM test can feed (or refuse) signals. */
internal fun interface DashboardSignalsRpc {
    suspend fun signals(): List<DashboardSignalDto>
}

internal fun liveDashboardSignalsRpc() = DashboardSignalsRpc { rpcService<IDashboardService>().getDashboardSignals() }

/** Tab visibility and the clock of the dashboard behind a seam, so a DOM test can drive "the tab becomes visible again". */
internal interface DashboardVisibility {
    fun addListener(listener: (Event) -> Unit)

    fun removeListener(listener: (Event) -> Unit)

    fun isVisible(): Boolean

    fun nowMs(): Double
}

internal object WindowDashboardVisibility : DashboardVisibility {
    override fun addListener(listener: (Event) -> Unit) = document.addEventListener("visibilitychange", listener)

    override fun removeListener(listener: (Event) -> Unit) = document.removeEventListener("visibilitychange", listener)

    override fun isVisible(): Boolean = document.asDynamic().visibilityState == "visible"

    override fun nowMs(): Double = js("Date.now()").unsafeCast<Double>()
}

/** Test seam (V1.9.218): the RPCs, the scheduler and the visibility can be replaced; the public overload keeps the route signature `(SimplePanel) -> Unit`. */
internal fun renderDashboardScreen(
    container: SimplePanel,
    onlineCountsRpc: OnlineCountsRpc,
    onlineCountsScheduler: OnlineCountsScheduler,
    signalsRpc: DashboardSignalsRpc = liveDashboardSignalsRpc(),
    visibility: DashboardVisibility = WindowDashboardVisibility,
    storageSpaceRpc: StorageSpaceRpc = liveStorageSpaceRpc(),
    todaysAnniversariesRpc: TodaysAnniversariesRpc = liveTodaysAnniversariesRpc(),
) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }

    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    // W5: constant title as in the sidebar (R35); the greeting is a data value and therefore the subtitle.
    root.pageHeader(
        tr("Dashboard"),
        subtitle = gettext("Willkommen, %1", session.displayName),
        intro = PageIntros.forRoute(Routes.DASHBOARD),
    )

    // Status (member status, not just the permission level: a friend account has AccountRole.MEMBER), role and the end of the session.
    val chips = root.div(className = "lapis-status-chips")
    chips.statusChip("fas fa-id-badge", gettext("Status: %1", memberStatusLabel(session.status)))
    chips.statusChip("fas fa-user-shield", gettext("Rolle: %1", accountRoleLabel(session.role)))
    chips.statusChip("fas fa-clock", gettext("Sitzung bis %1", formatSystemDateTimeWithZone(session.expiresAt)), muted = true)

    // The one account hint the menu does not give as a destination of its own: where password, card and leaving are.
    if (NavVisibility.showsDsgvoRights(session.status)) {
        val hint = root.div(className = "small text-body-secondary lapis-dashboard-account-hint")
        hint.add(Span(tr("Passwort, Mitgliedsausweis und Austritt:")))
        hint.add(Span(" "))
        hint.link(tr("Meine Daten"), url = "#${Routes.DSGVO_RIGHTS}")
    }

    if (shouldShowOnlineCounts()) {
        val online = root.div(className = "lapis-dashboard-online")
        renderOnlineCountsCard(online, rpc = onlineCountsRpc, scheduler = onlineCountsScheduler)
    }

    // V1.9.276: only BOARD/ADMIN get the slot (and the RPC). It sits after "Für Sie" and is filled once "Für Sie" has settled for the
    // first time (that happens in a coroutine, so the slot below exists by then).
    var todaySlot: TodaysAnniversariesSlot? = null
    renderSignals(root, session, signalsRpc, visibility, storageSpaceRpc, onFirstSettled = { todaySlot?.signalsSettled() })
    if (showsTodaysAnniversaries(session)) todaySlot = TodaysAnniversariesSlot(root, todaysAnniversariesRpc)
}

private fun SimplePanel.statusChip(
    icon: String,
    text: String,
    muted: Boolean = false,
) {
    val chip = Span(className = if (muted) "lapis-status-chip lapis-status-chip--muted" else "lapis-status-chip")
    val glyph = Icon(icon)
    glyph.setAttribute("aria-hidden", "true")
    chip.add(glyph)
    chip.add(Span(text))
    add(chip)
}

/** Higher priority first, then the earlier deadline (none last), then module and reason -- the order the server sends, kept if it did not. */
internal fun sortSignals(signals: List<DashboardSignalDto>): List<DashboardSignalDto> =
    signals.sortedWith(
        compareByDescending<DashboardSignalDto> { it.priority }
            .thenBy(nullsLast()) { it.deadline }
            .thenBy { it.moduleKey }
            .thenBy { it.reasonKey },
    )

private fun storageTileClasses(level: StorageSpaceLevel): String =
    when (level) {
        StorageSpaceLevel.HINT -> "card lapis-storage-tile bg-warning-subtle border-warning-subtle"
        StorageSpaceLevel.WARNING -> "card lapis-storage-tile bg-danger-subtle border-danger-subtle lapis-storage-tile--warning"
        else -> "card lapis-storage-tile"
    }

/**
 * "Für Sie". The list is mounted with a one-line "Wird geladen …" so the page does not jump; an answer after the screen has left is dropped;
 * a failure or an empty answer ends in "Im Moment gibt es nichts Neues für Sie." (no toast, no retry loop). The list is fetched again when the
 * tab becomes visible and the last fetch is more than a minute old. The insert hook only starts the work: widget state changes happen inside the
 * coroutine (V1.9.218).
 */
private fun renderSignals(
    root: SimplePanel,
    session: SessionInfoDto,
    rpc: DashboardSignalsRpc,
    visibility: DashboardVisibility,
    storageRpc: StorageSpaceRpc,
    onFirstSettled: () -> Unit = {},
) {
    val section = Div(className = "lapis-dashboard-signals-section")
    section.setAttribute("aria-live", "polite")
    section.h2(tr("Für Sie")) { addCssClass("h5") }
    // V1.9.273: the storage-space tile (ADMIN only) always comes first, before the signal cards. Its room is reserved from the
    // first frame, so the cards below do not jump when the answer arrives. Without an ADMIN there is no slot at all.
    val storageSlot: Div? =
        if (showsStorageSpace(session)) {
            Div(className = "lapis-dashboard-storage lapis-dashboard-storage--reserved").also {
                it.setAttribute("aria-hidden", "true")
                section.add(it)
            }
        } else {
            null
        }
    val status = Div(className = "lapis-dashboard-signals__status small text-body-secondary")
    status.content = tr("Wird geladen …")
    section.add(status)
    val grid = Div(className = "lapis-dashboard-signals")
    section.add(grid)

    val context = NavContext(session = session, memberFinderOffered = null)
    var gone = false
    var started = false
    var inFlight = false
    var firstSettled = false
    var shown = false
    var signalCount = 0
    var storageSignals = false
    var storagePending = false
    var lastLoadMs = Double.NEGATIVE_INFINITY
    var listener: ((Event) -> Unit)? = null

    // The "nothing new" sentence stands only when there is no signal card AND no storage signal (hint or warning), whatever order the
    // answers arrive in. The tile at info or unknown level is no signal.
    fun updateStatus() {
        if (!shown) return
        when {
            signalCount > 0 || storageSignals -> status.visible = false
            storagePending -> {
                status.content = tr("Wird geladen …")
                status.visible = true
            }
            else -> {
                status.content = tr("Im Moment gibt es nichts Neues für Sie.")
                status.visible = true
            }
        }
    }

    fun show(signals: List<DashboardSignalDto>) {
        val cards = sortSignals(signals).mapNotNull { signalCard(it, context) }
        grid.removeAll()
        cards.forEach { grid.add(it) }
        shown = true
        signalCount = cards.size
        updateStatus()
    }

    // Once, no polling, no toast: a failure or a refused call removes the reserved slot (the one allowed shift).
    fun loadStorage() {
        val slot = storageSlot ?: return
        storagePending = true
        AppScope.launch {
            try {
                val dto = storageRpc.storageSpace()
                if (!gone) {
                    val level = storageBarModel(dto).level
                    val tile = Div(className = storageTileClasses(level))
                    tile.add(storageSpaceBar(dto, withLink = true))
                    slot.add(tile)
                    slot.removeCssClass("lapis-dashboard-storage--reserved")
                    slot.removeAttribute("aria-hidden")
                    storageSignals = level == StorageSpaceLevel.HINT || level == StorageSpaceLevel.WARNING
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!gone) section.remove(slot)
            } finally {
                storagePending = false
                if (!gone) updateStatus()
            }
        }
    }

    fun load() {
        if (inFlight) return
        inFlight = true
        lastLoadMs = visibility.nowMs()
        AppScope.launch {
            try {
                val signals = rpc.signals()
                if (!gone) show(signals)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // no toast, no retry: the loading line ends as the empty sentence, an earlier list stays
                if (!gone && !shown) show(emptyList())
            } finally {
                inFlight = false
                if (!firstSettled) {
                    firstSettled = true
                    if (!gone) onFirstSettled()
                }
            }
        }
    }

    root.addWithLifecycle(
        section,
        onInsert = {
            gone = false
            if (!started) {
                started = true
                load()
                if (showsStorageSpace(session)) loadStorage()
            }
            if (listener == null) {
                val l: (Event) -> Unit = {
                    if (visibility.isVisible() && visibility.nowMs() - lastLoadMs > DASHBOARD_REFRESH_MS) load()
                }
                listener = l
                visibility.addListener(l)
            }
        },
        onDestroy = {
            gone = true
            listener?.let { visibility.removeListener(it) }
            listener = null
        },
    )
}

/**
 * One card: the whole card is the link. Title = the menu label of the module, one reason line (cut with an ellipsis, the full text in `title`),
 * a count badge for more than one object. `null` (nothing drawn) for a reason this client has no text for, for parameters of the wrong shape,
 * and for a module the person's menu does not show.
 */
internal fun signalCard(
    signal: DashboardSignalDto,
    context: NavContext,
): Link? {
    val entry = DashboardSignalRoutes.entryFor(signal.moduleKey, context) ?: return null
    if (!DashboardSignalRoutes.isInMenu(signal.moduleKey, context)) return null
    val route = DashboardSignalRoutes.route(signal.moduleKey, signal.objectId) ?: return null
    val text = DashboardSignalTexts.text(signal) ?: return null

    val urgent = signal.priority >= DashboardPriority.URGENT
    val card = Link(label = "", url = "#$route", className = "card lapis-signal-card" + if (urgent) " lapis-signal-card--urgent" else "")

    val head = Span(className = "lapis-signal-card__head")
    val glyph = Icon(entry.icon)
    glyph.setAttribute("aria-hidden", "true")
    head.add(glyph)
    head.add(Span(content = entry.label, className = "lapis-signal-card__title"))
    if (signal.count > 1) {
        val badge = Span(content = signal.count.toString(), className = "badge text-bg-secondary lapis-signal-card__count")
        badge.setAttribute("role", "img")
        badge.setAttribute("aria-label", gettext("%1 Einträge", signal.count.toString()))
        head.add(badge)
    }
    card.add(head)
    val reason = Span(content = text, className = "lapis-signal-card__reason")
    reason.setAttribute("title", text)
    card.add(reason)
    return card
}
