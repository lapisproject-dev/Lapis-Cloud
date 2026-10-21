package network.lapis.cloud.client.encounter

import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_COLUMNS
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_ROWS
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapCell
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapTemplate
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** What the view asks of the room. The room (and its controller) decides; the view never talks to the server. */
internal interface EncounterMapHandler {
    fun step(dir: EncounterMapFacing)

    fun walkTo(index: Int)

    fun teleport(anchor: EncounterMapAnchor)

    fun enterMap()

    fun leaveMap()

    /** The "L" key: speak the own place and the names nearby. */
    fun describeAloud()
}

/** One figure on the board: the element lives as long as the person stays on the map; only its custom properties and classes change. */
private class Figure(
    val root: Div,
    val initials: Span,
) {
    var facing: EncounterMapFacing? = null
    var shownInitials: String = ""
    var own: Boolean = false
}

/**
 * V1.9.166 (encounter stage 3, wave 1) -- the grid map as DOM. Built ONCE from the room template (96 tiles, `aria-hidden`); a render only
 * fills it: a figure per person on the map, moved by the CSS custom properties `--x`/`--y` of the figure ITSELF (never a `transform` on an
 * ancestor: the sticky control bar of the room must not get a new containing block).
 *
 * V1.9.167: the view lives in the map overlay ([EncounterMapOverlay], which owns the title, the privacy line and the notice); it builds no heading of its own.
 *
 * - **One tab stop**: the board is a `role="group"` named "Karte" with `tabindex=0`, described by the status line. Arrow keys and W/A/S/D
 *   step (a key with Ctrl, Alt or Meta is left to the browser), "L" speaks the own place and the names nearby, Escape moves the focus to
 *   "Karte verlassen" while the viewer is on the map (a second Escape, or Escape off the map, is left to the overlay around the view: it closes). A click or tap on a tile walks there.
 * - **No name on the board**: a figure shows initials (`aria-hidden`); no `title`, no `aria-label`, no `data-*` carries a name. The names
 *   are only in the list "In Ihrer Nähe", which belongs to the viewer.
 * - **Touch**: below 600 px a small direction pad appears (CSS), buttons of at least 44 px.
 * - Nothing here is stored in the browser.
 */
