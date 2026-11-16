package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.document
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollViewerLinkDto
import org.w3c.dom.Element

private const val SVG_NS = "http://www.w3.org/2000/svg"

/** Version 40 of the QR standard has 177 modules a side; anything larger is not a QR code of this server. */
internal const val ROOM_POLL_QR_MAX_SIZE = 177
internal const val ROOM_POLL_QR_MAX_RUNS = 20_000

/**
 * The QR code of the viewer link as an `<svg>` element built ONLY from DOM calls (`createElementNS` + numeric attributes): no `innerHTML`, no
 * string that came from the server is ever parsed as markup. Fixed black on white (the classes read the two `--lapis-qr-*` tokens, the same in every theme) with a quiet zone of [RoomPollRules.QR_QUIET_ZONE_MODULES]
 * modules, whatever the theme (many camera apps fail on inverted codes). The numbers are clamped; a size or run count outside the limits draws
 * NOTHING (an empty element) instead of something unbounded.
 */
internal fun roomPollQrSvg(
    link: RoomPollViewerLinkDto,
    pixelSize: String,
): Element {
    val svg = document.createElementNS(SVG_NS, "svg")
    val size = link.qrSize
    val valid = size in 1..ROOM_POLL_QR_MAX_SIZE && link.qrRuns.size <= ROOM_POLL_QR_MAX_RUNS
    val quiet = RoomPollRules.QR_QUIET_ZONE_MODULES
    val total = if (valid) size + 2 * quiet else 1
    svg.setAttribute("xmlns", SVG_NS)
    svg.setAttribute("viewBox", "0 0 $total $total")
    svg.setAttribute("width", pixelSize)
    svg.setAttribute("height", pixelSize)
    svg.setAttribute("shape-rendering", "crispEdges")
    svg.setAttribute("role", "img")
    svg.setAttribute("aria-label", gettext("QR-Code zur Umfrage-Seite"))
    svg.setAttribute("class", "lapis-room-poll-qr")
    if (!valid) return svg
    val background = document.createElementNS(SVG_NS, "rect")
    background.setAttribute("x", "0")
    background.setAttribute("y", "0")
    background.setAttribute("width", total.toString())
    background.setAttribute("height", total.toString())
    background.setAttribute("class", "lapis-room-poll-qr-bg")
    svg.appendChild(background)
    link.qrRuns.forEach { run ->
        val x = run.x.coerceIn(0, size)
        val y = run.y.coerceIn(0, size - 1)
        val length = run.length.coerceIn(0, size - x)
        if (length > 0) {
            val rect = document.createElementNS(SVG_NS, "rect")
            rect.setAttribute("x", (x + quiet).toString())
            rect.setAttribute("y", (y + quiet).toString())
            rect.setAttribute("width", length.toString())
            rect.setAttribute("height", "1")
            rect.setAttribute("class", "lapis-room-poll-qr-dark")
            svg.appendChild(rect)
        }
    }
    return svg
}
