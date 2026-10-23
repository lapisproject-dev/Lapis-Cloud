package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.RoomBarZone
import network.lapis.cloud.client.RoomControlBar
import network.lapis.cloud.client.newIconOnlyActionButton
import network.lapis.cloud.client.resolvedAttributeText
import network.lapis.cloud.shared.domain.EncounterReactionOption

/**
 * The groups of the control bar of the encounter room, in DOM order. V1.9.184: the bar has three zones (see `RoomControlBar`) -- [SETTINGS]
 * alone at the start, the middle in the centre, [EXIT] at the end. A divider (CSS only) separates two non-empty groups of the centre.
 *
 * - [SETTINGS] (start zone): the gear (device picker, "Einstellungen") -- alone at the left edge, for every role.
 * - [DEVICES]: first in the centre: microphone and camera -- only an office holder's session fills this group; also the table microphone and
 *   "Kanzel lauter" of a congregation person who sits at a table (the two sets never occur together).
 * - [REACTIONS]: the configured reactions of the room (hand always first, then the allowed events) -- the only controls with a word.
 * - [LITURGY] (V1.9.95/V1.9.96): the bell, then the blessing -- ONLY in a church-service room and ONLY for the pulpit; the group does not exist in the
 *   DOM otherwise.
 * - [PANELS]: the side panel (chat) and the map button.
 * - [VIEW]: scene on/off and the full screen.
 * - [RECORDING] (V1.9.143): start/stop of the pulpit recording -- ONLY for PULPIT/STEWARD of a room whose recording option is on; the group does not exist in the
 *   DOM otherwise. Since V1.9.184 it stands behind the view group, never next to the reactions or the exit.
 * - [MODERATION]: the transmission (people who moderate).
 * - [MORE]: "Mehr" (the sheet with what did not fit), directly before the end zone. Empty (and so not rendered) while nothing moved.
 * - [EXIT] (V1.9.74, end zone): "Türen schließen" (people who moderate) and "Verlassen", 12 px apart.
 */
internal enum class EncounterControlGroup { SETTINGS, DEVICES, REACTIONS, LITURGY, PANELS, VIEW, RECORDING, MODERATION, MORE, EXIT }

/** A control of the bar that the overflow handler knows about ("Mehr" itself is the sheet's opener and never moves). */
internal sealed interface EncounterControlSlot {
    data object Hand : EncounterControlSlot

    data class Reaction(
        val option: EncounterReactionOption,
    ) : EncounterControlSlot

    data object Mic : EncounterControlSlot

    data object Camera : EncounterControlSlot

    /** V1.9.80: the microphone of the viewer's own table (a congregation person; shown only while seated). */
    data object TableMic : EncounterControlSlot

    /** V1.9.80: "Kanzel lauter" (profile word: "Podium lauter" in an assembly) -- turns the pulpit up again while the viewer sits at a table. */
    data object PulpitLouder : EncounterControlSlot

    /** V1.9.91: the device picker (gear). V1.9.184: alone in the start zone; the third-to-last control to move into the sheet. */
    data object AudioDevices : EncounterControlSlot

    /** V1.9.95: the pulpit's blessing (church profile only). */
    data object Blessing : EncounterControlSlot

    /** V1.9.96: the pulpit's bell (church profile only); stands before the blessing in the liturgy group. */
    data object Bell : EncounterControlSlot

    /** V1.9.143: start/stop of the pulpit recording (PULPIT/STEWARD, room option on); a toggle with `aria-pressed`. */
    data object Recording : EncounterControlSlot

    data object Chat : EncounterControlSlot

    /** V1.9.167: opens the map overlay (members only; the slot is created inside `ensureMap`, after the guest guard). Stands in the panels group, before "Mehr". */
    data object Map : EncounterControlSlot

    data object More : EncounterControlSlot

    data object Scene : EncounterControlSlot

    data object Fullscreen : EncounterControlSlot

    data object Broadcast : EncounterControlSlot

