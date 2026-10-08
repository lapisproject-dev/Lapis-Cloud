package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.tag
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.shared.domain.ENCOUNTER_SEATS_PER_ROW
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.encounterSeatPosition
import network.lapis.cloud.shared.domain.encounterSeatRow
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

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
 * The pews: [EncounterSeatGrid]. Since V1.9.79 (stage 2a) a seat is a real `<button>` of the `group` "Sitzplan": a free seat can be chosen
 * (click, Enter, Space), a taken one is `aria-disabled`. A seat shows initials only and its accessible name never contains the name of the
 * person. Hand and reaction are icon children (`aria-hidden`), both are also part of the accessible name.
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

    /** V1.9.79: "Tippen Sie auf einen freien Platz ..." -- shown only to a congregation person who has not chosen a seat. */
    private val seatHint: Tag = benchesFrame.tag(TAG.P, content = terms.chooseSeatHint(), className = "lapis-encounter-seat-hint")
    private val benches: Div = benchesFrame.div(className = "lapis-encounter-benches")
    val seats: EncounterSeatGrid = EncounterSeatGrid(benches, terms)

    /** V1.9.79: the people without a seat -- a quiet row of symbols below the pews (not operable). */
    val unseated: EncounterUnseatedRow = EncounterUnseatedRow(benchesFrame, terms)

    /** V1.9.79: gives up the viewer's own seat; a SECONDARY button (R36), visible only while the viewer sits. */
    val releaseSeatButton: Button =
        benchesFrame.actionButton(
            ActionIcon.RELEASE_SEAT,
            terms.releaseSeatLabelContent(),
            style = ButtonStyle.OUTLINESECONDARY,
            small = true,
        )

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
        stewards.setAttribute("role", "group")
        stewards.setAttribute("aria-label", terms.stewardsName())
        stewards.hide()
        setPulpitNames(emptyList())
        seatHint.hide()
        releaseSeatButton.addCssClass("lapis-encounter-seat-release")
        releaseSeatButton.hide()
        // The audience-muted sentence describes the plan: a visible, quiet line (not only for screen readers).
        val note = benchesFrame.tag(TAG.P, content = terms.audienceMutedNote(), className = "lapis-encounter-seat-note")
        note.setAttribute("id", SEAT_NOTE_ID)
        benches.setAttribute("aria-describedby", SEAT_NOTE_ID)
    }

    /** Shows the "choose a seat" hint (the viewer is in the congregation and has no seat). */
    fun setSeatHintVisible(visible: Boolean) {
        if (visible) seatHint.show() else seatHint.hide()
    }

    /** Shows "Platz freigeben" (the viewer sits). */
    fun setReleaseVisible(visible: Boolean) {
        if (visible) releaseSeatButton.show() else releaseSeatButton.hide()
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
        unseated.dispose()
    }

    private companion object {
        const val FOCUS_RETRIES = 30
        const val FOCUS_RETRY_MS = 16
        const val SEAT_NOTE_ID = "lapis-encounter-seat-note"
    }
}

/** What one seat shows. [initials] `null` = a free seat. */
internal class SeatSlot(
    val initials: String?,
    val handUp: Boolean = false,
    val own: Boolean = false,
    /** The viewer just chose this seat and the server has not answered yet. */
    val pending: Boolean = false,
    /** `false` for a free seat the server would refuse (beyond the current capacity): shown, but not choosable. */
    val offered: Boolean = true,
)

/** The whole picture for one render: one slot per seat, in seat order. */
internal class SeatGridModel(
    val slots: List<SeatSlot>,
    /** `false` for somebody who cannot sit (an office holder): a free seat is then shown, but offers no choice. */
    val choosable: Boolean = true,
)

private class SeatCell(
    val root: Tag,
    val initials: Span,
    val hand: Span,
    val event: Span,
    val self: Span,
) {
    var slot: SeatSlot = SeatSlot(initials = null)
    var choosable: Boolean = true
    var eventOption: EncounterReactionOption? = null
}

