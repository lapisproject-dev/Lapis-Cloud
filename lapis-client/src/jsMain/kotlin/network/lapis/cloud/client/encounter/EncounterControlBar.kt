package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.lapisToolbar
import network.lapis.cloud.client.newIconOnlyActionButton
import network.lapis.cloud.client.resolvedAttributeText
import network.lapis.cloud.shared.domain.EncounterReactionOption

/**
 * The groups of the control bar of the encounter room, in DOM order (V1.9.90: own devices left, the middle, exit right). A divider (CSS
 * only) separates two non-empty groups.
 *
 * - [DEVICES]: first, at the left edge: microphone and camera -- only an office holder's session fills this group; since V1.9.80 also the table microphone and
 *   "Kanzel lauter" of a congregation person who sits at a table (the two sets never occur together).
 * - [REACTIONS]: the configured reactions of the room (hand always first, then the allowed events) -- the only controls with a word.
 * - [LITURGY] (V1.9.95/V1.9.96): the bell, then the blessing -- ONLY in a church-service room and ONLY for the pulpit; the group does not exist in the DOM otherwise.
 * - [PANELS]: the side panel (chat) and "Mehr" (the sheet with what did not fit).
 * - [VIEW]: scene on/off and the full screen.
 * - [MODERATION]: the transmission (people who moderate); never next to the reactions, so a slip does not trigger a reaction.
 * - [EXIT] (V1.9.74): "Türen schließen" (people who moderate) and "Verlassen", pushed to the end of the bar, 12 px apart.
 */
internal enum class EncounterControlGroup { DEVICES, REACTIONS, LITURGY, PANELS, VIEW, MODERATION, EXIT }

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

    /** V1.9.91: the device picker (gear) -- the LAST control of the devices group; never moved into the sheet. */
    data object AudioDevices : EncounterControlSlot

    /** V1.9.95: the pulpit's blessing (church profile only). */
    data object Blessing : EncounterControlSlot

    /** V1.9.96: the pulpit's bell (church profile only); stands before the blessing in the liturgy group. */
    data object Bell : EncounterControlSlot

    data object Chat : EncounterControlSlot

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
        EncounterControlSlot.AudioDevices,
        ->
            EncounterControlGroup.DEVICES
        EncounterControlSlot.Bell, EncounterControlSlot.Blessing -> EncounterControlGroup.LITURGY
        EncounterControlSlot.Chat, EncounterControlSlot.More -> EncounterControlGroup.PANELS
        EncounterControlSlot.Scene, EncounterControlSlot.Fullscreen -> EncounterControlGroup.VIEW
        EncounterControlSlot.Broadcast -> EncounterControlGroup.MODERATION
        EncounterControlSlot.CloseDoors, EncounterControlSlot.Leave -> EncounterControlGroup.EXIT
    }

/**
 * The order in which controls move into the "Mehr" sheet, the first one first: full screen, scene, transmission, then the event
 * reactions (the last of the canonical order first), then "Türen schließen", the bell (V1.9.96, [bell] only), the blessing (V1.9.95, [blessing] only: it stays in the bar as long as possible) and last of all the chat. Never moved: hand (the only reaction with a state; a hand-raise must never need a second tap), microphone,
 * camera, the device picker (V1.9.91), "Mehr" and "Verlassen". Built at run time because the event reactions differ per room profile.
 */
internal fun encounterOverflowOrder(
    allowed: Collection<EncounterReactionOption>,
    blessing: Boolean = false,
    bell: Boolean = false,
): List<EncounterControlSlot> =
    buildList {
        add(EncounterControlSlot.Fullscreen)
        add(EncounterControlSlot.Scene)
        add(EncounterControlSlot.Broadcast)
        EncounterReactionOption.entries
            .filter { it != EncounterReactionOption.ALWAYS_ON && it in allowed }
            .reversed()
            .forEach { add(EncounterControlSlot.Reaction(it)) }
        add(EncounterControlSlot.CloseDoors)
        if (bell) add(EncounterControlSlot.Bell)
        if (blessing) add(EncounterControlSlot.Blessing)
        add(EncounterControlSlot.Chat)
    }

/** Pure invariant (V1.9.90): every device control stands before every control of another group. */
internal fun devicesAreFirst(shownInOrder: List<EncounterControlSlot>): Boolean {
    val groups = shownInOrder.map { encounterControlGroup(it) }
    val lastDevice = groups.indexOfLast { it == EncounterControlGroup.DEVICES }
    return groups.take(lastDevice + 1).all { it == EncounterControlGroup.DEVICES }
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
 * "Mehr" sheet (`ControlBarOverflow`). "Türen schließen" and "Verlassen" sit in their own exit group at the far end, "Verlassen" always
 * last.
 *
 * This class only owns the structure; the controls are built by the owner (`encounterControlButton`), so the guideline rules
 * (R57 icons, R58 icon-only) keep applying to each button.
 */
internal class EncounterControlBar(
    parent: Container,
    /** V1.9.95/V1.9.96: `true` only for the pulpit of a church-service room (blessing or bell); otherwise the liturgy group is not even built. */
    liturgy: Boolean,
) {
    val root: Div = parent.lapisToolbar { addCssClass("lapis-encounter-controls") }
    private val groups: Map<EncounterControlGroup, Div> =
        EncounterControlGroup.entries.filter { it != EncounterControlGroup.LITURGY || liturgy }.associateWith { group ->
            root.div(className = "lapis-encounter-control-group lapis-encounter-control-group--${group.name.lowercase()}").also {
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
