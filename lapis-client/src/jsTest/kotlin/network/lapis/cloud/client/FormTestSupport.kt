package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
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
        // Nor may a coroutine another test left running in AppScope ask THIS test's stub (see [settleAppScope]).
        cancelAppScopeWork()
        try {
            block()
        } finally {
            closeOpenModals(timeoutMs = 400)
            AppState.setSession(null)
        }
    }

/**
 * [withMountedRoot] that lets the screen's background work finish against the test's own stub ([settleAppScope]) and then closes
 * every open modal BEFORE the root is disposed: disposing the root tears the modal out of the document while Bootstrap still holds
 * its focus trap, which pulls `document.activeElement` onto a modal button in whichever test runs next (see [closeOpenModals]).
 */
internal suspend inline fun <T> mountedForm(
    id: String,
    block: (Root, () -> HTMLElement) -> T,
): T =
    withMountedRoot(id) { root, element ->
        try {
            block(root, element)
        } finally {
            settleAppScope()
            closeOpenModals(timeoutMs = 1000)
        }
    }

/** The coroutines currently running in the production [AppScope] (every screen load, every guarded action, every reload). */
private fun activeAppScopeWork(): List<Job> {
    val scopeJob = AppScope.coroutineContext[Job] ?: return emptyList()
    return scopeJob.children.filter { it.isActive }.toList()
}

/**
 * Cancels every coroutine still running in [AppScope] -- a best-effort net, not a guarantee: a Kilua RPC call that is already
 * waiting for its answer is NOT interrupted by this (observed: the cancelled reopen of the revote test still returned its result
 * and reloaded the screen), so the code after it carries on. What really keeps a test's work out of the next test is that it
 * finishes in time ([settleAppScope]) and that a stub answers nothing once its test is over ([withFetchStub]).
 */
internal fun cancelAppScopeWork() {
    activeAppScopeWork().forEach { it.cancel() }
}

/**
 * Lets the background work a test has started finish BEFORE its fetch stub goes away: waits up to [graceMs] until no [AppScope]
 * coroutine is running any more (a write's follow-up keeps the count above zero throughout, because each step launches the next
 * before it ends), then cancels whatever is still running (a screen that polls forever never settles).
 *
 * Why (found 2026-10-02 while chasing the flaky `ConsensusDetailDomTest.anOpenConsensus_listsTheNamedRatings_onlyThere`, whose
 * own cause was a wait for the wrong text): a test that clicks a write waits only until the write REQUEST is recorded and then
 * ends, while the write's answer and the reload of the whole screen that follows it can still be running in [AppScope] -- seen
 * under CPU load at the end of the revote, evaluate, freeze-conflict and close-rating phases. The test's stub is removed, the next
 * test installs its own, and the leftover reload is answered by the NEXT test's world (in a detached panel): every load request
 * then appears a second time in that test's call log, and a leftover request inside a [routeOf] window hands back a foreign
 * route (observed: the evaluate reload of one test inside the routes lookup of the next). Holding back the revote's reopen answer
 * until the next test had installed its stub made that next test fail deterministically; with this settle and the closed-stub
 * rule of [withFetchStub] it passes.
 */
internal suspend fun settleAppScope(graceMs: Int = 1500) {
    var waited = 0
    while (activeAppScopeWork().isNotEmpty() && waited < graceMs) {
        delay(10)
        waited += 10
    }
    cancelAppScopeWork()
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
    val control = controlOf(label, nth)
    if (control is HTMLInputElement && control.getAttribute("role") == "combobox") {
        chooseInCombobox(control, value)
        return
    }
    val select = control as HTMLSelectElement
    select.value = value
    select.dispatchEvent(Event("change"))
}

/**
 * The ids of the entries a [SearchableSelect] offers right now (the none entry `""` included), read by opening its list and
 * closing it again with Esc. For tests that wait for options that arrive asynchronously.
 */
internal fun HTMLElement.comboOptionIds(
    label: String,
    nth: Int = 0,
): List<String> {
    val input = controlOf(label, nth) as HTMLInputElement
    input.click()
    val ids = (input.closest(".lapis-ssel") as HTMLElement).allOf("[role=option]").map { it.getAttribute("data-value").orEmpty() }
    input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    return ids
}

/**
 * The id a [SearchableSelect] currently holds (`""` when nothing is chosen): the field itself only shows the NAME, so this opens the
 * list, reads the entry marked `aria-selected`, and closes it with Esc again.
 */
internal fun HTMLElement.comboValue(
    label: String,
    nth: Int = 0,
): String {
    val input = controlOf(label, nth) as HTMLInputElement
    input.click()
    val selected =
        (input.closest(".lapis-ssel") as HTMLElement)
            .querySelector("[role=option][aria-selected=true]")
            ?.getAttribute("data-value")
            .orEmpty()
    input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    return selected
}

/**
 * Picks the entry with id [value] from a [SearchableSelect] the way a person does: focus, click (opens the list), click the entry.
 * The entry must be among the rendered ones (at most 50) -- fine for test data; a test with more options types a query first.
 */
internal fun chooseInCombobox(
    input: HTMLInputElement,
    value: String,
) {
    input.focus()
    input.click()
    val wrapper = assertNotNull(input.closest(".lapis-ssel") as? HTMLElement, "combobox without .lapis-ssel wrapper")
    val option = assertNotNull(wrapper.querySelector("[role=option][data-value='$value']") as? HTMLElement, "no option with id '$value'")
    option.click()
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
