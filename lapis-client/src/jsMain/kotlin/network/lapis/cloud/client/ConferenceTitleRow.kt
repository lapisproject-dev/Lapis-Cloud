package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.h2
import io.kvision.panel.HPanel

/** Class of the call's heading row; the full-screen rule in `theme.css` hides everything in it except the heading (V1.9.108). */
internal const val CONFERENCE_TITLE_ROW_CLASS = "lapis-conference-title-row"

/** Class of the room title heading: in full screen it stays in the accessibility tree but is not painted. */
internal const val CONFERENCE_TITLE_CLASS = "lapis-conference-title"

/** Class of the "Bearbeiten" button of the heading row: in full screen it is `display:none`, so also out of the tab order. */
internal const val CONFERENCE_TITLE_EDIT_CLASS = "lapis-conference-title-edit"

/**
 * V1.9.108 -- the VIEW state of the call's heading row (title, and for the moderation the "Bearbeiten" button). Extracted from
 * `enterCall` so the real structure can be tested against the real stylesheet: in full screen the row paints nothing -- the person
 * is looking at the faces, not at a title bar -- while the `h2` stays in the accessibility tree, so the landmark keeps its name.
 */
internal fun HPanel.renderConferenceTitleView(
    roomTitle: String,
    canModerate: Boolean,
    onEdit: () -> Unit,
) {
    removeAll()
    h2(roomTitle) { addCssClasses("h5 $CONFERENCE_TITLE_CLASS") }
    if (canModerate) {
        val editButton = actionButton(ActionIcon.EDIT, io.kvision.i18n.tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
        editButton.addCssClasses("btn-sm $CONFERENCE_TITLE_EDIT_CLASS")
        editButton.onClick { onEdit() }
    }
}
