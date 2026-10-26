package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.html.Span
import io.kvision.i18n.gettext
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.NavCountsDto
import network.lapis.cloud.shared.rpc.IDashboardService
import org.w3c.dom.events.Event

/** How often the sidebar counters reload themselves while the tab is visible (V1.9.224). */
internal const val NAV_COUNTS_REFRESH_MS = 60_000

/** The one RPC of the counters, injectable so a DOM test can feed (or refuse) figures. */
internal fun interface NavCountsRpc {
    suspend fun navCounts(): NavCountsDto
}

internal fun liveNavCountsRpc() = NavCountsRpc { rpcService<IDashboardService>().getNavCounts() }

/** Timer, visibility and clock of the poller behind a seam, so a DOM test can count what is running (like [OnlineCountsScheduler]). */
internal interface NavCountsScheduler {
    /** Starts a repeating timer; the result stops it. */
    fun every(
        ms: Int,
        block: () -> Unit,
    ): () -> Unit

    /** Starts listening for tab visibility changes; the result stops it. */
    fun onVisibilityChange(block: (visible: Boolean) -> Unit): () -> Unit

    fun isVisible(): Boolean

    fun nowMs(): Double
}

internal object WindowNavCountsScheduler : NavCountsScheduler {
    override fun every(
        ms: Int,
        block: () -> Unit,
    ): () -> Unit {
        val id = window.setInterval(block, ms)
        return { window.clearInterval(id) }
    }

    override fun onVisibilityChange(block: (visible: Boolean) -> Unit): () -> Unit {
        val listener: (Event) -> Unit = { block(isVisible()) }
        document.addEventListener("visibilitychange", listener)
        return { document.removeEventListener("visibilitychange", listener) }
    }

    override fun isVisible(): Boolean = document.asDynamic().visibilityState == "visible"

    override fun nowMs(): Double = js("Date.now()").unsafeCast<Double>()
}

/**
 * V1.9.224 -- the live "(aktiv/gesamt)" after "Videokonferenz" and "Begegnungsräume". One process-wide poller, not one per sidebar build: the sidebar
 * is rebuilt often (language change, phone off-canvas, session refresh) and every rebuild would otherwise leave a timer behind. [bind]
 * REPLACES the previous binding of its suffix (the old link is gone from the document), [start] is idempotent, [stop] ends everything
 * (sign-out). The server answer comes from the database only and is up to 90 seconds behind.
 *
 * A failure keeps the last figures, or shows nothing without any; there is never a toast. Nothing is requested while the tab is hidden; a tab
 * that becomes visible asks at once if the last request is more than a minute old.
 */
internal object NavCountsPoller {
    private class Binding(
        val suffix: NavLiveSuffix,
        val span: Span,
        val link: Link,
        val label: String,
    )

    private val bindings = mutableListOf<Binding>()
    private var last: NavCountsDto? = null
    private var owner: String? = null
    private var cancelTimer: (() -> Unit)? = null
    private var cancelVisibility: (() -> Unit)? = null
    private var rpc: NavCountsRpc? = null
    private var scheduler: NavCountsScheduler = WindowNavCountsScheduler
    private var inFlight = false
    private var generation = 0
    private var lastRequestMs = Double.NEGATIVE_INFINITY

    /** Registers the suffix [span] of [link] for [suffix]; renders the last known figures at once. */
    fun bind(
        suffix: NavLiveSuffix,
        span: Span,
        link: Link,
        label: String,
    ) {
        bindings.removeAll { it.suffix == suffix }
        val binding = Binding(suffix = suffix, span = span, link = link, label = label)
        bindings += binding
        last?.let { render(binding, it) }
    }

    /** Starts for the person [ownerId]; a call for the same person while running does nothing, another person starts afresh. */
    fun start(
        ownerId: String,
        rpc: NavCountsRpc = liveNavCountsRpc(),
        scheduler: NavCountsScheduler = WindowNavCountsScheduler,
    ) {
        if (owner == ownerId && cancelTimer != null) return
        if (owner != ownerId) {
            val keep = bindings.toList()
            stop()
            bindings += keep
        }
        owner = ownerId
        this.rpc = rpc
        this.scheduler = scheduler
        cancelTimer = scheduler.every(NAV_COUNTS_REFRESH_MS) { if (scheduler.isVisible()) request() }
        cancelVisibility =
            scheduler.onVisibilityChange { visible ->
                if (visible && scheduler.nowMs() - lastRequestMs > NAV_COUNTS_REFRESH_MS) request()
            }
        request()
    }

    /** Sign-out: no timer, no listener, no figures, no bindings. */
    fun stop() {
        cancelTimer?.invoke()
        cancelVisibility?.invoke()
        cancelTimer = null
        cancelVisibility = null
        bindings.clear()
        last = null
        owner = null
        rpc = null
        inFlight = false
        generation++
        lastRequestMs = Double.NEGATIVE_INFINITY
    }

    private fun request() {
        val call = rpc ?: return
        if (inFlight) return
        inFlight = true
        lastRequestMs = scheduler.nowMs()
        val requestedFor = owner
        val myGeneration = generation
        AppScope.launch {
            try {
                val dto = call.navCounts()
                // an answer for a person who has signed out meanwhile is dropped
                if (owner == requestedFor && requestedFor != null) {
                    last = dto
                    bindings.toList().forEach { render(it, dto) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // keep the last figures; no toast for a counter
            } finally {
                // a stop() since this request began already reset the flag; do not clear a newer request's
                if (generation == myGeneration) inFlight = false
            }
        }
    }

    private fun render(
        binding: Binding,
        dto: NavCountsDto,
    ) {
        val (active, total) =
            when (binding.suffix) {
                NavLiveSuffix.CONFERENCE -> dto.conferenceActive to dto.conferenceTotal
                NavLiveSuffix.ENCOUNTER -> dto.encounterActive to dto.encounterTotal
            }
        applyNavSuffix(span = binding.span, link = binding.link, label = binding.label, active = active, total = total)
    }

    internal fun lastForTest(): NavCountsDto? = last

    internal fun boundForTest(): Int = bindings.size
}

/**
 * Writes " (active/total)" into [span] and the matching accessible name onto [link]. `total == 0` clears everything: no empty "(0/0)", and
 * the link keeps its plain accessible name. The span itself is `aria-hidden`; the link carries the information ("Videokonferenz, 2 von 5 aktiv").
 */
internal fun applyNavSuffix(
    span: Span,
    link: Link,
    label: String,
    active: Int,
    total: Int,
) {
    if (total <= 0) {
        span.content = ""
        span.visible = false
        link.removeAttribute("aria-label")
        link.removeAttribute("title")
        return
    }
    span.content = " ($active/$total)"
    span.visible = true
    link.setAttribute("aria-label", gettext("%1, %2 von %3 aktiv", plainMenuText(label), active.toString(), total.toString()))
    link.setAttribute("title", gettext("%1 von %2 aktiv", active.toString(), total.toString()))
}