internal class EncounterMapView(
    host: Div,
    private val terms: EncounterTerms,
    private val template: EncounterMapTemplate,
    private val handler: EncounterMapHandler,
    private val canWalk: Boolean,
) {
    private val section: Div = host.div(className = "lapis-encounter-map")
    private val status: Div = section.div(className = "lapis-encounter-map__status")
    private val board: Div = section.div(className = "lapis-encounter-map__board")
    private val grid: Div = board.div(className = "lapis-encounter-map__grid")
    private val figuresLayer: Div = board.div(className = "lapis-encounter-map__figures")
    private val figures = LinkedHashMap<String, Figure>()

    /** Whether the viewer stands on the map right now (set by [render]; only a walking viewer ever is). */
    private var selfOnMap = false
    private val actions: Div = section.div(className = "lapis-encounter-map__actions")
    private val enterButton: Button
    private val leaveButton: Button
    private val anchorButtons: List<Tag>
    private val nearHeading: Div = section.div(className = "small fw-semibold lapis-encounter-map__near-heading")
    private val nearList: Tag = section.tag(TAG.UL, className = "small lapis-encounter-map__near")
    private val pad: Div = section.div(className = "lapis-encounter-map__pad")

    init {
        status.setAttribute("id", STATUS_ID)
        grid.setAttribute("role", "group")
        grid.setAttribute("aria-label", gettext("Karte"))
        grid.setAttribute("aria-describedby", STATUS_ID)
        grid.setAttribute("tabindex", "0")
        buildTiles()
        grid.onEvent { keydown = { event -> onKey(event) } }
        board.onEvent { click = { event -> onBoardClick(event.target) } }
        enterButton = actions.actionButton(ActionIcon.MAP_ENTER, tr("Auf die Karte"), ButtonStyle.OUTLINEPRIMARY, small = true)
        enterButton.onClick { handler.enterMap() }
        leaveButton = actions.actionButton(ActionIcon.MAP_LEAVE, tr("Karte verlassen"), ButtonStyle.OUTLINESECONDARY, small = true)
        leaveButton.onClick { handler.leaveMap() }
        anchorButtons =
            EncounterMapAnchor.entries.map { anchor ->
                val button = actions.tag(TAG.BUTTON, content = terms.mapAnchorLabel(anchor), className = "btn btn-outline-secondary btn-sm")
                button.setAttribute("type", "button")
                button.onClick { handler.teleport(anchor) }
                button
            }
        buildPad()
        if (!canWalk) {
            // An office holder looks only: no entering, no walking, no pad.
            actions.hide()
            pad.hide()
        }
        nearHeading.hide()
        nearList.hide()
        leaveButton.hide()
        anchorButtons.forEach { it.hide() }
    }

    /** Fills the board with [model]. Idempotent; builds nothing but figures. */
    fun render(model: EncounterMapModel) {
        untrustedContent(status, model.status)
        val seen = HashSet<String>()
        model.people.forEach { person ->
            seen += person.memberId
            val figure = figures.getOrPut(person.memberId) { buildFigure() }
            figure.root.setStyle("--x", person.x.toString())
            figure.root.setStyle("--y", person.y.toString())
            if (figure.facing != person.facing) {
                figure.facing?.let { figure.root.removeCssClass(facingClass(it)) }
                figure.root.addCssClass(facingClass(person.facing))
                figure.facing = person.facing
            }
            val own = person.memberId == model.selfId
            if (figure.own != own) {
                if (own) figure.root.addCssClass("is-self") else figure.root.removeCssClass("is-self")
                figure.own = own
            }
            if (figure.shownInitials != person.initials) {
                untrustedContent(figure.initials, person.initials)
                figure.shownInitials = person.initials
            }
        }
        figures.keys.filter { it !in seen }.forEach { id -> figures.remove(id)?.let { figuresLayer.remove(it.root) } }
        val walking = model.canWalk
        selfOnMap = walking && model.selfOnMap
        // The button that has the focus is about to leave the picture ("Karte verlassen" is hidden once the person is off the map): hand the focus to the board.
        val focusWasInActions = actions.getElement()?.contains(document.activeElement) == true
        if (walking) {
            applyAnchorState(model.anchorsBusy)
            if (model.selfOnMap) {
                enterButton.hide()
                leaveButton.show()
                anchorButtons.forEach { it.show() }
            } else {
                enterButton.show()
                leaveButton.hide()
                anchorButtons.forEach { it.hide() }
            }
        }
        renderNear(model)
        if (focusWasInActions && !selfOnMap) focusBoard()
    }

    /**
     * The anchor buttons during a jump or its cooldown: `aria-disabled` + a dimmed look, NEVER the native `disabled` (it would drop the
     * keyboard focus). The click still reaches the controller, which answers it with a spoken notice.
     */
    private fun applyAnchorState(busy: Boolean) {
        anchorButtons.forEach {
            if (busy) {
                it.setAttribute("aria-disabled", "true")
                it.addCssClass("is-cooling")
            } else {
                it.removeAttribute("aria-disabled")
                it.removeCssClass("is-cooling")
            }
        }
    }

    /** Moves the keyboard focus to the board (used after entering the map). */
    fun focusBoard() {
        (grid.getElement() as? HTMLElement)?.focus()
    }

    /** Moves the focus to "Auf die Karte" when that button is on offer (a walking viewer who is not on the map); `true` if it did. */
    fun focusEnter(): Boolean {
        if (!canWalk || selfOnMap) return false
        val element = enterButton.getElement() as? HTMLElement ?: return false
        element.focus()
        return true
    }

    fun dispose() {
        figures.clear()
        section.hide()
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun buildTiles() {
        for (y in 0 until ENCOUNTER_MAP_ROWS) {
            for (x in 0 until ENCOUNTER_MAP_COLUMNS) {
                val kind =
                    when (template.cell(x, y)) {
                        EncounterMapCell.FLOOR -> "floor"
                        EncounterMapCell.WALL -> "wall"
                        EncounterMapCell.OBJECT -> "object"
                    }
                val tile = grid.div(className = "lapis-encounter-map__tile lapis-encounter-map__tile--$kind")
                tile.setAttribute("aria-hidden", "true")
                tile.setAttribute("data-cell", (y * ENCOUNTER_MAP_COLUMNS + x).toString())
            }
        }
    }

    private fun buildFigure(): Figure {
        val root = figuresLayer.div(className = "lapis-encounter-map__figure")
        root.setAttribute("aria-hidden", "true")
        val initials = Span(className = "lapis-encounter-map__initials")
        root.add(initials)
        return Figure(root = root, initials = initials)
    }

    private fun renderNear(model: EncounterMapModel) {
        nearList.removeAll()
        if (!model.selfOnMap || model.near.isEmpty()) {
            nearHeading.hide()
            nearList.hide()
            return
        }
        nearHeading.content = tr("In Ihrer Nähe")
        nearHeading.show()
        nearList.show()
        model.near.forEach { name ->
            val item = Tag(TAG.LI)
            untrustedContent(item, name)
            nearList.add(item)
        }
        if (model.nearMore > 0) nearList.add(Tag(TAG.LI, content = gettext("und %1 weitere", model.nearMore)))
    }

    private fun buildPad() {
        pad.setAttribute("role", "group")
        pad.setAttribute("aria-label", gettext("Richtungstasten"))
        listOf(
            Pair(EncounterMapFacing.NORTH, Triple(gettext("Nach oben"), "▲", "up")),
            Pair(EncounterMapFacing.WEST, Triple(gettext("Nach links"), "◀", "left")),
            Pair(EncounterMapFacing.EAST, Triple(gettext("Nach rechts"), "▶", "right")),
            Pair(EncounterMapFacing.SOUTH, Triple(gettext("Nach unten"), "▼", "down")),
        ).forEach { (dir, look) ->
            val (label, glyph, area) = look
            val button =
                pad.tag(
                    TAG.BUTTON,
                    className = "btn btn-outline-secondary lapis-encounter-map__pad-button lapis-encounter-map__pad-button--$area",
                )
            button.setAttribute("type", "button")
            button.setAttribute("aria-label", label)
            button.add(Span(content = glyph).also { it.setAttribute("aria-hidden", "true") })
            button.onClick { handler.step(dir) }
        }
    }

    private fun onKey(event: KeyboardEvent) {
        if (event.ctrlKey || event.altKey || event.metaKey) return
        val dir =
            when (event.key) {
                "ArrowUp", "w", "W" -> EncounterMapFacing.NORTH
                "ArrowDown", "s", "S" -> EncounterMapFacing.SOUTH
                "ArrowLeft", "a", "A" -> EncounterMapFacing.WEST
                "ArrowRight", "d", "D" -> EncounterMapFacing.EAST
                "l", "L" -> {
                    event.preventDefault()
                    handler.describeAloud()
                    return
                }
                "Escape" -> {
                    // On the map, the first Escape moves to "Karte verlassen" (the overlay stays); in every other case it is left alone, so the
                    // overlay around the view closes. Closing the overlay never leaves the map.
                    val leave = leaveButton.getElement() as? HTMLElement
                    if (selfOnMap && canWalk && leave != null && document.activeElement != leave) {
                        leave.focus()
                        event.preventDefault()
                        event.stopPropagation()
                    }
                    return
                }
                else -> return
            }
        event.preventDefault()
        if (canWalk) handler.step(dir)
    }

    private fun onBoardClick(target: org.w3c.dom.events.EventTarget?) {
        if (!canWalk) return
        val index = (target as? Element)?.closest("[data-cell]")?.getAttribute("data-cell")?.toIntOrNull() ?: return
        handler.walkTo(index)
    }

    private fun facingClass(facing: EncounterMapFacing): String =
        when (facing) {
            EncounterMapFacing.NORTH -> "is-n"
            EncounterMapFacing.EAST -> "is-e"
            EncounterMapFacing.SOUTH -> "is-s"
            EncounterMapFacing.WEST -> "is-w"
        }

    private companion object {
        const val STATUS_ID = "lapis-encounter-map-status"
    }
}
