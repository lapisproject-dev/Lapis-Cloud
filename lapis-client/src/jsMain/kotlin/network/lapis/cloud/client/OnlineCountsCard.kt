package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OnlineCountsDto
import network.lapis.cloud.shared.domain.OnlineGroupCountsDto
import network.lapis.cloud.shared.rpc.IOnlineCountsService
import org.w3c.dom.events.Event

/** How often the tile reloads itself while its tab is visible (V1.9.215). */
internal const val ONLINE_COUNTS_REFRESH_MS = 60_000

/** The one RPC of the tile, injectable so a DOM test can feed figures. */
internal fun interface OnlineCountsRpc {
    suspend fun onlineCounts(): OnlineCountsDto
}

/**
 * The timer and the `visibilitychange` listener of the tile, behind a seam so a DOM test can count what is running (V1.9.218).
 * The default delegates to `window` and `document`.
 */
internal interface OnlineCountsScheduler {
    fun setInterval(
        callback: () -> Unit,
        ms: Int,
    ): Int

    fun clearInterval(id: Int)

    fun addVisibilityListener(listener: (Event) -> Unit)

    fun removeVisibilityListener(listener: (Event) -> Unit)
}

internal object WindowOnlineCountsScheduler : OnlineCountsScheduler {
    override fun setInterval(
        callback: () -> Unit,
        ms: Int,
    ): Int = window.setInterval(callback, ms)

    override fun clearInterval(id: Int) = window.clearInterval(id)

    override fun addVisibilityListener(listener: (Event) -> Unit) = document.addEventListener("visibilitychange", listener)

    override fun removeVisibilityListener(listener: (Event) -> Unit) = document.removeEventListener("visibilitychange", listener)
}

internal fun liveOnlineCountsRpc() = OnlineCountsRpc { rpcService<IOnlineCountsService>().onlineCounts() }

/** The tile is for ADMIN only (the server enforces the same; BOARD does not imply it). */
internal fun shouldShowOnlineCounts(): Boolean = AppState.hasRole(AccountRole.ADMIN)

/** "Mitglieder a · Freunde b · Gäste c" -- plus "· Sonstige d" only when [OnlineGroupCountsDto.other] is above zero. Whole sentences, never glued from pieces. */
internal fun onlineBreakdownText(c: OnlineGroupCountsDto): String {
    val members = c.members.toString()
    val friends = c.friends.toString()
    val guests = c.guests.toString()
    return if (c.other > 0) {
        gettext("Mitglieder %1 · Freunde %2 · Gäste %3 · Sonstige %4", members, friends, guests, c.other.toString())
    } else {
        gettext("Mitglieder %1 · Freunde %2 · Gäste %3", members, friends, guests)
    }
}

/**
 * V1.9.215 -- the admin dashboard tile "Gerade angemeldet": how many people are active right now and how many are signed in at all,
 * each split by group. Counts only. Reloads once a minute while the tab is visible (and at once when it becomes visible again), by button on demand; never two
 * requests at a time. A failed reload keeps the last figures and says so quietly; only that message is a live region, so the silent refresh is
 * never announced.
 *
 * The timer and the `visibilitychange` listener start when the tile really is in the document and end when it leaves ([addWithLifecycle]).
 *
 * V1.9.218 -- why the tile appeared twice on the admin dashboard: the insert hook ran `load()`, which disabled the refresh button
 * synchronously. A widget change inside the insert hook re-renders the root while the first patch (the one inserting the tile) is still
 * running; that nested patch starts from the old vnode, builds the tile a second time, and the outer patch then finishes as well. The
 * second element was never updated again ("Wird geladen …" for good). Measured with a probe: without the hooks (plain `add`) the tile
 * is single, so are the hooks the trigger; the same page with the button change moved into the coroutine is single again. Rule: an
 * insert hook of [addWithLifecycle] only touches the DOM or starts work; widget state changes wait for the coroutine.
 */
