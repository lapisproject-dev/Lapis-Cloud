package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.i18n.gettext

/*
 * V1.9.184 -- the ONE bar of a room (conference and encounter room): three zones in a grid, `auto | 1fr | auto`.
 *
 * - [RoomBarZone.START]: the settings gear, alone, at the left edge.
 * - [RoomBarZone.CENTRE]: everything you do in the room (microphone, camera, hand, panels, view, recording, "Mehr"), centred.
 * - [RoomBarZone.END]: the way out ("Für alle beenden"/"Türen schließen", "Verlassen"), at the right edge.
 *
 * This class owns the structure only (like `EncounterControlBar` did before): the owners add their buttons to a zone through the icon-only
 * factories (R58 named exceptions b/c), so the guideline rules keep applying to each button. It contains NO RPC call and NO late hook.
 *
 * The separators between the zones are drawn by CSS (pseudo-elements of `__start` and `__end`), they are no elements of the DOM and so no
 * stop of the focus order. The bar is NOT given `role="toolbar"`: a toolbar promises arrow-key navigation, which the bars never had (every
 * control is an ordinary tab stop); the three zones are labelled groups instead.
 *
 * The bar itself is `position: static` -- the sticky positioning, the skin (border, shadow, surface) and the anchoring of the sheets belong
 * to the DOCK around it (`.lapis-room-bar-dock`, theme.css), which the owner builds, because the "Mehr" sheet and the settings panel are
 * siblings of the bar inside that dock (a sheet inside the bar would inherit the bar's `.btn` rules).
 */

/** The three zones of [RoomControlBar], in DOM order. */
internal enum class RoomBarZone { START, CENTRE, END }

internal class RoomControlBar(
    parent: Container,
    /** The variant class of the bar (`lapis-room-bar--conference` / `lapis-room-bar--encounter`), plus any compatibility class of the owner. */
    variantClass: String,
) {
    val root: Div = parent.div(className = "lapis-room-bar $variantClass")

    /** `.lapis-room-bar__start`: the settings gear. */
    val start: Div = zoneDiv("lapis-room-bar__start", gettext("Einstellungen"))

    /** `.lapis-room-bar__centre`: the controls of the room. */
    val centre: Div = zoneDiv("lapis-room-bar__centre", gettext("Im Raum"))

    /** `.lapis-room-bar__end`: the way out. */
    val end: Div = zoneDiv("lapis-room-bar__end", gettext("Raum verlassen"))

    private fun zoneDiv(
        className: String,
        label: String,
    ): Div =
        root.div(className = className).also {
            it.setAttribute("role", "group")
            it.setAttribute("aria-label", label)
        }

    fun zone(zone: RoomBarZone): Div =
        when (zone) {
            RoomBarZone.START -> start
            RoomBarZone.CENTRE -> centre
            RoomBarZone.END -> end
        }

    /** A thin vertical line between two groups of the centre zone; decorative, `aria-hidden`, no tab stop. */
    fun divider(parent: Container): Span = parent.conferenceControlsDivider()

    companion object {
        /**
         * What the two zone separators take in the row beyond what [controlBarOverflow] already counts for two group boundaries (a divider
         * item: 6 gap + 9 + 6 gap = 21 px each). Measured against theme.css: start zone = gear, 6 flex gap, 8 margin, 1 line, 16 margin,
         * then 6 column gap = 37 px (16 more than 21); end zone = 6 column gap, 16 margin, 1 line, 8 margin, 12 zone gap = 43 px (22 more).
         * A separator is drawn only while its zone shows a control, so the figure is a safe over-estimate when the gear sits in the sheet.
         */
        const val ZONE_SEPARATORS_RESERVED_PX = 38.0
    }
}
