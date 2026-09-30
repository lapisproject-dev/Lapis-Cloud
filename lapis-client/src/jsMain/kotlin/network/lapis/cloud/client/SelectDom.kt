package network.lapis.cloud.client

import io.kvision.core.Widget
import kotlinx.browser.window
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/*
 * The few raw-DOM reads [SearchableSelect] needs, kept apart from its widget code: they only READ (no attribute writes), and
 * `ClientLateHookRatchetTest` scans for a `setAttribute(` near a raw element source, so the two must not share a file section.
 */

/** The current text of the `<input>` behind this KVision widget, or `null` while it is not in the document. */
internal fun Widget.domInputText(): String? = (getElement() as? HTMLInputElement)?.value

/**
 * Fires a real, bubbling DOM `change` event on the `<input>`: a choice made from the dropdown is not typing, so the browser raises
 * nothing by itself -- but the form grammar (`LapisField.onInput`) and `crossFieldRule(watch = ...)` listen for exactly that event.
 */
internal fun Widget.dispatchDomChange() {
    getElement()?.dispatchEvent(Event("change", EventInit(bubbles = true)))
}

/** Selects the whole text of the `<input>` behind this widget (focus convenience: typing replaces the shown name). */
internal fun Widget.selectDomInputText() {
    (getElement() as? HTMLInputElement)?.select()
}

/**
 * `true` when an overlay of [needed] pixels does NOT fit below this widget but there is more room above it -- the dropdown then
 * opens upwards. Without an element (not mounted) there is nothing to measure: open downwards.
 */
internal fun Widget.opensUpwards(needed: Int): Boolean {
    val element = getElement() ?: return false
    val rect = element.getBoundingClientRect()
    val below = window.innerHeight - rect.bottom
    return below < needed && rect.top > below
}

/** Scrolls this widget into view inside its scroll container by the smallest distance (no animation). */
internal fun Widget.scrollNearestIntoView() {
    val element = getElement() ?: return
    element.asDynamic().scrollIntoView(js("({ block: 'nearest', behavior: 'auto' })"))
}
