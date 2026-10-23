package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel

/**
 * V1.9.182 -- progressive disclosure: a link-style button that shows or hides [build]'s content, closed by default.
 *
 * The state is `aria-expanded` on the button (`aria-controls` points at the body); the label stays the same when the state flips (a
 * disclosure, not two actions). The body is built at once, but KVision does not render a hidden widget: the content is in the DOM only while the disclosure is open
 * (a test opens it first). [label] must be a static `tr(...)` constant. [bodyId] names the body element for `aria-controls`.
 *
 * @return the button, so a caller can describe it further (`aria-describedby`).
 */
internal fun SimplePanel.disclosure(
    label: String,
    bodyId: String,
    initiallyOpen: Boolean = false,
    build: SimplePanel.() -> Unit,
): Button {
    val toggle = button(label, style = ButtonStyle.LINK)
    toggle.addCssClass("px-0")
    val body = vPanel(spacing = 4)
    body.id = bodyId
    body.build()
    if (!initiallyOpen) body.hide()
    toggle.setAttribute("aria-expanded", initiallyOpen.toString())
    toggle.setAttribute("aria-controls", bodyId)
    toggle.onClick {
        val show = !body.visible
        if (show) body.show() else body.hide()
        toggle.setAttribute("aria-expanded", show.toString())
    }
    return toggle
}
