package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.span
import kotlinx.html.unsafe

/**
 * V1.9.243 -- the small "repeat" mark of a series entry on `/veranstaltungen`. Lives in its own file because it is the one place that
 * renders markup through `unsafe`: the SVG is the fixed constant below, never user data, and the tooltip [tip] goes through the
 * escaping attribute API. The mark is `aria-hidden` (the same sentence is printed in the row under it) and never carries `title=`.
 */
internal const val REPEAT_ICON_SVG =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" focusable=\"false\">" +
        "<path d=\"M17 2l4 4-4 4\"/><path d=\"M3 11V9a3 3 0 0 1 3-3h15\"/>" +
        "<path d=\"M7 22l-4-4 4-4\"/><path d=\"M21 13v2a3 3 0 0 1-3 3H3\"/></svg>"

internal fun FlowContent.repeatMark(tip: String) {
    span(classes = "series-mark") {
        attributes["aria-hidden"] = "true"
        attributes["data-tip"] = tip
        unsafe { +REPEAT_ICON_SVG }
    }
}