/**
 * The pews as widgets (V1.9.79, stage 2a): [blocks] blocks of [perBlock] seats per row, one `<button type="button">` per seat in a
 * `role="group"` named "Sitzplan". The seat INDEX is the position -- a cell is only ever updated in place (never rebuilt, never moved), so
 * the keyboard focus survives every refresh of the presence list and nobody shifts.
 *
 * - **Operation**: a free seat calls [onChoose]; a taken seat (also the viewer's own) is `aria-disabled` and does nothing. Enter and
 *   Space are the native button activation; a seat is NEVER a second primary action (R36), it is the room's own control.
 * - **Keyboard**: roving tabindex -- exactly one seat has `tabindex=0` (the viewer's own seat, else the first free one, else seat 0), so
 *   Tab passes the plan in one stop. Arrow left/right move along the row over the aisle (no wrap into the next row), up/down to the same
 *   position of the neighbouring row, Home/End to the start/end of the row. No focus trap: Tab leaves the plan.
 * - **No name**: the accessible name of a seat is row, position, state and the INITIALS spelled letter by letter; there is no `title`.
 *   A hand and a shown reaction are part of the name.
 * - Reaction symbols disappear on their own after [ENCOUNTER_EVENT_VISIBLE_MS]; a seat has ONE event slot, a newer event replaces the older.
 */