    data object CloseDoors : EncounterControlSlot

    data object Leave : EncounterControlSlot
}

internal fun encounterControlGroup(slot: EncounterControlSlot): EncounterControlGroup =
    when (slot) {
        EncounterControlSlot.Hand, is EncounterControlSlot.Reaction -> EncounterControlGroup.REACTIONS
        EncounterControlSlot.Mic, EncounterControlSlot.Camera, EncounterControlSlot.TableMic, EncounterControlSlot.PulpitLouder,
        ->
            EncounterControlGroup.DEVICES
        EncounterControlSlot.AudioDevices -> EncounterControlGroup.SETTINGS
        EncounterControlSlot.Bell, EncounterControlSlot.Blessing -> EncounterControlGroup.LITURGY
        EncounterControlSlot.Recording -> EncounterControlGroup.RECORDING
        EncounterControlSlot.Chat, EncounterControlSlot.Map -> EncounterControlGroup.PANELS
        EncounterControlSlot.More -> EncounterControlGroup.MORE
        EncounterControlSlot.Scene, EncounterControlSlot.Fullscreen -> EncounterControlGroup.VIEW
        EncounterControlSlot.Broadcast -> EncounterControlGroup.MODERATION
        EncounterControlSlot.CloseDoors, EncounterControlSlot.Leave -> EncounterControlGroup.EXIT
    }

/** The zone of a group (V1.9.184): the settings gear at the start, the way out at the end, everything else in the centre. */
internal fun encounterControlZone(group: EncounterControlGroup): RoomBarZone =
    when (group) {
        EncounterControlGroup.SETTINGS -> RoomBarZone.START
        EncounterControlGroup.EXIT -> RoomBarZone.END
        else -> RoomBarZone.CENTRE
    }

internal fun encounterControlZone(slot: EncounterControlSlot): RoomBarZone = encounterControlZone(encounterControlGroup(slot))

/**
 * The order in which controls move into the "Mehr" sheet, the first one first: full screen, scene, transmission, then the event
 * reactions (the last of the canonical order first), then the bell (V1.9.96, [bell] only), the blessing (V1.9.95, [blessing] only: it stays
 * in the bar as long as possible), the recording (V1.9.143), the map button (V1.9.167, [map] only) and the chat. V1.9.184 appends
 * "Türen schließen" (which used to move in the middle of the list), the gear, the camera and the microphone: the way out and the devices
 * are the last thing a narrow bar gives up. Never moved: hand (the only reaction with a state; a hand-raise must never need a second tap),
 * "Mehr" and "Verlassen". Built at run time because the event reactions differ per room profile.
 */
internal fun encounterOverflowOrder(
    allowed: Collection<EncounterReactionOption>,
    blessing: Boolean = false,
    bell: Boolean = false,
    recording: Boolean = false,
    /** V1.9.167: `true` for a room with a map; the map button then moves into the sheet directly BEFORE the chat. */
    map: Boolean = false,
): List<EncounterControlSlot> =
    buildList {
        add(EncounterControlSlot.Fullscreen)
        add(EncounterControlSlot.Scene)
        add(EncounterControlSlot.Broadcast)
        EncounterReactionOption.entries
            .filter { it != EncounterReactionOption.ALWAYS_ON && it in allowed }
            .reversed()
            .forEach { add(EncounterControlSlot.Reaction(it)) }
        if (bell) add(EncounterControlSlot.Bell)
        if (blessing) add(EncounterControlSlot.Blessing)
        // V1.9.143: the recording control stays in the bar as long as possible (only the chat outlasts it) and so always has the chat or "Mehr" next to it.
        if (recording) add(EncounterControlSlot.Recording)
        // V1.9.167: the map button moves into the sheet just before the chat (only the chat outlasts it).
        if (map) add(EncounterControlSlot.Map)
        add(EncounterControlSlot.Chat)
        // V1.9.184: the way out and the devices go last. "Türen schließen" is a red entry at the end of the sheet (rule above it, V1.9.72).
        add(EncounterControlSlot.CloseDoors)
        add(EncounterControlSlot.AudioDevices)
        add(EncounterControlSlot.Camera)
        add(EncounterControlSlot.Mic)
    }

