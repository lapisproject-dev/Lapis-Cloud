package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import org.w3c.dom.HTMLElement

/** Icon classes every [actionButton] icon carries: fixed width (aligned labels) + the spacing class from theme.css. */
private const val ACTION_ICON_EXTRA_CLASSES = "fa-fw lapis-action-icon"

/**
 * Standard button with the icon of its verb (V1.9.43, guideline R57).
 *
 * - Visible text stays [label] (unchanged, so text-based test helpers and screen readers see the same name); the icon is decorative.
 * - [iconOnly] is for dense table rows only (two or more actions per row, guideline R58): [label] then becomes `title` AND
 *   `aria-label` through [tableActionTooltip] -- the one path that keeps the `###KvI18nS###` marker out of the DOM.
 * - [label] must not be blank, also for [iconOnly]: an icon without a name has no accessible name.
 * - [small] adds `btn-sm`. Never use it inside a [lapisToolbar] (tripwire R56).
 *
 * [label] must be a static `tr(...)` / `gettext(...)` text, never data from the server.
 */
fun Container.actionButton(
    kind: ActionIcon,
    label: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
    iconOnly: Boolean = false,
    small: Boolean = false,
    init: Button.() -> Unit = {},
): Button {
    val result = newActionButton(kind, label, style, iconOnly, small)
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
    init: Button.() -> Unit = {},
): Button {
    require(label.isNotBlank()) { "actionButton needs a label (accessible name), also when iconOnly" }
    val result = Button(text = if (iconOnly) "" else label, icon = "${kind.css} $ACTION_ICON_EXTRA_CLASSES", style = style)
    result.addAfterInsertHook { vnode -> (vnode.elm as? HTMLElement)?.querySelector("i")?.setAttribute("aria-hidden", "true") }
    if (iconOnly) result.tableActionTooltip(label)
    if (small || iconOnly) result.addCssClass("btn-sm")
    result.init()
    return result
}

/** Icon-only button for a dense table action column (R58): `btn-sm`, [tooltip] as `title` + `aria-label`. */
fun Container.tableActionButton(
    kind: ActionIcon,
    tooltip: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
): Button = actionButton(kind, tooltip, style = style, iconOnly = true)
