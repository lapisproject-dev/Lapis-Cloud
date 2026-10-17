package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.allowedFor
import network.lapis.cloud.shared.domain.defaultRoomLayout
import org.w3c.dom.HTMLInputElement

/** What the form pre-fills when a template built around tables is chosen: `tables x seats`; `null` = the template needs no tables. Pure. */
internal fun layoutTablePreset(layout: EncounterRoomLayout): Pair<Int, Int>? =
    when (layout) {
        EncounterRoomLayout.FOYER -> 6 to 4
        EncounterRoomLayout.SMALL_GROUP -> 3 to 8
        EncounterRoomLayout.NAVE, EncounterRoomLayout.HALL -> null
    }

/** Shown on every tile while no room kind is chosen yet (the create form has no preselection). */
private fun chooseKindFirst(): String = gettext("Wählen Sie zuerst die Raumart.")

/**
 * V1.9.152 (stage 2c) -- the template choice of the room form: a `<fieldset>` with the legend "Raumvorlage" and one NATIVE radio per template
 * (arrow keys, space and the radio-group semantics come from the browser; the radio itself is visually hidden and the tile is its visible face with
 * a focus ring of its own). A tile shows the very scene of the room ([renderLayoutScene], scaled down), the name, one line of description and, when
 * the template does not fit the chosen room kind, WHY (text, never colour alone).
 *
 * The rule "which template fits which kind" is the shared [allowedFor]; this class only mirrors it into `disabled` states. State changes the
 * person did not make themselves (the template was reset because the kind changed, the tables were switched on) are said in a `role="status"` line.
 * The class holds no person and no room data -- it knows the choice and the kind only.
 */
internal class EncounterRoomLayoutChoice(
    parent: Container,
    initial: EncounterRoomLayout?,
    private val locked: Boolean,
) {
    private class Tile(
        val layout: EncounterRoomLayout,
        val input: Tag,
        val reason: Span,
    )

    val root: Tag = parent.tag(TAG.FIELDSET, className = "lapis-encounter-layout-fieldset")
    private val status: Div
    private val tiles: List<Tile>

    /** The chosen template; `null` while nothing is chosen (create form before the room kind is chosen). */
    var selected: EncounterRoomLayout? = initial
        private set

    /** Called after a choice the PERSON made (a click, an arrow key); never after [select] or [adaptTo]. */
    var onChosen: (EncounterRoomLayout) -> Unit = {}

    init {
        root.tag(TAG.LEGEND, content = layoutLegend(), className = "lapis-encounter-layout-legend")
        val choices = root.div(className = "lapis-encounter-layout-choices")
        tiles =
            EncounterRoomLayout.entries.map { layout ->
                val key = layoutCssKey(layout)
                val label = choices.tag(TAG.LABEL, className = "lapis-encounter-layout-tile lapis-encounter-layout-tile--$key")
                val input = label.tag(TAG.INPUT, className = "lapis-encounter-layout-radio")
                input.setAttribute("type", "radio")
                input.setAttribute("name", GROUP_NAME + groupCounter)
                input.setAttribute("value", layout.name)
                val body = label.div(className = "lapis-encounter-layout-body")
                val preview = body.div(className = "lapis-encounter-layout-preview")
                renderLayoutScene(parent = preview, layout = layout, scale = PREVIEW_SCALE, withRows = true)
                val nameId = "lapis-encounter-layout-name-$groupCounter-$key"
                val descId = "lapis-encounter-layout-desc-$groupCounter-$key"
                val reasonId = "lapis-encounter-layout-reason-$groupCounter-$key"
                body.tag(TAG.SPAN, content = layoutName(layout), className = "lapis-encounter-layout-name").setAttribute("id", nameId)
                body
                    .tag(
                        TAG.SPAN,
                        content = layoutDescription(layout),
                        className = "small text-muted lapis-encounter-layout-desc",
                    ).setAttribute("id", descId)
                val reason = Span(className = "small lapis-encounter-layout-reason")
                reason.setAttribute("id", reasonId)
                body.add(reason)
                input.setAttribute("aria-labelledby", nameId)
                input.setAttribute("aria-describedby", "$descId $reasonId")
                input.onEvent {
                    change = {
                        if (selected != layout) {
                            selected = layout
                            onChosen(layout)
                        }
                    }
                }
                Tile(layout = layout, input = input, reason = reason)
            }
        groupCounter++
        if (locked) {
            root.div(layoutFrozenNote(), className = "text-muted small lapis-encounter-layout-frozen")
        }
        status = root.div(className = "small lapis-encounter-layout-status")
        status.setAttribute("role", "status")
        tiles.forEach { markChecked(tile = it, checked = it.layout == selected) }
        restrictTo(profile = null, known = false)
    }

    /**
     * Enables exactly the templates that fit [profile] and explains the others in text. [profile] `null` = no kind chosen yet: nothing can be chosen.
     * A locked form (open room) keeps every tile disabled; the stored choice stays visible.
     */
    fun restrictTo(profile: EncounterProfile?) = restrictTo(profile = profile, known = true)

    private fun restrictTo(
        profile: EncounterProfile?,
        known: Boolean,
    ) {
        tiles.forEach { tile ->
            val fits = profile?.let { tile.layout.allowedFor(it) } ?: false
            val disabled = locked || !fits
            if (disabled) tile.input.setAttribute("disabled", "disabled") else tile.input.removeAttribute("disabled")
            tile.reason.content =
                when {
                    locked -> ""
                    !known || profile == null -> chooseKindFirst()
                    !fits -> layoutLockedReason(tile.layout)
                    else -> ""
                }
        }
    }

    /**
     * After the room kind changed to [profile]: nothing chosen yet gets the default of the kind quietly; a choice that no longer fits is replaced by
     * that default and the replacement is said in the status line. A choice that fits stays.
     */
    fun adaptTo(profile: EncounterProfile) {
        val current = selected
        val stillFits = current?.allowedFor(profile) ?: false
        if (stillFits) return
        val fallback = defaultRoomLayout(profile)
        select(fallback)
        if (current != null) say(layoutResetNotice(fallback))
    }

    /** Chooses [layout] by program (no [onChosen]). */
    fun select(layout: EncounterRoomLayout) {
        selected = layout
        tiles.forEach { markChecked(tile = it, checked = it.layout == layout) }
    }

    /** Puts [sentence] into the polite status line. */
    fun say(sentence: String) {
        status.content = sentence
    }

    private fun markChecked(
        tile: Tile,
        checked: Boolean,
    ) {
        if (checked) tile.input.setAttribute("checked", "checked") else tile.input.removeAttribute("checked")
        (tile.input.getElement() as? HTMLInputElement)?.checked = checked
    }

    private companion object {
        const val GROUP_NAME = "lapis-encounter-layout-"
        const val PREVIEW_SCALE = 0.4
        var groupCounter = 0
    }
}
