package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun HTMLElement.text(): String = textContent.orEmpty()

private val CAPTURE_NOTICE =
    EventCaptureNoticeDto(
        versionLabel = "2026-10-18.v1",
        sha256 = "b".repeat(64),
        headline = "Aufzeichnung im Saal",
        keyPoints = listOf("Punkt eins", "Punkt zwei", "Punkt drei"),
        fullText = "Aufzeichnung im Saal\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz.",
    )

/** Answers the two requests of the guest page from memory and records what was sent. */
private class FakeGuestApi(
    var state: EventOnlineStateDto?,
    var joinAnswer: EventGuestJoinHttpResult = EventGuestJoinHttpResult.Failed,
) : EventGuestApi {
    val sent = mutableListOf<EventGuestJoinRequestDto>()
    var stateReads = 0

    override suspend fun state(slug: String): EventOnlineStateDto? {
        stateReads++
        return state
    }

    override suspend fun join(
        slug: String,
        request: EventGuestJoinRequestDto,
    ): EventGuestJoinHttpResult {
        sent += request
        return joinAnswer
    }
}

/**
 * V1.9.156 -- the public page of a person WITHOUT an account in an event that may be recorded: the capture consent comes with a box of its own ABOVE the guest
 * notice and its box, the guest notice is the capture variant (never "Es wird nichts aufgezeichnet."), the request carries both consents, an error sits at its
 * own box, and a 409 reads the state again instead of leaving the person with a stale form. An event that is not released looks like before.
 */
class EventGuestCaptureDomTest {
    private fun openState(capture: Boolean) =
        EventOnlineStateDto(
            outcome = EventOnlineOutcome.JOINED,
            title = "Offene Runde",
            opensAt = "2030-06-15T17:30",
            closesAt = "2030-06-15T21:00",
            noticeVersion = if (capture) EventGuestNoticeCapture.VERSION else EventGuestNotice.VERSION,
            noticeSha256 = if (capture) EventGuestNoticeCapture.SHA256 else EventGuestNotice.SHA256,
            captureNotice = if (capture) CAPTURE_NOTICE else null,
            captureLive = if (capture) EventCaptureLiveDto(recording = false, streaming = false) else null,
        )

    private suspend fun withPage(
        api: FakeGuestApi,
        started: MutableList<EventGuestJoinResponseDto> = mutableListOf(),
        block: suspend (HTMLElement) -> Unit,
    ) {
        mountedForm("event-guest-capture") { root, element ->
            renderEventJoinScreenWith(
                root = root,
                slug = "offene-runde",
                api = api,
                memberRoute = { },
                startCall =
                    EventGuestCallStarter { _: SimplePanel, response: EventGuestJoinResponseDto, _: () -> Unit -> started += response },
            )
            awaitUntil("the form") { element().querySelector(".lapis-form") != null }
            block(element())
        }
    }

    private fun HTMLElement.boxes(): List<HTMLInputElement> = allOf("input[type=checkbox]").map { it as HTMLInputElement }

    @Test
    fun aReleasedEvent_showsTheConsentFirst_andTheCaptureVariantOfTheNotice(): Promise<Unit> =
        formTest {
            withPage(FakeGuestApi(openState(capture = true))) { page ->
                val text = page.text()
                assertTrue(text.contains("Aufzeichnung und Übertragung in diesem Raum"))
                assertTrue(text.contains("Aufzeichnung im Saal"))
                assertTrue(page.querySelector("details summary") != null, "the full text behind a disclosure")
                assertTrue(text.contains("Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus."))
                assertFalse(text.contains("Es wird nichts aufgezeichnet"), "the old promise is false here")
                assertEquals(2, page.boxes().size)
                assertTrue(text.indexOf("Ich willige ein") < text.indexOf("Ich habe den Hinweis gelesen."), "consent box first")
            }
        }

    @Test
    fun anUnreleasedEvent_looksLikeBefore(): Promise<Unit> =
        formTest {
            withPage(FakeGuestApi(openState(capture = false))) { page ->
                val text = page.text()
                assertTrue(text.contains("Es wird nichts aufgezeichnet."))
                assertFalse(text.contains("Aufzeichnung und Übertragung in diesem Raum"))
                assertEquals(1, page.boxes().size)
            }
        }

    @Test
    fun theRequestCarriesBothConsents_andAMissingBoxIsAnErrorAtThatBox(): Promise<Unit> =
        formTest {
            val api =
                FakeGuestApi(
                    openState(capture = true),
                    joinAnswer = EventGuestJoinHttpResult.Answer(EventGuestJoinResponseDto(outcome = EventOnlineOutcome.FORBIDDEN)),
                )
            withPage(api) { page ->
                page.typeInto("Ihr Name", "  Anna  ")
                // only the guest notice is ticked: the consent box must complain at ITS place, and nothing is sent
                page.boxes()[1].click()
                page.buttonNamed("Beitreten").click()
                awaitUntil(
                    "an error at the consent box",
                ) { page.shownErrors().any { it.contains("Bitte bestätigen Sie die Einwilligung") } }
                assertEquals(0, api.sent.size)
                page.boxes()[0].click()
                page.buttonNamed("Beitreten").click()
                awaitUntil("the request") { api.sent.size == 1 }
                val request = api.sent.single()
                assertEquals("Anna", request.name)
                assertEquals(EventGuestNoticeCapture.VERSION, request.consentVersion)
                assertEquals(EventGuestNoticeCapture.SHA256, request.consentSha256)
                assertEquals(CAPTURE_NOTICE.versionLabel, request.captureConsentVersion)
                assertEquals(CAPTURE_NOTICE.sha256, request.captureConsentSha256)
            }
        }

    @Test
    fun anUnreleasedEvent_sendsNoCaptureFields(): Promise<Unit> =
        formTest {
            val api =
                FakeGuestApi(
                    openState(capture = false),
                    joinAnswer = EventGuestJoinHttpResult.Answer(EventGuestJoinResponseDto(outcome = EventOnlineOutcome.FORBIDDEN)),
                )
            withPage(api) { page ->
                page.typeInto("Ihr Name", "Anna")
                page.boxes().single().click()
                page.buttonNamed("Beitreten").click()
                awaitUntil("the request") { api.sent.size == 1 }
                assertEquals(EventGuestNotice.VERSION, api.sent.single().consentVersion)
                assertEquals(null, api.sent.single().captureConsentVersion)
                assertEquals(null, api.sent.single().captureConsentSha256)
            }
        }

    @Test
    fun a409_readsTheStateAgain_andShowsWhatIsAskedNow(): Promise<Unit> =
        formTest {
            // the page was loaded before the event was released; the server now wants the consent
            val api = FakeGuestApi(openState(capture = false), joinAnswer = EventGuestJoinHttpResult.NoticeOutdated)
            withPage(api) { page ->
                page.typeInto("Ihr Name", "Anna")
                page.boxes().single().click()
                api.state = openState(capture = true)
                page.buttonNamed("Beitreten").click()
                awaitUntil("the state is read again", 3000) { api.stateReads >= 2 }
                awaitUntil("the consent is there") { page.text().contains("Aufzeichnung und Übertragung in diesem Raum") }
                assertTrue(page.text().contains("Der Hinweis wurde geändert. Bitte lesen und bestätigen Sie ihn erneut."))
                assertEquals(2, page.boxes().size)
                assertTrue(page.boxes().none { it.checked }, "nothing is pre-ticked")
                assertNotNull(page.querySelector(".lapis-form"))
                assertFalse((page.buttonNamed("Beitreten") as HTMLButtonElement).disabled)
            }
        }
}
