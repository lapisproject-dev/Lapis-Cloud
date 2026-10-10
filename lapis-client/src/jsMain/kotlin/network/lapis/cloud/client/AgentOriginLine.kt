package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.utils.px

/**
 * The muted "Entworfen von Agent „Name“" origin line shared by every screen that shows content an MCP agent drafted
 * (`AiDraftsScreen`, `ArticlesScreen`, `ArticleEditor`).
 *
 * Kares Regel: the agent origin is never a heading, never bold, never a `title` attribute, never a badge -- a self-chosen,
 * unverified connection name must never look like a trustworthy label. It only ever reaches the screen through
 * [untrustedSpan] (marker-stripping) and is truncated, never rendered as HTML.
 */
internal fun Container.agentOriginLine(label: String) {
    span(tr("Entworfen von Agent")) { addCssClasses("text-muted small") }
    untrustedSpan("„$label“", className = "text-muted small text-truncate") { maxWidth = 240.px }
}
