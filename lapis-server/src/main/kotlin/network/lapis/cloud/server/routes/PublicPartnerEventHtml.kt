package network.lapis.cloud.server.routes

import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.unsafe
import network.lapis.cloud.server.partner.ForeignText
import network.lapis.cloud.server.partner.PartnerEventView

/**
 * V1.9.245 "Partnerserver P2" -- the ONE place that renders an event of a FOREIGN server on a public page, and therefore the ONE place with an
 * external link (pinned by `PartnerServerTripwireTest`). Everything from the foreign feed is text: it goes through the escaping `kotlinx.html`
 * API (`+text`, attribute setters) and never through `unsafe`; the only `unsafe` is the fixed arrow constant. The link target was validated at
 * parse time AND is validated again by `PartnerEventDisplay.revalidatedUrl` before a view exists.
 *
 * The link opens in the same tab (no `target`), carries `rel="noopener noreferrer external"` and `referrerpolicy="no-referrer"`, the arrow is
 * `aria-hidden` and the words "(externe Seite)" are visually hidden, so no icon is ever the only carrier of meaning (R58).
 */
private const val NAME_MAX_CHARS = 60
private const val EXTERNAL_ARROW_SVG = "&#8599;"

/** "Partnerserver: Name" (direct partner) or "Partnerserver: Name (host) · über Partner" (reached through one). Names are cut to 60 characters. */
internal fun partnerMarkLine(view: PartnerEventView): String {
    val root = ForeignText.ellipsize(text = view.rootName, maxChars = NAME_MAX_CHARS)
    if (view.hops <= 1) return "Partnerserver: $root"
    val origin = ForeignText.ellipsize(text = view.originName ?: view.originHost, maxChars = NAME_MAX_CHARS)
    val withHost = if (view.originName != null) "$origin (${view.originHost})" else origin
    return "Partnerserver: $withHost · über $root"
}

internal fun UL.partnerEventItem(view: PartnerEventView) {
    li(classes = "public-event public-event--partner") {
        attributes["lang"] = "de"
        a(href = view.url) {
            attributes["rel"] = "noopener noreferrer external"
            attributes["referrerpolicy"] = "no-referrer"
            attributes["hreflang"] = "de"
            +view.title
        }
        span {
            attributes["aria-hidden"] = "true"
            unsafe { +EXTERNAL_ARROW_SVG }
        }
        span(classes = "visually-hidden") { +"(externe Seite)" }
        span(classes = "section-note") {
            +" · ${germanDate(view.startsAtWall)}, ${germanTime(view.startsAtWall)} Uhr"
        }
        val recurrence = view.recurrenceSentence
        if (recurrence != null) {
            repeatMark(tip = recurrence.replaceFirstChar { it.uppercase() })
            span(classes = "section-note series-note") { +"Reihe: $recurrence" }
        }
        p(classes = "public-event-partner") { +partnerMarkLine(view) }
    }
}
