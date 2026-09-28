package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review fix (Welle V1.9.14, MAJOR test-coverage finding): drives the REAL `RegistrationScreen.kt`
 * catch chain (lines ~143-171 at review time) through a mounted screen and a stubbed `window.fetch`
 * that answers with the actual Kilua RPC wire shape a thrown service exception has
 * ([serviceExceptionResult], verified against the pinned `kilua-rpc-core` 0.0.45 sources -- see its
 * own KDoc). Before this file, nothing exercised this catch chain at all: the concrete regression
 * scenario the review named -- someone reordering the `catch` branches (or dropping the
 * `RegionalChapterRequiredException` one) so it falls through to the generic `guarded { throw e }`
 * toast instead of the field error -- is exactly what [regionalChapterRequiredException_showsFieldError]
 * below catches (it fails the moment the chapter field stops getting its error).
 */
class RegistrationScreenFieldErrorDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.inputs(selector: String): List<HTMLInputElement> = all(selector).map { it as HTMLInputElement }

    private fun fill(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
        input.dispatchEvent(Event("blur"))
    }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    /** Fills every required field of the registration form with valid, distinct values, leaving the chapter select untouched. */
    private fun HTMLElement.fillRequiredFields() {
        fill(inputs("input[type=text]")[0], "Amara Okafor")
        fill(inputs("input[type=email]")[0], "amara@example.org")
        val passwords = inputs("input[type=password]")
        fill(passwords[0], "correct horse battery staple")
        fill(passwords[1], "correct horse battery staple")
        inputs("input[type=checkbox]")[0].click()
    }

    private val agreementJson = """{"version":"v7","text":"Vertragstext","sha256":"sha-agreement-abc"}"""
    private val oneChapterJson = """[{"id":"chapter-1","name":"Landesverband Nord"}]"""

    /** Answers `getMembershipAgreement`/`listRegionalChapterOptions` by ROUTE (both are zero-param, so a bare
     * param-count dispatch like `FormSubmitBodyDomTest.answerLoadWith` cannot tell them apart); `registerApplication`
     * (one param) is answered by [registerResponse]. */
    private suspend fun respondWithChapterAndAgreement(
        registerResponse: (RecordedRequest) -> StubResponse,
    ): (RecordedRequest) -> StubResponse {
        val agreementRoute = routeOf { rpcService<IRegistrationService>().getMembershipAgreement() }
        val chaptersRoute = routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() }
        return { request ->
            when {
                !request.isRpc -> StubResponse()
                request.rpcRoute == agreementRoute -> rpcResult(request.json.id as Int, agreementJson)
                request.rpcRoute == chaptersRoute -> rpcResult(request.json.id as Int, oneChapterJson)
                else -> registerResponse(request)
            }
        }
    }

    @Test
    fun regionalChapterRequiredException_showsFieldError_notTheGenericToast(): Promise<Unit> =
        test {
            val respond =
                respondWithChapterAndAgreement { request ->
                    serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.RegionalChapterRequiredException")
                }
            withFetchStub(respond = respond) {
                withMountedRoot("body-registration-chapter-required") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector("input[type=email]") != null }
                    awaitUntil("the chapter select") { element().querySelector("select") != null }
                    element().fillRequiredFields()
                    val select = element().querySelector("select") as HTMLSelectElement
                    select.value = "chapter-1"
                    select.dispatchEvent(Event("change"))
                    element().button("Antrag einreichen").click()
                    awaitUntil("the field error") { element().shownErrors().isNotEmpty() }
                    assertEquals(listOf("Bitte wählen Sie einen Landesverband."), element().shownErrors())
                    // The regression this test exists for: if the field error is showing, the screen
                    // did NOT fall through to `guarded { throw e }` and its "Antrag eingereicht" success
                    // state -- the form is still up.
                    assertTrue(
                        element().querySelector("input[type=email]") != null,
                        "the form must still be showing, not the pending screen",
                    )
                }
            }
        }

    @Test
    fun badRequestException_withAChapterChosen_showsTheChapterGoneFieldError(): Promise<Unit> =
        test {
            val respond =
                respondWithChapterAndAgreement { request ->
                    serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.BadRequestException")
                }
            withFetchStub(respond = respond) {
                withMountedRoot("body-registration-bad-request-chosen") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector("input[type=email]") != null }
                    awaitUntil("the chapter select") { element().querySelector("select") != null }
                    element().fillRequiredFields()
                    val select = element().querySelector("select") as HTMLSelectElement
                    select.value = "chapter-1"
                    select.dispatchEvent(Event("change"))
                    element().button("Antrag einreichen").click()
                    awaitUntil("the field error") { element().shownErrors().isNotEmpty() }
                    assertEquals(listOf("Dieser Landesverband ist nicht mehr verfügbar -- bitte Seite neu laden."), element().shownErrors())
                }
            }
        }

    @Test
    fun badRequestException_withNoChapterChosen_fallsThroughToTheGenericHandler_noFieldError(): Promise<Unit> =
        test {
            val respond =
                respondWithChapterAndAgreement { request ->
                    serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.BadRequestException")
                }
            withFetchStub(respond = respond) { calls ->
                withMountedRoot("body-registration-bad-request-no-chapter") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector("input[type=email]") != null }
                    awaitUntil("the chapter select") { element().querySelector("select") != null }
                    element().fillRequiredFields()
                    // Chapter left at its default ("— weiß ich noch nicht —", an empty value) --
                    // `chapterId.isNullOrBlank()` is true, so the catch chain must NOT show a field
                    // error for this BadRequestException; it is not necessarily chapter-shaped.
                    element().button("Antrag einreichen").click()
                    awaitUntil("the register request") { calls.any { it.isRpc && (it.json.params.length as Int) == 1 } }
                    delay(150) // let the (rejected) request's continuation finish handling the catch chain
                    assertNoShownError(element())
                    assertTrue(
                        element().querySelector("input[type=email]") != null,
                        "the form must still be showing, not the pending screen",
                    )
                }
            }
        }
}