internal fun renderOnlineCountsCard(
    root: SimplePanel,
    rpc: OnlineCountsRpc = liveOnlineCountsRpc(),
    intervalMs: Int = ONLINE_COUNTS_REFRESH_MS,
    isTabVisible: () -> Boolean = { document.asDynamic().visibilityState == "visible" },
    scheduler: OnlineCountsScheduler = WindowOnlineCountsScheduler,
) {
    val card = Div(className = "card")
    val body = card.div(className = "card-body")
    body.h2(tr("Gerade angemeldet")) { addCssClass("h5") }

    val activeValue = Div(className = "fs-4 fw-semibold lapis-tabular-nums")
    val activeSplit = Div(className = "small")
    val signedInValue = Div(className = "lapis-tabular-nums")
    val signedInSplit = Div(className = "small")
    val explanation = Div(className = "small text-body-secondary mb-2")

    fun row(
        label: String,
        value: Div,
        split: Div,
    ) {
        body.div(className = "mb-2") {
            div(label, className = "text-body-secondary small")
            add(value)
            add(split)
        }
    }
    row(tr("Gerade aktiv"), activeValue, activeSplit)
    row(tr("Angemeldet insgesamt"), signedInValue, signedInSplit)
    body.add(explanation)
    activeValue.content = tr("Wird geladen …")

    lateinit var refreshButton: Button
    lateinit var stampText: Div
    body.actionGroup {
        refreshButton = actionButton(ActionIcon.REFRESH, tr("Aktualisieren"))
        stampText = div("", className = "small align-self-center")
    }
    val error = Div("", className = "small text-danger mt-1")
    error.setAttribute("aria-live", "polite")
    body.add(error)

    var inFlight = false
    var started = false
    var timerId: Int? = null
    var visibilityListener: ((Event) -> Unit)? = null

    fun show(dto: OnlineCountsDto) {
        val active = dto.active
        val signedIn = dto.signedIn
        val windowMinutes = dto.activeWindowMinutes.toString()
        val sessionHours = dto.sessionTtlHours.toString()
        val computedAt = dto.computedAt
        val activeTotal = active.total.toString()
        val signedInTotal = signedIn.total.toString()
        activeValue.content = activeTotal
        activeSplit.content = onlineBreakdownText(active)
        signedInValue.content = signedInTotal
        signedInSplit.content = onlineBreakdownText(signedIn)
        explanation.content =
            gettext(
                "„Gerade aktiv“ zählt alle, die in den letzten %1 Minuten etwas getan haben. " +
                    "Eine Anmeldung bleibt bis zu %2 Stunden gültig, deshalb kann „Angemeldet insgesamt“ höher sein. Sie selbst sind mitgezählt.",
                windowMinutes,
                sessionHours,
            )
        stampText.content = gettext("Stand: %1 Uhr", formatSystemTime(computedAt))
    }

    fun load() {
        if (inFlight) return
        inFlight = true
        AppScope.launch {
            // Inside the coroutine on purpose: the first load runs from the insert hook, and a widget change made synchronously there
            // re-renders the root in the middle of the patch that is inserting the tile (V1.9.218, see the KDoc of the function).
            refreshButton.disabled = true
            try {
                val dto = rpc.onlineCounts()
                show(dto)
                error.content = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.content = tr("Die Zahlen konnten gerade nicht geladen werden.")
            } finally {
                inFlight = false
                refreshButton.disabled = false
            }
        }
    }

    refreshButton.onClick { load() }

    root.addWithLifecycle(
        card,
        onInsert = {
            // May fire more than once per widget (insert, destroy, insert on a re-attach): the first load happens once, the timers once per attach.
            if (!started) {
                started = true
                load()
            }
            if (timerId == null) {
                timerId = scheduler.setInterval({ if (isTabVisible()) load() }, intervalMs)
                val listener: (Event) -> Unit = { if (isTabVisible()) load() }
                visibilityListener = listener
                scheduler.addVisibilityListener(listener)
            }
        },
        onDestroy = {
            timerId?.let { scheduler.clearInterval(it) }
            timerId = null
            visibilityListener?.let { scheduler.removeVisibilityListener(it) }
            visibilityListener = null
        },
    )
}
