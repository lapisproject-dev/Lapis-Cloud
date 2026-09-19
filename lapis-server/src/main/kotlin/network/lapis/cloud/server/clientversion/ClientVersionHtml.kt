package network.lapis.cloud.server.clientversion

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.20 -- pure, I/O-free HTML transformer that stamps the client build id (see
 * [ClientBuildId]) into the KVision client's `index.html` shell, in two places:
 *
 * 1. the `content` of `<meta name="lapis-client-build" ...>` -- the running tab reads its OWN id
 *    from there (see the client's `ClientVersionWatcher`);
 * 2. a `?v=<id>` cache buster on the `main.bundle.js` script `src`.
 *
 * **The `?v=` buster is load-bearing, not cosmetic.** Without it a browser may combine a NEW
 * `index.html` with an OLD cached `main.bundle.js`; that old bundle would then read the NEW id as
 * its own and never notice it is stale -- the exact failure this wave fixes, in disguise. With
 * `?v=` every id change is a new bundle URL.
 *
 * **Fail closed instead of escaping.** The id is guarded by [ClientBuildId.isWellFormed] (pure
 * `[0-9a-f]`) before anything is written, so no HTML escaping is needed; a null or malformed id
 * leaves the input byte-identical. Same discipline as `BrandingHtml`: marker based, never throws,
 * a missing marker is logged at most once per process (not per request).
 */
object ClientVersionHtml {
    /** Name of the `<meta>` element the client reads its own build id from. */
    const val META_NAME = "lapis-client-build"

    private const val META_MARKER = "name=\"$META_NAME\""
    private const val CONTENT_ATTR = "content=\""
    private const val BUNDLE_SRC_MARKER = "src=\"main.bundle.js\""

    @Volatile
    private var metaMarkerMissingLogged = false

    @Volatile
    private var bundleMarkerMissingLogged = false

    /** Returns [html] with [buildId] injected -- see class KDoc. Unchanged for a null / malformed id. Never throws. */
    fun inject(
        html: String,
        buildId: String?,
    ): String {
        if (buildId == null || !ClientBuildId.isWellFormed(buildId)) return html
        return injectBundleVersion(html = injectMeta(html = html, buildId = buildId), buildId = buildId)
    }

    private fun injectMeta(
        html: String,
        buildId: String,
    ): String {
        val markerIndex = html.indexOf(META_MARKER)
        val tagEnd = if (markerIndex >= 0) html.indexOf('>', startIndex = markerIndex) else -1
        val attrIndex = if (tagEnd >= 0) html.indexOf(CONTENT_ATTR, startIndex = markerIndex) else -1
        val valueStart = if (attrIndex in 0 until tagEnd) attrIndex + CONTENT_ATTR.length else -1
        val valueEnd = if (valueStart >= 0) html.indexOf('"', startIndex = valueStart) else -1
        if (valueStart < 0 || valueEnd < 0 || valueEnd > tagEnd) {
            if (!metaMarkerMissingLogged) {
                metaMarkerMissingLogged = true
                logger.warn { "index.html enthält kein <meta $META_MARKER content=\"...\"> -- Client-Build-Kennung wird nicht injiziert." }
            }
            return html
        }
        return html.substring(0, valueStart) + buildId + html.substring(valueEnd)
    }

    private fun injectBundleVersion(
        html: String,
        buildId: String,
    ): String {
        val index = html.indexOf(BUNDLE_SRC_MARKER)
        if (index < 0) {
            if (!bundleMarkerMissingLogged) {
                bundleMarkerMissingLogged = true
                logger.warn { "index.html enthält kein <script $BUNDLE_SRC_MARKER> (bzw. schon versioniert) -- kein ?v=-Cache-Buster." }
            }
            return html
        }
        val replacement = "src=\"main.bundle.js?v=$buildId\""
        return html.substring(0, index) + replacement + html.substring(index + BUNDLE_SRC_MARKER.length)
    }
}
