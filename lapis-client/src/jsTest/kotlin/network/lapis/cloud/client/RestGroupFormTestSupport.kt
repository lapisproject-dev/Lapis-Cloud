package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/*
 * Shared helpers of the V1.9.49 DOM tests (collapsed create forms of the remaining groups). Plain functions on top of `FormTestSupport.kt`.
 */

/** True while the form host [formId] holds a built form (it exists, empty, while the form is closed). */
internal fun HTMLElement.hostOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

/** The create buttons (those with `aria-controls`) hanging in a title row or the page header, by label. */
internal fun HTMLElement.createButtonLabels(): List<String> =
    allOf(".lapis-page-action button[aria-controls]").map {
        it.textContent.orEmpty().trim()
    }

/** True when the element takes part in the layout (a hidden slot, `display:none`, has no client rects). */
internal fun HTMLElement.isRendered(): Boolean = (asDynamic().getClientRects().length as Int) > 0

/** The create button of [formId] is shown to the person (its slot is not hidden). */
internal fun HTMLElement.createButtonShown(formId: String): Boolean =
    (querySelector("button[aria-controls='$formId']") as? HTMLElement)?.isRendered() == true

internal fun pressEscape(target: HTMLElement) {
    target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
}

/** Answers the "Eingaben verwerfen?" dialog with the button named [label] ("Weiter bearbeiten" or "Verwerfen"). */
internal suspend fun answerDiscardDialog(label: String) {
    awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
    lastOpenModal().buttonNamed(label).click()
    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
}

internal const val KV_MARKER = "###KvI18nS###"

/** The create button of [formId] (the one with `aria-controls`). */
internal fun HTMLElement.createButtonOf(formId: String): HTMLElement = createFormButton(this, formId)
