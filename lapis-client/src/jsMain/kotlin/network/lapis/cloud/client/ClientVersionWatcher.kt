package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.fetch.NO_STORE
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.SAME_ORIGIN
import kotlin.random.Random

private const val BUILD_META_NAME = "lapis-client-build"

/** The un-injected dev default of `index.html`'s `<meta>` -- means "unknown build", never checked. */
private const val BUILD_META_DEV_SENTINEL = "dev"
private const val RELOAD_ATTEMPTS_KEY = "lapis-cloud-version-reload-attempts"
private const val RELOAD_BUILD_KEY = "lapis-cloud-version-reload-build"
private const val VERSION_ENDPOINT = "/api/client-version"
private const val CONTROLS_ROW_SELECTOR = ".lapis-conference-controls-row"
private const val MAX_RESPONSE_CHARS = 128

/**
 * This tab's own build id, read from `<meta name="lapis-client-build">` (server-injected, see
 * `ClientVersionHtml`); `null` when the element is missing, blank, the dev sentinel `dev`, or
 * implausible ([isPlausibleBuildId]) -- in all those cases nothing is ever checked.
 */
internal fun readOwnBuildId(): String? {
    val raw =
        document
            .querySelector("meta[name=\"$BUILD_META_NAME\"]")
            ?.getAttribute("content")
            ?.trim()
    return raw?.takeIf { it != BUILD_META_DEV_SENTINEL && isPlausibleBuildId(it) }
}

/** Reload clicks so far in this browser session (tab-scoped `sessionStorage`); `0` when storage is unavailable. */
internal fun readReloadAttempts(): Int =
    try {
        sessionStorage.getItem(RELOAD_ATTEMPTS_KEY)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    } catch (e: Throwable) {
        0
    }

internal fun storeReloadAttempts(value: Int) {
    try {
        sessionStorage.setItem(RELOAD_ATTEMPTS_KEY, value.toString())
    } catch (e: Throwable) {
        // storage blocked/full -- the reload budget then only lives in memory, harmless.
    }
}

/** The own build id at the last "Neu laden" click in this tab; `null` when none/unavailable. */
internal fun readReloadBuildId(): String? =
    try {
        sessionStorage.getItem(RELOAD_BUILD_KEY)?.takeIf { isPlausibleBuildId(it) }
    } catch (e: Throwable) {
        null
    }

internal fun storeReloadBuildId(value: String?) {
    try {
        if (value == null) sessionStorage.removeItem(RELOAD_BUILD_KEY) else sessionStorage.setItem(RELOAD_BUILD_KEY, value)
    } catch (e: Throwable) {
        // storage blocked/full -- harmless, see storeReloadAttempts.
    }
}

private fun nowMs(): Long =
    kotlin.js.Date
        .now()
        .toLong()

private fun documentHidden(): Boolean = document.asDynamic().hidden == true

/**
 * Welle V1.4.20 "Client-Hinweis: Neue Version verfuegbar" -- the browser adapter around the pure
 * state machine in `ClientVersionCheck.kt`. A long-lived tab keeps running the bundle it was loaded
 * with; after a server deploy its RPC calls can start failing against a newer server contract. This
 * watcher polls `GET /api/client-version` (delay FIRST, then every ~5 min with jitter/backoff, and
 * on tab re-focus), compares against [readOwnBuildId], and on a mismatch shows a non-blocking pill
 * with "Neu laden" / "Ausblenden". Never a toast, never an auto-reload, never a modal.
 *
 * **Global and started exactly once** ([start], idempotent), NOT per screen: ONE polling coroutine
 * that ends by itself at the first detected mismatch, and ONE `visibilitychange` listener for the
 * whole page life -- deliberately never removed, which is not a leak (a single listener for the
 * lifetime of the page, as opposed to one per screen). Without a known own id ([readOwnBuildId]
 * `null`: dev build, tests) it builds the (hidden) markup but registers no listener and starts no
 * loop -- zero network traffic.
 *
 * **Markup.** The `role="status" aria-live="polite"` wrapper is permanently mounted; the pill is
 * its toggled child. KVision removes a `visible = false` child from the DOM entirely, and a live
 * region that is inserted TOGETHER with its content is not announced by screen readers -- so the
 * live region itself must outlive the pill.
 */
internal object ClientVersionWatcher {
    private var started = false
    private var state = VersionCheckState()
    private var checking = false
    private var snoozeWake: Job? = null
    private var pill: Widget? = null
    private var reloadButton: Button? = null

    /** Builds the hint markup in [container] and (given a known own build id) starts polling. Idempotent. */
    fun start(container: SimplePanel) {
        if (started) return
        started = true
        val ownId = readOwnBuildId()
        val storedAttempts = readReloadAttempts()
        val attempts =
            effectiveReloadAttempts(storedAttempts = storedAttempts, storedBuildId = readReloadBuildId(), ownBuildId = ownId)
        if (attempts != storedAttempts) storeReloadAttempts(attempts)
        state = VersionCheckState(ownBuildId = ownId, reloadAttempts = attempts)
        buildMarkup(container)
        if (state.ownBuildId == null) return

        document.addEventListener("visibilitychange", {
            if (!documentHidden()) {
                AppScope.launch { safeTick() }
            }
            render()
        })
        AppScope.launch {
            while (!state.outdated) {
                delay(nextDelayMs(state = state, jitter01 = Random.nextDouble()))
                safeTick()
            }
        }
    }