/** Pure invariant (V1.9.184): the settings gear is the first control of the bar (alone in the start zone). */
internal fun settingsIsFirst(shownInOrder: List<EncounterControlSlot>): Boolean {
    val gear = shownInOrder.indexOf(EncounterControlSlot.AudioDevices)
    return gear <= 0
}

/** Pure invariant (V1.9.184): when "Mehr" is shown it stands directly before the end zone (the exit controls). */
internal fun moreImmediatelyBeforeEnd(shownInOrder: List<EncounterControlSlot>): Boolean {
    val more = shownInOrder.indexOf(EncounterControlSlot.More)
    if (more < 0) return true
    val next = shownInOrder.getOrNull(more + 1) ?: return true
    return encounterControlZone(next) == RoomBarZone.END
}

/** Pure invariant: "Verlassen" is the last shown control of the bar. */
internal fun encounterLeaveIsLast(shownInOrder: List<EncounterControlSlot>): Boolean =
    shownInOrder.lastOrNull() == EncounterControlSlot.Leave

/** Pure invariant: when "Türen schließen" is shown it stands directly before "Verlassen". */
internal fun doorsImmediatelyBeforeLeave(shownInOrder: List<EncounterControlSlot>): Boolean {
    if (!encounterLeaveIsLast(shownInOrder)) return false
    val doors = shownInOrder.indexOf(EncounterControlSlot.CloseDoors)
    return doors < 0 || doors == shownInOrder.size - 2
}

/** Pure invariant: no moderation control sits directly next to a reaction (a slip must not hit the transmission). */
internal fun moderationNeverAdjacentToReactions(shownInOrder: List<EncounterControlSlot>): Boolean =
    shownInOrder.zipWithNext().none { (a, b) ->
        val ga = encounterControlGroup(a)
        val gb = encounterControlGroup(b)
        (ga == EncounterControlGroup.REACTIONS && gb == EncounterControlGroup.MODERATION) ||
            (ga == EncounterControlGroup.MODERATION && gb == EncounterControlGroup.REACTIONS)
    }

/** Pure invariant (V1.9.95): the blessing never stands directly next to a moderation control (a slip must not hit the transmission). */
internal fun blessingNeverAdjacentToModeration(shownInOrder: List<EncounterControlSlot>): Boolean =
    shownInOrder.zipWithNext().none { (a, b) ->
        (a == EncounterControlSlot.Blessing && encounterControlGroup(b) == EncounterControlGroup.MODERATION) ||
            (b == EncounterControlSlot.Blessing && encounterControlGroup(a) == EncounterControlGroup.MODERATION)
    }

/** Pure invariant (V1.9.96): the bell never stands directly next to a moderation control (a slip must not hit the transmission). */
internal fun bellNeverAdjacentToModeration(shownInOrder: List<EncounterControlSlot>): Boolean =
    shownInOrder.zipWithNext().none { (a, b) ->
        (a == EncounterControlSlot.Bell && encounterControlGroup(b) == EncounterControlGroup.MODERATION) ||
            (b == EncounterControlSlot.Bell && encounterControlGroup(a) == EncounterControlGroup.MODERATION)
    }

/**
 * Pure invariant (V1.9.184, replaces `recordingNeverAdjacentToExitOrModeration`): the recording and the transmission never stand directly
 * next to a reaction or an exit control IN THE SAME ZONE (a slip must not raise a reaction, end the visit or close the doors). The end zone
 * is a zone of its own, separated from the centre by a separator and room, so "transmission, then the end zone" is the intended layout.
 */