internal class EncounterSeatGrid(
    private val host: Div,
    private val terms: EncounterTerms,
    private val blocks: Int = 2,
    private val perBlock: Int = ENCOUNTER_SEATS_PER_ROW / 2,
) {
    private val perRow = blocks * perBlock
    private val cells = mutableListOf<SeatCell>()
    private val rows = mutableListOf<Div>()
    private val timers = mutableSetOf<Int>()
    private val eventTimers = mutableMapOf<Int, Int>()
    private var visibleSeats = 0
    private var activeSeat = 0

    /** Called with the seat index when a FREE seat is chosen. */
    var onChoose: (Int) -> Unit = {}

    init {
        host.setAttribute("role", "group")
        host.setAttribute("aria-label", terms.seatPlanLabel())
        host.onEvent { keydown = { event -> onKey(event) } }
    }

    val seatCount: Int get() = visibleSeats

    /** Draws [model]: builds missing rows, hides rows beyond it, updates every seat in place and keeps exactly one tab stop. */
    fun render(model: SeatGridModel) {
        val wanted = model.slots.size
        while (cells.size < wanted) buildRow()
        visibleSeats = wanted
        rows.forEachIndexed { index, row -> if (index * perRow < wanted) row.show() else row.hide() }
        model.slots.forEachIndexed { seat, slot -> apply(seat, slot, model.choosable) }
        for (seat in wanted until cells.size) cells[seat].eventOption = null
        activeSeat = restingSeat(model)
        refreshTabStops()
    }

    /** Moves the keyboard focus to [seat] once it is in the document (KVision patches the DOM asynchronously). */
    fun focusSeat(seat: Int) {
        if (seat !in 0 until visibleSeats) return
        activeSeat = seat
        refreshTabStops()
        var attempts = 0

        fun attempt() {
            val element = cells.getOrNull(seat)?.root?.getElement() as? HTMLElement
            if (element != null) {
                element.focus()
            } else if (attempts++ < FOCUS_RETRIES) {
                window.setTimeout({ attempt() }, FOCUS_RETRY_MS)
            }
        }
        attempt()
    }

    private fun restingSeat(model: SeatGridModel): Int {
        // While the keyboard focus is inside the plan the roving position stays where the person put it; otherwise: own seat, first free
        // seat, seat 0.
        val focusInside = host.getElement()?.contains(document.activeElement) == true
        if (focusInside && activeSeat in model.slots.indices) return activeSeat
        model.slots
            .indexOfFirst { it.own }
            .takeIf { it >= 0 }
            ?.let { return it }
        model.slots
            .indexOfFirst { it.initials == null }
            .takeIf { it >= 0 }
            ?.let { return it }
        return 0
    }

    private fun buildRow() {
        val row = host.div(className = "lapis-encounter-row")
        row.setAttribute("role", "presentation")
        rows += row
        repeat(blocks) {
            val block = row.div(className = "lapis-encounter-block")
            block.setAttribute("role", "presentation")
            repeat(perBlock) { cells += buildCell(block, seat = cells.size) }
        }
    }

    private fun buildCell(
        block: Div,
        seat: Int,
    ): SeatCell {
        val cell = block.tag(TAG.BUTTON, className = "lapis-encounter-seat lapis-encounter-seat--free")
        cell.setAttribute("type", "button")
        cell.setAttribute("data-seat", seat.toString())
        cell.setAttribute("tabindex", "-1")
        val initials = Span(className = "lapis-encounter-seat-initials")
        cell.add(initials)
        val hand = Span(className = "lapis-encounter-seat-hand")
        hand.icon("fas fa-hand")
        hand.setAttribute("aria-hidden", "true")
        cell.add(hand)
        val event = Span(className = "lapis-encounter-seat-event")
        event.setAttribute("aria-hidden", "true")
        cell.add(event)
        val self = Span(content = terms.ownSeatMarker(), className = "lapis-encounter-seat-self")
        self.setAttribute("aria-hidden", "true")
        cell.add(self)
        val created = SeatCell(root = cell, initials = initials, hand = hand, event = event, self = self)
        cell.onClick { if (created.slot.initials == null && !created.slot.pending && created.choosable) onChoose(seat) }
        return created
    }

    private fun apply(
        seat: Int,
        slot: SeatSlot,
        choosable: Boolean,
    ) {
        val cell = cells[seat]
        cell.slot = slot
        cell.choosable = choosable && slot.offered
        val free = slot.initials == null
        cell.root.setClass("lapis-encounter-seat--free", free)
        cell.root.setClass("lapis-encounter-seat--taken", !free)
        cell.root.setClass("lapis-encounter-seat--own", slot.own)
        cell.root.setClass("lapis-encounter-seat--pending", slot.pending)
        if (slot.pending) cell.root.setAttribute("aria-busy", "true") else cell.root.removeAttribute("aria-busy")
        // A taken seat (also the own one) and a pending one do nothing when pressed; they stay focusable (aria-disabled, not disabled).
        if (free &&
            !slot.pending &&
            cell.choosable
        ) {
            cell.root.removeAttribute("aria-disabled")
        } else {
            cell.root.setAttribute("aria-disabled", "true")
        }
        untrustedContent(cell.initials, slot.initials.orEmpty())
        if (slot.handUp) cell.hand.addCssClass("is-on") else cell.hand.removeCssClass("is-on")
        if (slot.own) cell.self.show() else cell.self.hide()
        relabel(seat)
    }

    private fun Tag.setClass(
        name: String,
        on: Boolean,
    ) {
        if (on) addCssClass(name) else removeCssClass(name)
    }

    private fun relabel(seat: Int) {
        val cell = cells[seat]
        val row = encounterSeatRow(seat)
        val position = encounterSeatPosition(seat)
        val slot = cell.slot
        val label =
            when {
                slot.own -> terms.ownSeatLabel(row, position)
                slot.initials == null && !cell.choosable -> terms.freeSeatPlainLabel(row, position)
                slot.initials == null -> terms.freeSeatLabel(row, position)
                else -> terms.takenSeatLabel(row, position, spelled(slot.initials))
            }
        val suffix =
            buildString {
                if (slot.initials != null && slot.handUp) append(terms.handRaisedSuffix())
                cell.eventOption?.let { append(terms.reactionSuffix(it)) }
            }
        cell.root.setAttribute("aria-label", sanitizeUntrustedI18nText(label + suffix))
        // No `title`: the name of a person must not appear as a tooltip either.
        cell.root.removeAttribute("title")
    }

    /** "MS" -> "M S": a screen reader spells initials instead of trying to pronounce them. */
    private fun spelled(initials: String): String = initials.toList().joinToString(" ")

    private fun refreshTabStops() {
        cells.forEachIndexed { seat, cell ->
            val stop = seat == activeSeat && seat < visibleSeats
            cell.root.setAttribute("tabindex", if (stop) "0" else "-1")
            cell.root.setAttribute("data-tab-set", if (stop) "1" else "0")
        }
    }

    private fun onKey(event: KeyboardEvent) {
        val target = (event.target as? HTMLElement)?.closest("[data-seat]") as? HTMLElement ?: return
        val seat = target.getAttribute("data-seat")?.toIntOrNull() ?: return
        val row = seat / perRow
        val rowStart = row * perRow
        val rowEnd = minOf(visibleSeats, rowStart + perRow) - 1
        val next =
            when (event.key) {
                "ArrowLeft" -> if (seat > rowStart) seat - 1 else null
                "ArrowRight" -> if (seat < rowEnd) seat + 1 else null
                "ArrowUp" -> (seat - perRow).takeIf { it >= 0 }
                "ArrowDown" -> (seat + perRow).takeIf { it < visibleSeats }
                "Home" -> rowStart
                "End" -> rowEnd
                else -> return
            }
        event.preventDefault()
        if (next != null && next != seat) focusSeat(next)
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
        cell.eventOption = option
        relabel(seat)
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
                cell.eventOption = null
                relabel(seat)
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

    private companion object {
        const val FOCUS_RETRIES = 30
        const val FOCUS_RETRY_MS = 16
    }
}

/**
 * V1.9.79: the people who have not chosen a seat, as a quiet row of symbols under the pews: initials, a hand badge and a reaction glyph,
 * NOT operable (a person without a seat is not a control). The accessible name of an entry is initials spelled and state only -- no name.
 * Hidden while nobody is without a seat.
 */
internal class EncounterUnseatedRow(
    parent: Container,
    private val terms: EncounterTerms,
) {
    val root: Div = parent.div(className = "lapis-encounter-unseated")
    private val list: Div = root.div(className = "lapis-encounter-unseated-list")
    private val entries = LinkedHashMap<String, UnseatedEntry>()
    private val timers = mutableSetOf<Int>()
    private var lastOrder: List<String> = emptyList()

    private class UnseatedEntry(
        val root: Span,
        val initials: Span,
        val hand: Span,
        val event: Span,
        var initialsText: String,
        var handUp: Boolean = false,
        var eventOption: EncounterReactionOption? = null,
        var timer: Int? = null,
    )

    init {
        root.setAttribute("role", "group")
        root.setAttribute("aria-label", terms.unseatedRowLabel())
        root.hide()
    }

    /** Draws [people] (identity to name) in the given order; [handUp] says whose hand is up. Entries are updated in place. */
    fun render(
        people: List<Pair<String, String>>,
        handUp: (String) -> Boolean,
    ) {
        val ids = people.map { it.first }.toSet()
        entries.keys.filter { it !in ids }.forEach { id ->
            entries.remove(id)?.let { entry ->
                entry.timer?.let { handle ->
                    window.clearTimeout(handle)
                    timers.remove(handle)
                }
                list.remove(entry.root)
            }
        }
        if (people.isEmpty()) lastOrder = emptyList()
        people.forEach { (identity, name) ->
            val entry = entries.getOrPut(identity) { build(identity) }
            entry.initialsText = encounterInitials(name)
            entry.handUp = handUp(identity)
            untrustedContent(entry.initials, entry.initialsText)
            if (entry.handUp) entry.hand.addCssClass("is-on") else entry.hand.removeCssClass("is-on")
            relabel(entry)
        }
        // Sorted by name, like the list: a newcomer takes its place in the order, the entries themselves are reused.
        val order = people.map { it.first }
        if (order != lastOrder) {
            list.removeAll()
            order.forEach { id -> entries[id]?.let { list.add(it.root) } }
            lastOrder = order
        }
        if (entries.isEmpty()) root.hide() else root.show()
    }

    private fun build(identity: String): UnseatedEntry {
        val item = Span(className = "lapis-encounter-unseated-item")
        item.setAttribute("role", "img")
        item.setAttribute("data-identity", identity)
        val initials = Span(className = "lapis-encounter-seat-initials")
        item.add(initials)
        val hand = Span(className = "lapis-encounter-seat-hand")
        hand.icon("fas fa-hand")
        hand.setAttribute("aria-hidden", "true")
        item.add(hand)
        val event = Span(className = "lapis-encounter-seat-event")
        event.setAttribute("aria-hidden", "true")
        item.add(event)
        return UnseatedEntry(root = item, initials = initials, hand = hand, event = event, initialsText = "")
    }

    private fun relabel(entry: UnseatedEntry) {
        val base = entry.initialsText.toList().joinToString(" ")
        val suffix =
            buildString {
                if (entry.handUp) append(terms.handRaisedSuffix())
                entry.eventOption?.let { append(terms.reactionSuffix(it)) }
            }
        entry.root.setAttribute("aria-label", sanitizeUntrustedI18nText(base + suffix))
    }

    /** Shows a reaction glyph at the entry of [identity] for a few seconds; `false` when that person is not in the row. */
    fun showEvent(
        identity: String,
        option: EncounterReactionOption,
    ): Boolean {
        val entry = entries[identity] ?: return false
        entry.event.removeAll()
        entry.event.icon(reactionGlyph(option))
        entry.event.setAttribute("data-reaction", option.name)
        entry.event.addCssClass("is-on")
        entry.eventOption = option
        relabel(entry)
        entry.timer?.let { old ->
            window.clearTimeout(old)
            timers.remove(old)
        }
        var handle = 0
        handle =
            window.setTimeout({
                timers.remove(handle)
                entry.timer = null
                entry.event.removeCssClass("is-on")
                entry.eventOption = null
                relabel(entry)
            }, ENCOUNTER_EVENT_VISIBLE_MS)
        timers += handle
        entry.timer = handle
        return true
    }

    fun contains(identity: String): Boolean = identity in entries

    /** Accessible names of the entries in order (tests). */
    fun labels(): List<String> = entries.values.map { it.root.getAttribute("aria-label").orEmpty() }

    fun dispose() {
        timers.forEach { window.clearTimeout(it) }
        timers.clear()
    }
}
