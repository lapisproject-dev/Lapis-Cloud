package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import kotlinx.browser.window
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.shared.domain.EncounterReactionOption

/** How long an event reaction (amen, applause, heart) stays visible at its seat, in milliseconds (a short, quiet symbol, never a counter). */
internal const val ENCOUNTER_EVENT_VISIBLE_MS = 3_000

/**
 * V1.9.62 Begegnungsraum (B2) -- the two layers of the room (design team, Duarte: scene and operation in separate layers):
 *
 * - **Layer 0, the scene** (`lapis-encounter-scene-front` and `lapis-encounter-scene-rows`): a floor plan seen from above, drawn as CSS
 *   masks, `aria-hidden`: the front (chancel or podium) once at the top, one row of benches or chairs tiled behind the seats. Switched
 *   off by "Szene aus", by forced-colours/high-contrast modes and removed from the picture when switched off. Which drawing is used
 *   comes from the room's [EncounterTerms] (the profile); this class never asks the profile itself.
 * - **Layer 1, the stage** (`lapis-encounter-stage`): an opaque surface (`--lapis-encounter-surface`) with the pulpit, the stewards'
 *   strip and the pews. Text is always on this surface, never on the scene, so contrast does not depend on the motif.
 *
 * The pews: [EncounterSeatGrid]. A seat shows initials only; it is a `listitem` of the `list` "Gemeinde" with an accessible name and
 * no button, no tab stop. Hand and amen are icon children (`aria-hidden`), the hand is also part of the accessible name.
 */
internal class EncounterSceneLayout(
    parent: Container,
    private val terms: EncounterTerms,
) {
    val root: Div = parent.div(className = "lapis-encounter")
    private val sceneLayer: Div = root.div(className = "lapis-encounter-scene lapis-encounter-scene-front")
    private val stage: Div = root.div(className = "lapis-encounter-stage")

    /** The pulpit region; the keyboard focus lands here after entering (`tabindex=-1`: focusable by script, not a tab stop). */
    val pulpit: Div = stage.div(className = "lapis-encounter-pulpit")
    private val pulpitTiles: Div = pulpit.div(className = "lapis-encounter-pulpit-tiles")
    private val pulpitEmpty: Div = pulpit.div(terms.emptyStageContent(), className = "lapis-encounter-pulpit-empty")
    private val stewards: Div = stage.div(className = "lapis-encounter-stewards")
    private val benchesFrame: Div = stage.div(className = "lapis-encounter-benches-frame")
    private val rowsLayer: Div = benchesFrame.div(className = "lapis-encounter-scene lapis-encounter-scene-rows")
    private val benches: Div = benchesFrame.div(className = "lapis-encounter-benches")
    val seats: EncounterSeatGrid = EncounterSeatGrid(benches)

    init {
        sceneLayer.setAttribute("aria-hidden", "true")
        rowsLayer.setAttribute("aria-hidden", "true")
        // The decorative masks: set inline because the bundler would try to resolve a `url()` in theme.css at build time, and the files are
        // runtime assets served from `/assets` (staged by `stageVideoEffectAssets`). Size, repeat, colour and visibility come from theme.css
        // through the custom properties below.
        sceneLayer.setStyle("--lapis-enc-scene-front", "url(\"${terms.sceneFrontPath}\")")
        rowsLayer.setStyle("--lapis-enc-scene-row", "url(\"${terms.sceneRowPath}\")")
        pulpit.setAttribute("tabindex", "-1")
        pulpit.setAttribute("role", "group")
        benches.setAttribute("role", "list")
        benches.setAttribute("aria-label", terms.audienceName())
        stewards.setAttribute("role", "group")
        stewards.setAttribute("aria-label", terms.stewardsName())
        stewards.hide()
        setPulpitNames(emptyList())
    }

    /** Shows [tiles] on the pulpit (the first one large). The pulpit announces whom it holds, or that it is empty. */
    fun setPulpit(tiles: List<EncounterTile>) {
        pulpitTiles.removeAll()
        tiles.forEach { it.attachTo(pulpitTiles) }
        if (tiles.isEmpty()) pulpitEmpty.show() else pulpitEmpty.hide()
    }

    fun setStewards(tiles: List<EncounterTile>) {
        stewards.removeAll()
        tiles.forEach { it.attachTo(stewards) }
        if (tiles.isEmpty()) stewards.hide() else stewards.show()
    }

    /** The accessible name of the stage region: "<speakers>: <names>" (names are untrusted and sanitised) or the empty-stage sentence. */
    fun setPulpitNames(names: List<String>) {
        val label =
            if (names.isEmpty()) {
                terms.emptyStage()
            } else {
                terms.stageNamed(sanitizeUntrustedI18nText(names.joinToString(", ")))
            }
        pulpit.setAttribute("aria-label", label)
    }

    /** "Szene aus": the scene layer leaves the picture entirely (display none via the modifier class), the stage stays as it is. */
    fun setSceneOff(off: Boolean) {
        if (off) root.addCssClass("lapis-encounter--scene-off") else root.removeCssClass("lapis-encounter--scene-off")
    }

    val isSceneOff: Boolean get() = root.hasCssClass("lapis-encounter--scene-off")

    /** Moves the keyboard focus to the pulpit once it is in the document (KVision patches the DOM asynchronously). */
    fun focusPulpit() {
        var attempts = 0

        fun attempt() {
            val element = pulpit.getElement()
            if (element != null) {
                element.focus()
            } else if (attempts++ < FOCUS_RETRIES) {
                window.setTimeout({ attempt() }, FOCUS_RETRY_MS)
            }
        }
        attempt()
    }

    fun dispose() {
        seats.dispose()
    }

    private companion object {
        const val FOCUS_RETRIES = 30
        const val FOCUS_RETRY_MS = 16
    }
}