    /** Called from `guarded()` on every failed RPC -- lifts a snooze while outdated. */
    fun notifyRpcFailure() {
        try {
            state = onRpcFailure(state)
        } catch (e: Throwable) {
            // never let the hint break the caller (`guarded()` must not throw).
        }
        render()
    }

    /** Called from [ConferenceCallPresence] whenever a call becomes live / stops being live. */
    fun notifyCallLiveChanged() {
        render()
    }

    /**
     * [tick] that never throws (except cancellation): the poll loop runs on `AppScope`, whose plain
     * `Job` (no `SupervisorJob`) would cancel EVERY other app coroutine if this one failed.
     */
    private suspend fun safeTick() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // a failing hint must never take the app down.
        }
    }

    private suspend fun tick() {
        if (checking || !shouldCheckNow(state = state, nowMs = nowMs(), visible = !documentHidden())) return
        checking = true
        try {
            val serverId = fetchServerBuildId()
            state =
                if (serverId == null) {
                    onFetchFailed(state = state, nowMs = nowMs())
                } else {
                    onFetched(state = state, serverBuildId = serverId, nowMs = nowMs())
                }
        } finally {
            checking = false
        }
        render()
    }

    /** `null` on any failure (network, non-2xx, unreadable body) -- never a toast, never a rethrow (except cancellation). */
    private suspend fun fetchServerBuildId(): String? =
        try {
            val response =
                window
                    .fetch(
                        VERSION_ENDPOINT,
                        RequestInit(method = "GET", credentials = RequestCredentials.SAME_ORIGIN, cache = RequestCache.NO_STORE),
                    ).await()
            val declaredLength = response.headers.get("content-length")?.toLongOrNull()
            if (response.ok && (declaredLength == null || declaredLength <= MAX_RESPONSE_CHARS)) {
                response
                    .text()
                    .await()
                    .trim()
                    .take(MAX_RESPONSE_CHARS)
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    /** Never throws: rendering the hint runs from `guarded()`'s catch path and the app-wide poll loop. */
    private fun render() {
        try {
            renderUnsafe()
        } catch (e: Throwable) {
            // a widget failing to re-render must not break the caller.
        }
    }

    private fun renderUnsafe() {
        val currentPill = pill ?: return
        val show = shouldShowBanner(state = state, nowMs = nowMs(), callLive = ConferenceCallPresence.live)
        if (show) {
            reloadButton?.visible = shouldShowReloadButton(state)
            // DOM query instead of guessing the route: self-correcting, and it also covers the
            // Failed / post-call states in which the fixed conference control bar still stands.
            if (document.querySelector(CONTROLS_ROW_SELECTOR) != null) {
                currentPill.addCssClass(LIFTED_CLASS)
            } else {
                currentPill.removeCssClass(LIFTED_CLASS)
            }
        }
        currentPill.visible = show
    }

    private fun onReloadClicked() {
        state = onReloadRequested(state)
        storeReloadAttempts(state.reloadAttempts)
        storeReloadBuildId(state.ownBuildId)
        window.location.reload()
    }

    private fun onDismissClicked() {
        val now = nowMs()
        state = onDismiss(state = state, nowMs = now)
        render()
        // The polling loop has ended by now (outdated is terminal), so nothing else would re-render
        // when the snooze runs out -- wake up once for that.
        snoozeWake?.cancel()
        snoozeWake =
            AppScope.launch {
                delay(VERSION_CHECK_SNOOZE_MS + 1_000L)
                render()
            }
    }

    private const val LIFTED_CLASS = "lapis-update-pill-lifted"

    private fun buildMarkup(container: SimplePanel) {
        container.div(className = "lapis-update-live") {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            val newPill =
                div(className = "lapis-update-pill") {
                    span(className = "lapis-update-dot").setAttribute("aria-hidden", "true")
                    span(
                        content = tr("Neue Version verfügbar – dieser Tab läuft auf einem älteren Stand."),
                        className = "lapis-update-text",
                    )
                    reloadButton =
                        button(text = tr("Neu laden"), style = ButtonStyle.PRIMARY, className = "lapis-update-reload") {
                            onClick { onReloadClicked() }
                        }
                    button(text = "", icon = "fas fa-xmark", style = ButtonStyle.LINK, className = "lapis-update-pill-close") {
                        setAttribute("title", gettext("Ausblenden"))
                        setAttribute("aria-label", gettext("Ausblenden"))
                        onClick { onDismissClicked() }
                    }
                }
            newPill.visible = false
            pill = newPill
        }
    }
}
