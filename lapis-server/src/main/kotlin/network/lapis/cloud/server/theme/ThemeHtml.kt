package network.lapis.cloud.server.theme

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Stamps the theme's content hash into the SPA shell: the `href` of the element carrying the marker id becomes
 * `/theme.css?v=<hash12>` (hex only, so no escaping is needed). Marker based, never throws, a missing marker is logged once per
 * process. Same discipline as `ClientVersionHtml`: do not repeat the marker literal inside HTML comments of `index.html`
 * (the first occurrence wins).
 */
object ThemeHtml {
    const val MARKER_ID = "lapis-theme-css"
    private const val ID_ATTR = "id=\"$MARKER_ID\""
    private const val HREF_ATTR = "href=\""

    @Volatile
    private var markerMissingLogged = false

    fun inject(
        html: String,
        theme: ResolvedTheme,
    ): String {
        val idIndex = html.indexOf(ID_ATTR)
        val tagStart = if (idIndex >= 0) html.lastIndexOf('<', idIndex) else -1
        val tagEnd = if (idIndex >= 0) html.indexOf('>', idIndex) else -1
        val hrefIndex = if (tagStart >= 0 && tagEnd >= 0) html.indexOf(HREF_ATTR, tagStart) else -1
        val valueStart = if (hrefIndex in tagStart until tagEnd) hrefIndex + HREF_ATTR.length else -1
        val valueEnd = if (valueStart >= 0) html.indexOf('"', valueStart) else -1
        if (valueStart < 0 || valueEnd < 0 || valueEnd > tagEnd) {
            if (!markerMissingLogged) {
                markerMissingLogged = true
                logger.warn { "index.html enthält kein <link ... $ID_ATTR href=\"...\"> -- Theme-Stylesheet wird nicht versioniert." }
            }
            return html
        }
        return html.substring(0, valueStart) + "$THEME_CSS_PATH?v=${theme.hash12}" + html.substring(valueEnd)
    }
}
