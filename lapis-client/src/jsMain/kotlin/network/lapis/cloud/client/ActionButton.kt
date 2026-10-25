package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Link
import io.kvision.html.icon
import io.kvision.html.span
import org.w3c.dom.HTMLElement

/** Icon classes every [actionButton] icon carries: fixed width (aligned labels) + the spacing class from theme.css. */
private const val ACTION_ICON_EXTRA_CLASSES = "fa-fw lapis-action-icon"

/** The full class string of an [actionButton] icon (for a button whose icon changes with its state). */
internal fun actionIconClasses(kind: ActionIcon): String = "${kind.css} $ACTION_ICON_EXTRA_CLASSES"

/**
 * How an action button shows its label (V1.9.208, guideline R58 mobile extension).
 *
 * - [ALWAYS]: icon and visible text at every width (the default).
 * - [RESPONSIVE]: icon + text from 576px up, the icon alone below it -- the text stays in the DOM as `aria-hidden` and the button carries
 *   `title` + `aria-label`. Only for groups of two or more actions on a card, row or header ([actionGroup]); never in a dense table.
 * - [ICON_ONLY]: icon at every width (dense table rows only, R58).
 */
enum class ActionLabelMode { ALWAYS, RESPONSIVE, ICON_ONLY }

/**
 * Standard button with the icon of its verb (V1.9.43, guideline R57).
 *
 * - Visible text stays [label] (unchanged, so text-based test helpers and screen readers see the same name); the icon is decorative.
 * - [iconOnly] is for dense table rows only (two or more actions per row, guideline R58): [label] then becomes `title` AND
 *   `aria-label` through [tableActionTooltip] -- the one path that keeps the `###KvI18nS###` marker out of the DOM.
 * - [label] must not be blank, also for [iconOnly]: an icon without a name has no accessible name.
 * - [small] adds `btn-sm`. Never use it inside a [lapisToolbar] (tripwire R56).
 * - [labelMode] (V1.9.208) wins over [iconOnly]; [ActionLabelMode.RESPONSIVE] adds `lapis-action-btn` (44px touch target, no `btn-sm`).
 * - [accessibleName] (V1.9.208): a longer accessible name for a button with a SHORT visible label ("Anmelden" -> "Zur Veranstaltung anmelden");
 *   it must contain the visible label (WCAG 2.5.3, label in name). Static text only.
 *
 * [label] must be a static `tr(...)` / `gettext(...)` text, never data from the server.
 */
fun Container.actionButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
    iconOnly: Boolean = false,
    small: Boolean = false,
    labelMode: ActionLabelMode = if (iconOnly) ActionLabelMode.ICON_ONLY else ActionLabelMode.ALWAYS,
    accessibleName: String? = null,
    init: Button.() -> Unit = {},
): Button {
    val result = newActionButton(kind, label, style, iconOnly, small, labelMode, accessibleName)
    add(result)
    result.init()
    return result
}

/**
 * The same button, not yet attached -- for the places that hand a button to something else (`Modal.addButton`, a footer slot).
 *
 * KVision renders `Button(icon = ...)` as `<i class=...>` WITHOUT `aria-hidden`. The icon is decoration (FA 7 already excludes its glyph
 * from the accessible name through `content: var(--fa)/""`), so an insert hook marks it hidden explicitly. The hook is registered
 * BEFORE the widget is added, so the vnode key is stable from the first render (see KvisionLifecycle.kt).
 */
fun newActionButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
    iconOnly: Boolean = false,
    small: Boolean = false,
    labelMode: ActionLabelMode = if (iconOnly) ActionLabelMode.ICON_ONLY else ActionLabelMode.ALWAYS,
    accessibleName: String? = null,
    init: Button.() -> Unit = {},
): Button {
    val result =
        buildActionButton(
            kind,
            label,
            style,
            labelMode,
            small = small || labelMode == ActionLabelMode.ICON_ONLY,
            accessibleName = accessibleName,
        )
    result.init()
    return result
}

/**
 * V1.9.66 (R58 exception, named): icon-only button WITHOUT `btn-sm` for exactly two places -- `lapisChatComposer` (ChatComposer.kt)
 * and `conferenceControlButton` (ConferenceControlBar.kt). [label] becomes `title` + `aria-label` ([tableActionTooltip] path).
 * Tripwire R58 allows calls only from those two functions.
 */
internal fun newIconOnlyActionButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle,
): Button = buildActionButton(kind, label, style, ActionLabelMode.ICON_ONLY, small = false)

private fun buildActionButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle,
    labelMode: ActionLabelMode,
    small: Boolean,
    accessibleName: String? = null,
): Button {
    require(label.isNotBlank()) { "actionButton needs a label (accessible name), also when iconOnly" }
    require(accessibleName == null || accessibleName.isNotBlank()) { "accessibleName must not be blank" }
    val iconOnly = labelMode == ActionLabelMode.ICON_ONLY
    val responsive = labelMode == ActionLabelMode.RESPONSIVE
    val result = Button(text = if (iconOnly || responsive) "" else label, icon = actionIconClasses(kind), style = style)
    result.addAfterInsertHook { vnode -> (vnode.elm as? HTMLElement)?.querySelector("i")?.setAttribute("aria-hidden", "true") }
    if (responsive) {
        // V1.9.208: the text is a sibling span that Bootstrap hides below 576px (`d-none d-sm-inline`); the name lives in title + aria-label.
        require(!small) { "a RESPONSIVE action button is never btn-sm (44px touch target)" }
        result.addCssClass("lapis-action-btn")
        result.tableActionTooltip(label)
        result.span(label, className = "lapis-action-label d-none d-sm-inline") { setAttribute("aria-hidden", "true") }
    } else if (iconOnly) {
        result.tableActionTooltip(label)
    } else if (accessibleName != null) {
        result.tableActionTooltip(accessibleName)
    }
    if (small) result.addCssClass("btn-sm")
    return result
}

/**
 * V1.9.208: the one container for the actions of a card, a row or a header -- a wrapping flex row with a 0.5rem gap. Not a toolbar: it holds
 * buttons only, never form fields (those stay in [lapisToolbar]). At most one [ActionLabelMode.ALWAYS] action per group (guideline R58).
 */
fun Container.actionGroup(init: Div.() -> Unit = {}): Div {
    val group = Div(className = "lapis-action-group")
    add(group)
    group.init()
    return group
}

/** Icon-only button for a dense table action column (R58): `btn-sm`, [tooltip] as `title` + `aria-label`. */
fun Container.tableActionButton(
    kind: ActionIcon,
    tooltip: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
): Button = actionButton(kind, tooltip, style = style, iconOnly = true)

/**
 * A link with the icon of its verb (V1.9.51, guideline R57) -- the link counterpart of [actionButton], for a verb that navigates to a
 * file instead of running an action (the download of a recording). The icon is decoration (`aria-hidden`), [label] stays the
 * accessible name. A `_blank` [target] gets `rel="noopener"`.
 *
 * [label] must be a static `tr(...)` text, never data from the server; [url] is the existing media URL of the caller.
 */
fun Container.actionLink(
    kind: ActionIcon,
    label: String,
    url: String,
    target: String? = null,
): Link {
    require(label.isNotBlank()) { "actionLink needs a label (accessible name)" }
    val result = Link(label = "", url = url)
    if (target != null) {
        result.target = target
        if (target == "_blank") result.setAttribute("rel", "noopener")
    }
    result.icon("${kind.css} $ACTION_ICON_EXTRA_CLASSES") { setAttribute("aria-hidden", "true") }
    result.span(label)
    add(result)
    return result
}
