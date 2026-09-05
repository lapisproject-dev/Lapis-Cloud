package network.lapis.cloud.server.routes

import network.lapis.cloud.server.events.QrCodeMatrix

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- renders a [QrCodeMatrix] to a plain-string
 * SVG document. Deliberately a hand-built string, not `kotlinx.html` (that library targets HTML
 * elements, not SVG) -- but the content is pure geometry derived from [QrCodeMatrix.horizontalRuns]
 * (integers only), never any user-supplied text, so there is no injection/escaping question here the
 * way there would be for `EventPublicHtml`'s own user-facing markup.
 *
 * Fill color is an SVG **attribute** (`fill="#000000"`), never an inline `style="..."` -- so no
 * `style-src 'unsafe-inline'` CSP relaxation is ever needed for this response (see
 * `applyEventTicketPageHeaders` KDoc). No `<script>`, no external `xlink:href`/`<image>` reference --
 * `default-src 'none'` on the SVG response itself (`EventPublicRoutes` ticket.svg route) is
 * satisfiable exactly because this file never emits either.
 */
internal object EventTicketSvg {
    private const val QUIET_ZONE_MODULES = 2

    /** Renders [matrix] as a `[sizePx] x [sizePx]` SVG, with a [QUIET_ZONE_MODULES]-module white quiet zone -- required by the QR spec for reliable scanning, easy to forget when hand-rolling a renderer. */
    fun render(
        matrix: QrCodeMatrix,
        sizePx: Int,
    ): String {
        val totalModules = matrix.size + QUIET_ZONE_MODULES * 2
        val moduleSize = sizePx.toDouble() / totalModules
        val builder = StringBuilder()
        builder.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $sizePx $sizePx\" width=\"$sizePx\" height=\"$sizePx\">")
        builder.append("<rect x=\"0\" y=\"0\" width=\"$sizePx\" height=\"$sizePx\" fill=\"#ffffff\"/>")
        for (run in matrix.horizontalRuns()) {
            val x = (run.x + QUIET_ZONE_MODULES) * moduleSize
            val y = (run.y + QUIET_ZONE_MODULES) * moduleSize
            val width = run.length * moduleSize
            builder.append("<rect x=\"${fmt(x)}\" y=\"${fmt(y)}\" width=\"${fmt(width)}\" height=\"${fmt(moduleSize)}\" fill=\"#000000\"/>")
        }
        builder.append("</svg>")
        return builder.toString()
    }

    /** Trims a trailing ".0" off an integral double -- shorter, still valid SVG numeric syntax either way. */
    private fun fmt(value: Double): String {
        val rounded = (value * 1000).toLong() / 1000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
