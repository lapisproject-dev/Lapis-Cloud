package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Shared helpers of the form-grammar DOM tests (V1.4.29 audit): drive a REAL mounted form the way a person does (type, blur, choose,
 * click) with a stubbed `window.fetch`, and read what went over the wire. Text is typed padded with spaces on purpose: the client
 * trims text, and a value that arrives untrimmed (or, for an empty optional field, as "" instead of null) is a defect.
 */

/** A test body run in a coroutine with modal transitions off and every modal closed before and after. */
internal fun formTest(block: suspend () -> Unit): Promise<Unit> =
    CoroutineScope(SupervisorJob()).promise {
        disableModalTransitions()
        // Leftovers of other test classes (a hidden modal, a live focus trap) must not be found as 'the last modal'.
        closeOpenModals(timeoutMs = 300)
        try {
            block()
        } finally {
            closeOpenModals(timeoutMs = 400)
            AppState.setSession(null)
        }
    }

/**
 * [withMountedRoot] that closes every open modal BEFORE the root is disposed: disposing the root tears the modal out of the
 * document while Bootstrap still holds its focus trap, which pulls `document.activeElement` onto a modal button in whichever test
 * runs next (see [closeOpenModals]).
 */
internal suspend inline fun <T> mountedForm(
    id: String,
    block: (Root, () -> HTMLElement) -> T,
): T =
    withMountedRoot(id) { root, element ->
        try {
            block(root, element)
        } finally {
            closeOpenModals(timeoutMs = 1000)
        }
    }

internal fun HTMLElement.allOf(selector: String): List<HTMLElement> =
    (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

internal fun HTMLElement.buttonNamed(text: String): HTMLElement =
    assertNotNull(allOf("button").firstOrNull { it.textContent?.trim() == text }, "no button '$text'")

internal fun lastOpenModal(): HTMLElement {
    val modals = document.querySelectorAll(".modal")
    return assertNotNull(modals.item(modals.length - 1) as? HTMLElement, "no modal")
}

/** The control of the field whose label starts with [label] (`for`-linked, the star suffix ignored); [nth] picks among equal labels. */
internal fun HTMLElement.controlOf(
    label: String,
    nth: Int = 0,
): HTMLElement {
    val labels =
        allOf("label").filter {
            it.textContent
                .orEmpty()
                .trim()
                .removeSuffix("*")
                .trim()
                .startsWith(label)
        }
    val target = assertNotNull(labels.getOrNull(nth), "no label '$label' #$nth")
    return assertNotNull(document.getElementById(target.getAttribute("for").orEmpty()) as? HTMLElement, "no control for '$label'")
}

/** Types [text] into the text control of [label] the way a person does: value, `input`, then leaving the field (`blur`). */
internal fun HTMLElement.typeInto(
    label: String,
    text: String,
    nth: Int = 0,
) {
    val control = controlOf(label, nth)
    when (control) {
        is HTMLInputElement -> control.value = text
        is HTMLTextAreaElement -> control.value = text
        else -> error("not a text control: $label")
    }
    control.dispatchEvent(Event("input"))
    control.dispatchEvent(Event("blur"))
}

internal fun HTMLElement.chooseIn(
    label: String,
    value: String,
    nth: Int = 0,
) {
    val select = controlOf(label, nth) as HTMLSelectElement
    select.value = value
    select.dispatchEvent(Event("change"))
}

internal fun HTMLElement.tick(
    label: String,
    nth: Int = 0,
) {
    val box = controlOf(label, nth) as HTMLInputElement
    if (!box.checked) box.click()
}

/** The requests that went to the service method [route] (see [routeOf]). */
internal fun List<RecordedRequest>.toRoute(route: String): List<RecordedRequest> = filter { it.isRpc && it.rpcRoute == route }

/** The parameters of the only request to [route]; fails when there was none or more than one. */
internal fun List<RecordedRequest>.singleCall(route: String): RecordedRequest {
    val matching = toRoute(route)
    assertEquals(1, matching.size, "expected exactly one request to $route, got ${matching.size} (all: ${map { it.rpcRoute }})")
    return matching.single()
}

/** The number of RPC requests so far, of any route: the NEGATIVE assertion is "no RPC at all", not "no RPC with n parameters". */
internal val List<RecordedRequest>.rpcCount: Int get() = count { it.isRpc }

/** The JSON of [value] as a Kilua RPC result would carry it. */
internal fun <T> jsonOf(
    serializer: KSerializer<T>,
    value: T,
): String = Json.encodeToString(serializer, value)

/** An RPC answer carrying [resultJson] for the request's own JSON-RPC id. */
internal fun RecordedRequest.answerWith(resultJson: String): StubResponse = rpcResult(json.id as Int, resultJson)

/** `true` when [element] is an error slot that is visibly showing an error. */
internal fun HTMLElement.shownErrors(): List<String> = allOf(".lapis-field-error--shown").map { it.textContent.orEmpty().trim() }

internal fun assertNoShownError(root: HTMLElement) =
    assertTrue(root.shownErrors().isEmpty(), "unexpected field errors: ${root.shownErrors()}")
