package network.lapis.cloud.server.theme

import kotlinx.html.HEAD
import kotlinx.html.link

/**
 * V1.9.216 -- the one place that links the stylesheets of every server-rendered public page: the shared page stylesheet first,
 * then the generated `/theme.css` (so a theme's custom properties win over the page stylesheet's use of them). Every public page
 * builder calls this instead of a raw `link(...)`; `PublicThemeTripwireTest` fails on a stray raw link.
 */
internal fun HEAD.publicStylesheetLinks() {
    link(rel = "stylesheet", href = "/s/assets/style.css")
    link(rel = "stylesheet", href = THEME_CSS_PATH)
}
