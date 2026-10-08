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
 * The groups of the control bar of the encounter room, in DOM order. A divider (CSS only) separates two non-empty groups.
 *
 * - [REACTIONS]: the configured reactions of the room (hand always first, then the allowed events) -- the only controls with a word.
 * - [DEVICES]: microphone and camera -- only an office holder's session fills this group; since V1.9.80 also the table microphone and
 *   "Kanzel lauter" of a congregation person who sits at a table.
 * - [PANELS]: the side panel (chat) and "Mehr" (the sheet with what did not fit).
 * - [VIEW]: scene on/off and the full screen.
 * - [MODERATION]: the transmission (people who moderate); never next to the reactions, so a slip does not trigger a reaction.
 * - [EXIT] (V1.9.74): "Türen schließen" (people who moderate) and "Verlassen", pushed to the end of the bar, 12 px apart.
 */
internal enum class EncounterControlGroup { REACTIONS, DEVICES, PANELS, VIEW, MODERATION, EXIT }

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
        EncounterControlSlot.Mic, EncounterControlSlot.Camera, EncounterControlSlot.TableMic, EncounterControlSlot.PulpitLouder ->
            EncounterControlGroup.DEVICES
        EncounterControlSlot.Chat, EncounterControlSlot.More -> EncounterControlGroup.PANELS
        EncounterControlSlot.Scene, EncounterControlSlot.Fullscreen -> EncounterControlGroup.VIEW
        EncounterControlSlot.Broadcast -> EncounterControlGroup.MODERATION
        EncounterControlSlot.CloseDoors, EncounterControlSlot.Leave -> EncounterControlGroup.EXIT
    }

/**
 * The order in which controls move into the "Mehr" sheet, the first one first: full screen, scene, transmission, then the event
 * reactions (the last of the canonical order first), then "Türen schließen" and last of all the chat. Never moved: hand, microphone,
 * camera, "Mehr" and "Verlassen". Built at run time because the event reactions differ per room profile.
 */
internal fun encounterOverflowOrder(allowed: Collection<EncounterReactionOption>): List<EncounterControlSlot> =
    buildList {
        add(EncounterControlSlot.Fullscreen)
        add(EncounterControlSlot.Scene)
        add(EncounterControlSlot.Broadcast)
        EncounterReactionOption.entries
            .filter { it != EncounterReactionOption.ALWAYS_ON && it in allowed }
            .reversed()
            .forEach { add(EncounterControlSlot.Reaction(it)) }
        add(EncounterControlSlot.CloseDoors)
        add(EncounterControlSlot.Chat)
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

/**
 * V1.9.74 (R58 named exception c): the ONLY icon-only factory of the encounter room. At least 44 x 44 px through theme.css. [label]
 * becomes `title`, `aria-label` and `data-label`; the button never shows a word, in no width. The reactions keep their word and are
 * built with `actionButton`.
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
 * V1.9.67 -- the bar under the stage (stage mode); V1.9.74: icon bar. Grouped controls, targets of at least 44 px, SYMBOLS ONLY except
 * the reactions (hand and events), which keep a word from 768 px on. It never wraps and never scrolls: what does not fit moves into the
 * "Mehr" sheet (`ControlBarOverflow`). "Türen schließen" and "Verlassen" sit in their own exit group at the far end, "Verlassen" always
 * last.
 *
 * This class only owns the structure; the controls are built by the owner (`encounterControlButton` for the symbols, `actionButton`
 * for the reactions), so the guideline rules (R57 icons, R58 icon-only) keep applying to each button.
 */
internal class EncounterControlBar(
    parent: Container,
) {
    val root: Div = parent.lapisToolbar { addCssClass("lapis-encounter-controls") }
    private val groups: Map<EncounterControlGroup, Div> =
        EncounterControlGroup.entries.associateWith { group ->
            root.div(className = "lapis-encounter-control-group lapis-encounter-control-group--${group.name.lowercase()}").also {
                labelOf(group)?.let { label ->
                    it.setAttribute("role", "group")
                    it.setAttribute("aria-label", label)
                }
            }
        }

    /** The container of [group]; add the group's buttons there. */
    fun group(group: EncounterControlGroup): Div = groups.getValue(group)

    private fun labelOf(group: EncounterControlGroup): String? =
        when (group) {
            EncounterControlGroup.MODERATION -> gettext("Moderation")
            EncounterControlGroup.EXIT -> gettext("Ausgang")
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