private class SeatCell(
    val root: Div,
    val initials: Span,
    val hand: Span,
    val event: Span,
)

/**
 * The pews as widgets: [blocks] blocks of [perBlock] seats per row, built row by row as the [EncounterSeating] plan grows (it never
 * shrinks). The seat INDEX is the position -- a cell is only ever updated in place, never moved, so people coming and going do not
 * shift anyone. Event symbols (amen, applause, heart) disappear on their own after [ENCOUNTER_EVENT_VISIBLE_MS]; a seat has ONE event slot,
 * a newer event replaces the older one.
 */
internal class EncounterSeatGrid(
    private val host: Div,
    private val blocks: Int = 2,
    private val perBlock: Int = 3,
) {
    private val perRow = blocks * perBlock
    private val cells = mutableListOf<SeatCell>()
    private val timers = mutableSetOf<Int>()
    private val eventTimers = mutableMapOf<Int, Int>()

    val seatCount: Int get() = cells.size

    /** Builds rows until [count] seats exist. */
    fun ensureSeats(count: Int) {
        while (cells.size < count) buildRow()
    }

    private fun buildRow() {
        val row = host.div(className = "lapis-encounter-row")
        row.setAttribute("role", "presentation")
        repeat(blocks) {
            val block = row.div(className = "lapis-encounter-block")
            block.setAttribute("role", "presentation")
            repeat(perBlock) { cells += buildCell(block) }
        }
    }

    private fun buildCell(block: Div): SeatCell {
        val cell = block.div(className = "lapis-encounter-seat lapis-encounter-seat--empty")
        cell.setAttribute("aria-hidden", "true")
        val initials = cell.span(className = "lapis-encounter-seat-initials")
        val hand = cell.span(className = "lapis-encounter-seat-hand")
        hand.icon("fas fa-hand")
        hand.setAttribute("aria-hidden", "true")
        val event = cell.span(className = "lapis-encounter-seat-event")
        event.setAttribute("aria-hidden", "true")
        return SeatCell(root = cell, initials = initials, hand = hand, event = event)
    }

    /** Updates one seat in place. [name] `null` = empty seat. */
    fun update(
        seat: Int,
        name: String?,
        handUp: Boolean,
    ) {
        val cell = cells.getOrNull(seat) ?: return
        if (name == null) {
            cell.root.setAttribute("aria-hidden", "true")
            cell.root.removeAttribute("role")
            cell.root.removeAttribute("aria-label")
            cell.root.removeAttribute("title")
            cell.root.addCssClass("lapis-encounter-seat--empty")
            untrustedContent(cell.initials, "")
            cell.hand.removeCssClass("is-on")
            cell.event.removeCssClass("is-on")
            return
        }
        val safe = sanitizeUntrustedI18nText(name)
        val accessibleName = if (handUp) gettext("%1, Hand erhoben", safe) else safe
        cell.root.removeAttribute("aria-hidden")
        cell.root.setAttribute("role", "listitem")
        cell.root.setAttribute("aria-label", accessibleName)
        cell.root.setAttribute("title", safe)
        cell.root.removeCssClass("lapis-encounter-seat--empty")
        untrustedContent(cell.initials, encounterInitials(name))
        if (handUp) cell.hand.addCssClass("is-on") else cell.hand.removeCssClass("is-on")
    }

    /** Shows the symbol of [option] (amen, applause, heart) at [seat] for a few seconds; a newer event replaces the one shown. */
    fun showEvent(
        seat: Int,
        option: EncounterReactionOption,
    ) {
        val cell = cells.getOrNull(seat) ?: return
        cell.event.removeAll()
        cell.event.icon(reactionGlyph(option))
        cell.event.setAttribute("data-reaction", option.name)
        cell.event.addCssClass("is-on")
        eventTimers.remove(seat)?.let { old ->
            window.clearTimeout(old)
            timers.remove(old)
        }
        var handle = 0
        handle =
            window.setTimeout({
                timers.remove(handle)
                eventTimers.remove(seat)
                cell.event.removeCssClass("is-on")
            }, ENCOUNTER_EVENT_VISIBLE_MS)
        timers += handle
        eventTimers[seat] = handle
    }

    /** True while an event symbol of [seat] is shown (tests). */
    fun eventVisible(seat: Int): Boolean = cells.getOrNull(seat)?.event?.hasCssClass("is-on") == true

    /** The reaction whose symbol [seat] shows right now, or `null` (tests). */
    fun eventShown(seat: Int): EncounterReactionOption? =
        cells
            .getOrNull(seat)
            ?.event
            ?.takeIf { it.hasCssClass("is-on") }
            ?.getAttribute("data-reaction")
            ?.let { name -> EncounterReactionOption.entries.firstOrNull { it.name == name } }

    fun dispose() {
        timers.forEach { window.clearTimeout(it) }
        timers.clear()
        eventTimers.clear()
    }
}