internal fun moderationToolsNeverAdjacentToExitOrReactions(shownInOrder: List<EncounterControlSlot>): Boolean =
    shownInOrder.zipWithNext().none { (a, b) ->
        fun tool(slot: EncounterControlSlot) = slot == EncounterControlSlot.Recording || slot == EncounterControlSlot.Broadcast

        fun risky(slot: EncounterControlSlot) =
            encounterControlGroup(slot).let { it == EncounterControlGroup.REACTIONS || it == EncounterControlGroup.EXIT }
        encounterControlZone(a) == encounterControlZone(b) && ((tool(a) && risky(b)) || (tool(b) && risky(a)))
    }

/**
 * V1.9.74 (R58 named exception c): the ONLY icon-only factory of the encounter room. At least 44 x 44 px through theme.css. [label]
 * becomes `title`, `aria-label` and `data-label`; the button never shows a word, in no width. V1.9.84: the reactions too (hand and events).
 */
internal fun Container.encounterControlButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
): Button {
    val button = newIconOnlyActionButton(kind, label, style)
    button.setAttribute("data-label", resolvedAttributeText(label))
    add(button)
    return button
}

/**
 * V1.9.67 -- the bar under the stage (stage mode); V1.9.74: icon bar. Grouped controls, targets of at least 44 px, SYMBOLS ONLY, the
 * reactions (hand and events) included (V1.9.84). It never wraps and never scrolls: what does not fit moves into the
 * "Mehr" sheet (`ControlBarOverflow`). V1.9.184: three zones (`RoomControlBar`) -- the gear alone at the start, the groups in the centre,
 * "Türen schließen" and "Verlassen" in the end zone, "Verlassen" always last.
 *
 * This class only owns the structure; the controls are built by the owner (`encounterControlButton`), so the guideline rules
 * (R57 icons, R58 icon-only) keep applying to each button.
 */
internal class EncounterControlBar(
    parent: Container,
    /** V1.9.95/V1.9.96: `true` only for the pulpit of a church-service room (blessing or bell); otherwise the liturgy group is not even built. */
    liturgy: Boolean,
    /** V1.9.143: `true` only for PULPIT/STEWARD of a room whose recording option is on; otherwise the recording group is not even built. */
    recording: Boolean = false,
) {
    private val bar = RoomControlBar(parent, "lapis-room-bar--encounter lapis-encounter-controls")
    val root: Div get() = bar.root
    private val groups: Map<EncounterControlGroup, Div> =
        EncounterControlGroup.entries
            .filter { (it != EncounterControlGroup.LITURGY || liturgy) && (it != EncounterControlGroup.RECORDING || recording) }
            .associateWith { group ->
                val className = "lapis-encounter-control-group lapis-encounter-control-group--${group.name.lowercase()}"
                bar.zone(encounterControlZone(group)).div(className = className).also {
                    labelOf(group)?.let { label ->
                        it.setAttribute("role", "group")
                        it.setAttribute("aria-label", label)
                    }
                }
            }

    /** The container of [group]; add the group's buttons there. Throws for [EncounterControlGroup.LITURGY] when the bar was built without it (on purpose). */
    fun group(group: EncounterControlGroup): Div = groups.getValue(group)

    private fun labelOf(group: EncounterControlGroup): String? =
        when (group) {
            EncounterControlGroup.MODERATION -> gettext("Moderation")
            EncounterControlGroup.EXIT -> gettext("Ausgang")
            EncounterControlGroup.REACTIONS -> gettext("Reaktionen")
            EncounterControlGroup.RECORDING -> gettext("Aufzeichnung")
            EncounterControlGroup.DEVICES -> gettext("Geräte")
            else -> null
        }

    /** The wrapper of a group is hidden by a CSS class while all its controls sit in the sheet (a wrapper that stays would take a gap). */
    fun setGroupSpent(
        group: EncounterControlGroup,
        spent: Boolean,
    ) {
        val wrapper = groups.getValue(group)
        if (spent) wrapper.addCssClass(SPENT) else wrapper.removeCssClass(SPENT)
    }

    companion object {
        const val SPENT = "lapis-encounter-control-group--spent"
    }
}
