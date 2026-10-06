package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.div
import network.lapis.cloud.client.lapisToolbar

/**
 * The groups of the control bar of the encounter room, in DOM order. A divider (CSS only) separates two non-empty groups.
 *
 * - [REACTIONS]: the configured reactions of the room (hand always first, then the allowed events).
 * - [DEVICES]: microphone and camera -- only an office holder's session ever fills this group.
 * - [PANELS]: the side panel (chat, people, transmission).
 * - [VIEW]: scene on/off and the full screen.
 * - [MODERATION]: transmission and "Türen schließen" (people who moderate); never next to the reactions, so a slip does not close the doors.
 */
internal enum class EncounterControlGroup { REACTIONS, DEVICES, PANELS, VIEW, MODERATION }

/**
 * V1.9.67 -- the bar under the stage (stage mode). Same pattern as the conference bar of V1.9.66: grouped controls, targets of at least
 * 44 px, symbol and text from 768 px on and symbol only below (the text stays in the DOM as the accessible name, theme.css hides it
 * visually). It never wraps into a second line on a phone: the groups scroll sideways inside the bar instead.
 *
 * This class only owns the structure; every control is built by the owner with `actionButton`, so the guideline rules (R57 icons,
 * R58 icon-only) keep applying to each button.
 */
internal class EncounterControlBar(
    parent: Container,
) {
    val root: Div = parent.lapisToolbar { addCssClass("lapis-encounter-controls") }
    private val groups: Map<EncounterControlGroup, Div> =
        EncounterControlGroup.entries.associateWith { group ->
            root.div(className = "lapis-encounter-control-group lapis-encounter-control-group--${group.name.lowercase()}")
        }

    /** The container of [group]; add the group's buttons there. */
    fun group(group: EncounterControlGroup): Div = groups.getValue(group)
}
