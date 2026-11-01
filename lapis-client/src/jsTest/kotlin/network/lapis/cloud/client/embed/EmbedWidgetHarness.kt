package network.lapis.cloud.client.embed

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.client.awaitUntil
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/**
 * V1.9.248 -- runs the REAL `lapis-widgets.js` of the server in headless Chrome.
 *
 * The file is generated into this source set (`GenerateEmbedWidgetsFixture`, one source of truth), turned into a Blob URL and loaded through a
 * `<script src=blob:...>`: that makes `document.currentScript.src` a real URL, so `LAPIS_ORIGIN` is this page's origin and the production code
 * needs NO test hook. `attachShadow` is wrapped for the duration of a test because the widget's shadow roots are CLOSED and could not be
 * read otherwise. `window.fetch` is replaced by a stub that records every call.
 */
internal class FetchCall(
    val url: String,
    val init: dynamic,
) {
    val credentials: String? get() = init?.credentials as? String
    val signal: dynamic get() = init?.signal
}

internal class EmbedHarness(
    val calls: MutableList<FetchCall>,
    private val roots: dynamic,
) {
    val origin: String get() = window.location.origin

    /** The (closed) shadow root of [host], or `null` while the widget has not mounted. */
    fun rootOf(host: HTMLElement): dynamic = roots.get(host)

    fun hasRoot(host: HTMLElement): Boolean = rootOf(host) != null

    suspend fun awaitRoot(host: HTMLElement): dynamic {
        awaitUntil("the events widget mounted") { hasRoot(host) }
        return rootOf(host)
    }

    /** Waits long enough for an in-flight (stubbed, immediate) request to have been handled, then asserts that nothing mounted. */
    suspend fun settle() = delay(SETTLE_MS)

    companion object {
        const val SETTLE_MS = 400L
    }
}

private fun newResponse(
    status: Int,
    body: String,
): dynamic {
    val factory: dynamic = js("(function (b, s) { return new Response(b, {status: s, headers: {'Content-Type': 'application/json'}}); })")
    return factory(body, status)
}

internal fun jsonResponse(
    status: Int,
    body: String,
): Promise<dynamic> = Promise.resolve(newResponse(status = status, body = body))

internal fun jsonResponse(body: dynamic): Promise<dynamic> = jsonResponse(status = 200, body = JSON.stringify(body))

internal fun rejectingResponse(): Promise<dynamic> = Promise.reject(RuntimeException("Failed to fetch"))

/** A request that never answers and rejects with an `AbortError` as soon as the widget aborts it. */
internal fun hangingResponse(init: dynamic): Promise<dynamic> =
    Promise { _, reject ->
        val signal = init?.signal
        if (signal != null) signal.addEventListener("abort", { reject(RuntimeException("aborted")) })
    }

/** A request that ignores the abort and answers late with [body]. */
internal fun lateResponse(
    delayMs: Int,
    body: dynamic,
): Promise<dynamic> =
    Promise { resolve, _ ->
        window.setTimeout({ resolve(newResponse(status = 200, body = JSON.stringify(body))) }, delayMs)
    }

internal fun ownEvent(
    slug: String,
    title: String,
    startsAt: String,
    location: String? = null,
    online: Boolean = false,
): dynamic {
    val e = js("({})")
    e.slug = slug
    e.title = title
    e.startsAt = startsAt
    e.endsAt = startsAt
    e.locationText = location
    e.registrationUrl = window.location.origin + "/veranstaltung/" + slug
    e.full = false
    e.feeAmount = "0.00"
    e.feeCurrency = "EUR"
    e.online = online
    return e
}

internal fun partnerEvent(
    title: String,
    startsAt: String,
    originHost: String = "partner.example",
    url: dynamic = "https://partner.example/veranstaltung/x",
    timezone: String = "Europe/Berlin",
    location: String? = null,
    series: String? = null,
    originName: String = "Partner e.V.",
): dynamic {
    val e = js("({})")
    e.title = title
    e.startsAt = startsAt
    e.endsAt = startsAt
    e.timezone = timezone
    e.locationText = location
    e.online = false
    e.seriesText = series
    e.url = url
    e.originName = originName
    e.originHost = originHost
    e.via = js("[]")
    return e
}

internal fun feedOf(
    events: Array<dynamic>,
    partnerEvents: Array<dynamic>? = null,
): dynamic {
    val d = js("({})")
    d.events = events
    if (partnerEvents != null) d.partnerEvents = partnerEvents
    return d
}

/**
 * Mounts `data-lapis-widget` hosts from [hostsHtml] (they must exist BEFORE the script runs: it scans once at load), loads the widget script
 * and runs [block]. Everything is undone in `finally`: stub, `attachShadow`, hosts, globals, Blob URL.
 */
internal fun embedTest(
    hostsHtml: String,
    handler: (url: String, init: dynamic) -> Promise<dynamic>,
    block: suspend (EmbedHarness, List<HTMLElement>) -> Unit,
): Promise<Unit> =
    CoroutineScope(SupervisorJob()).promise {
        val w: dynamic = window
        val originalFetch = w.fetch
        val originalAttach: dynamic = js("Element.prototype.attachShadow")
        val roots: dynamic = js("new Map()")
        val calls = mutableListOf<FetchCall>()
        val container = document.createElement("div") as HTMLElement
        var blobUrl: String? = null
        try {
            w.__lapisEmbedAllowedOriginsV1 = arrayOf(window.location.origin)
            js("delete window.__lapisEmbedV1")
            val installer: dynamic =
                js("(function (orig, map) { return function (init) { var r = orig.call(this, init); map.set(this, r); return r; }; })")
            val proto: dynamic = js("Element.prototype")
            proto.attachShadow = installer(originalAttach, roots)
            w.fetch = { url: dynamic, init: dynamic ->
                val u = if (jsTypeOf(url) == "string") url as String else url.url as String
                calls += FetchCall(u, init)
                handler(u, init)
            }
            container.innerHTML = hostsHtml
            document.body!!.appendChild(container)
            val hosts =
                container.querySelectorAll("[data-lapis-widget]").let { list ->
                    (0 until list.length).map { list.item(it) as HTMLElement }
                }
            val toBlobUrl: dynamic =
                js(
                    "(function (b64) { var bytes = Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); }); " +
                        "return URL.createObjectURL(new Blob([bytes], {type: 'text/javascript'})); })",
                )
            blobUrl = toBlobUrl(EMBED_WIDGETS_JS_BASE64) as String
            val loaded =
                Promise<Unit> { resolve, reject ->
                    val script = document.createElement("script")
                    script.asDynamic().onload = { resolve(Unit) }
                    script.asDynamic().onerror = { reject(IllegalStateException("lapis-widgets.js did not load")) }
                    script.setAttribute("src", blobUrl)
                    document.head!!.appendChild(script)
                }
            loaded.await()
            withTimeout(EMBED_TEST_DEADLINE_MS) { block(EmbedHarness(calls, roots), hosts) }
        } finally {
            w.fetch = originalFetch
            val restoreProto: dynamic = js("Element.prototype")
            restoreProto.attachShadow = originalAttach
            js("delete window.__lapisEmbedV1")
            js("delete window.__lapisEmbedAllowedOriginsV1")
            container.remove()
            blobUrl?.let { revoke(it) }
        }
    }

private const val EMBED_TEST_DEADLINE_MS = 60_000L

private fun revoke(url: String) {
    val f: dynamic = js("(function (u) { URL.revokeObjectURL(u); })")
    f(url)
}
