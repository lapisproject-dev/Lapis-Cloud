package network.lapis.cloud.server.branding

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * V1.2.5 White-Label-Branding -- pure, I/O-free HTML transformer that injects [ResolvedBranding]
 * into the KVision client's `index.html` shell: the `<title>` element's text, and a
 * `<script type="application/json" id="lapis-brand">` payload the client reads at startup (see
 * `network.lapis.cloud.client.Branding` KDoc). Never throws, never does I/O -- reading/writing the
 * actual file happens once, in `Application.kt`'s `cachedIndexHtml`.
 *
 * **Two separate escaping contexts, two separate escape functions.** [title] ends up BOTH inside
 * an HTML text node (`<title>...</title>`) and inside a JSON string literal embedded in a
 * `<script>` element -- each needs its own escaping discipline; conflating them (e.g. only
 * HTML-escaping, then trusting the result inside the `<script>` body too) would leave one of the
 * two contexts exploitable. See [BrandingHtmlTest] for dedicated coverage of both.
 */
object BrandingHtml {
    /** The route `Application.kt` registers to stream the logo file -- single source of truth for both sides of that route. */
    const val LOGO_ROUTE_PATH = "/api/branding/logo"

    private const val TITLE_OPEN = "<title>"
    private const val TITLE_CLOSE = "</title>"
    private const val PAYLOAD_ID_MARKER = "id=\"lapis-brand\""
    private const val SCRIPT_CLOSE = "</script>"

    // Log each missing marker at most ONCE per process, not once per request -- a deployment
    // running a stale/handwritten index.html without these markers would otherwise flood the log
    // on every single page view. @Volatile: best-effort dedup is enough here (a rare double-log
    // under a startup race is harmless), but a plain var could otherwise never become visible
    // across threads at all under the JMM.
    @Volatile
    private var titleMarkerMissingLogged = false

    @Volatile
    private var payloadMarkerMissingLogged = false

    /** Returns [html] with [ResolvedBranding.title]/[ResolvedBranding.logoAvailable] injected -- see class KDoc. Never throws. */
    fun inject(
        html: String,
        brand: ResolvedBranding,
    ): String =
        injectPayload(
            html = injectTitle(html = html, title = brand.title),
            brand = brand,
        )

    private fun injectTitle(
        html: String,
        title: String,
    ): String {
        val start = html.indexOf(TITLE_OPEN)
        val end = if (start >= 0) html.indexOf(TITLE_CLOSE, startIndex = start + TITLE_OPEN.length) else -1
        if (start < 0 || end < 0) {
            if (!titleMarkerMissingLogged) {
                titleMarkerMissingLogged = true
                logger.warn { "index.html enthält kein <title>...</title> -- Branding-Titel wird nicht injiziert." }
            }
            return html
        }
        val before = html.substring(0, start + TITLE_OPEN.length)
        val after = html.substring(end)
        return before + escapeHtmlText(title) + after
    }

    private fun injectPayload(
        html: String,
        brand: ResolvedBranding,
    ): String {
        val markerIndex = html.indexOf(PAYLOAD_ID_MARKER)
        val tagOpenEnd = if (markerIndex >= 0) html.indexOf('>', markerIndex).let { if (it < 0) -1 else it + 1 } else -1
        val tagClose = if (tagOpenEnd >= 0) html.indexOf(SCRIPT_CLOSE, startIndex = tagOpenEnd) else -1
        if (markerIndex < 0 || tagOpenEnd < 0 || tagClose < 0) {
            if (!payloadMarkerMissingLogged) {
                payloadMarkerMissingLogged = true
                logger.warn {
                    "index.html enthält kein <script type=\"application/json\" $PAYLOAD_ID_MARKER>...</script> " +
                        "-- Branding-Payload wird nicht injiziert."
                }
            }
            return html
        }
        val before = html.substring(0, tagOpenEnd)
        val after = html.substring(tagClose)
        return before + renderPayloadJson(brand) + after
    }

    private fun renderPayloadJson(brand: ResolvedBranding): String {
        val logoUrlLiteral = if (brand.logoAvailable) "\"${escapeJsonString(LOGO_ROUTE_PATH)}\"" else "null"
        return "{\"title\":\"${escapeJsonString(brand.title)}\",\"logoUrl\":$logoUrlLiteral}"
    }

    /** Standard HTML text-node escaping -- see class KDoc "Two separate escaping contexts". */
    private fun escapeHtmlText(value: String): String =
        buildString {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }

    /**
     * JSON string-literal escaping, embedded inside an HTML `<script>` element -- additionally
     * escapes `<` as the JSON unicode escape (which any conforming JSON parser decodes back to a
     * literal `<`) so a title containing the substring `</script>` can never prematurely close the
     * surrounding tag and break out into raw, unescaped HTML/script context. See
     * [BrandingHtmlTest] "script injection via title" for the concrete regression case.
     */
    private fun escapeJsonString(value: String): String =
        buildString {
            for (ch in value) {
                when {
                    ch == '\\' -> append("\\\\")
                    ch == '"' -> append("\\\"")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch == '<' -> append("\\u003C")
                    ch.code < 0x20 -> append("\\u" + ch.code.toString(radix = 16).padStart(length = 4, padChar = '0'))
                    else -> append(ch)
                }
            }
        }
}
