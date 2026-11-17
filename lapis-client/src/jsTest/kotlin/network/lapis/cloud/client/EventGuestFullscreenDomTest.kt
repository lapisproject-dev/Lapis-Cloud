package network.lapis.cloud.client

import io.kvision.panel.Root
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PSEUDO = "is-pseudo-fullscreen"

private class ConsentApi : EventGuestApi {
    override suspend fun state(slug: String): EventOnlineStateDto? =
        EventOnlineStateDto(
            outcome = EventOnlineOutcome.JOINED,
            title = "Offene Runde",
            captureNotice =
                EventCaptureNoticeDto(
                    versionLabel = "2026-11-04.v1",
                    sha256 = "a".repeat(64),
                    headline = "Aufzeichnung im Saal",
                    keyPoints = listOf("Punkt eins", "Punkt zwei", "Punkt drei"),
                    fullText = "Aufzeichnung im Saal\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz.",
                ),
        )

    override suspend fun join(
        slug: String,
        request: EventGuestJoinRequestDto,
    ): EventGuestJoinHttpResult = EventGuestJoinHttpResult.Failed
}

/**
 * V1.9.309 -- the full-screen button of the guest call view: the stage element holds the whole call, the button mirrors the state of
 * [network.lapis.cloud.client.encounter.EncounterFullscreen], and every way out (Escape, leaving, navigation, the consent dialog) ends the full screen.
 */
class EventGuestFullscreenDomTest {
    private fun response() =
        EventGuestJoinResponseDto(
            outcome = EventOnlineOutcome.JOINED,
            identity = "evg-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            displayName = "Selma",
            token = "tok-1",
        )

    private suspend fun withView(
        native: Boolean = false,
        onLeft: () -> Unit = {},
        block: suspend (EventGuestCallView, HTMLElement, SimplePanel) -> Unit,
    ) {
        mountedForm("event-guest-fullscreen") { root: Root, element ->
            val host = root.simplePanel()
            val view =
                EventGuestCallView(
                    host = host,
                    response = response(),
                    onLeft = onLeft,
                    slug = "offene-runde",
                    api = ConsentApi(),
                    fullscreenNativeSupported = { native },
                )
            view.show()
            try {
                awaitUntil("the call is shown") { element().textContent.orEmpty().contains("Die Teilnahme ist gerade nicht möglich") }
                block(view, element(), host)
            } finally {
                view.disposeFullscreen()
                view.endHands()
            }
        }
    }

    private fun HTMLElement.stage(): HTMLElement = assertNotNull(querySelector(".lapis-guest-stage") as? HTMLElement)

    private fun HTMLElement.button(): HTMLElement = assertNotNull(allOf("button").firstOrNull { it.textContent?.trim() == "Vollbild" })

    private fun escape(defaultPrevented: Boolean = false) {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", cancelable = true))
        if (defaultPrevented) event.preventDefault()
        document.dispatchEvent(event)
    }

    @Test
    fun theButton_sitsBetweenDevicesAndLeave_andStartsNotPressed(): Promise<Unit> =
        formTest {
            withView { _, page, _ ->
                val labels = page.allOf("button").map { it.textContent.orEmpty().trim() }
                val at = labels.indexOf("Vollbild")
                assertTrue(at > 0)
                assertEquals("Geräte", labels[at - 1])
                assertEquals("Verlassen", labels[at + 1])
                assertEquals("false", page.button().getAttribute("aria-pressed"))
            }
        }

    @Test
    fun withoutTheApi_aClickGivesACssFullscreenOnTheStage_andEscapeLeavesIt(): Promise<Unit> =
        formTest {
            withView { _, page, host ->
                val button = page.button()
                button.click()
                awaitUntil("the css fullscreen is on") { page.stage().classList.contains(PSEUDO) }
                assertFalse((host.getElement() as HTMLElement).classList.contains(PSEUDO))
                assertEquals("true", button.getAttribute("aria-pressed"))
                assertTrue(button.classList.contains("active"))
                assertEquals("Vollbild", button.textContent?.trim())
                awaitUntil("the symbol shows the way back") { button.querySelector("i")?.classList?.contains("fa-compress") == true }
                escape()
                awaitUntil("Escape left it") { !page.stage().classList.contains(PSEUDO) }
                assertEquals("false", button.getAttribute("aria-pressed"))
                assertFalse(button.classList.contains("active"))
                awaitUntil("the symbol is back") { button.querySelector("i")?.classList?.contains("fa-expand") == true }
            }
        }

    @Test
    fun anEscapeSomeoneElseHandled_keepsTheFullscreen(): Promise<Unit> =
        formTest {
            withView { _, page, _ ->
                page.button().click()
                awaitUntil("on") { page.stage().classList.contains(PSEUDO) }
                escape(defaultPrevented = true)
                assertTrue(page.stage().classList.contains(PSEUDO))
                assertEquals("true", page.button().getAttribute("aria-pressed"))
            }
        }

    @Test
    fun theNativeApi_isFollowedThroughFullscreenchange(): Promise<Unit> =
        formTest {
            withView(native = true) { _, page, _ ->
                val stage = page.stage()
                stage.asDynamic().requestFullscreen = { Promise.resolve(null) }
                try {
                    page.button().click()
                    js("Object.defineProperty(document, 'fullscreenElement', {value: stage, configurable: true})")
                    document.dispatchEvent(Event("fullscreenchange"))
                    awaitUntil("pressed") { page.button().getAttribute("aria-pressed") == "true" }
                    assertFalse(stage.classList.contains(PSEUDO))
                    js("Object.defineProperty(document, 'fullscreenElement', {value: null, configurable: true})")
                    document.dispatchEvent(Event("fullscreenchange"))
                    awaitUntil("not pressed") { page.button().getAttribute("aria-pressed") == "false" }
                } finally {
                    js("delete document.fullscreenElement")
                }
            }
        }

    @Test
    fun aRejectedRequest_fallsBackToTheCssFullscreen(): Promise<Unit> =
        formTest {
            withView(native = true) { _, page, _ ->
                page.stage().asDynamic().requestFullscreen = { Promise.reject(IllegalStateException("denied")) }
                page.button().click()
                awaitUntil("the fallback took over") { page.stage().classList.contains(PSEUDO) }
                assertEquals("true", page.button().getAttribute("aria-pressed"))
            }
        }

    @Test
    fun leavingTheCall_endsTheFullscreen_andRemovesTheListeners(): Promise<Unit> =
        formTest {
            var left = false
            withView(onLeft = { left = true }) { _, page, _ ->
                page.button().click()
                awaitUntil("on") { page.stage().classList.contains(PSEUDO) }
                page.buttonNamed("Verlassen").click()
                awaitUntil("the stage left the fullscreen") { !page.stage().classList.contains(PSEUDO) }
                awaitUntil("onLeft") { left }
                assertEquals("false", page.button().getAttribute("aria-pressed"))
                escape()
                page.button().click()
                assertFalse(page.stage().classList.contains(PSEUDO), "a disposed control does not toggle")
            }
        }

    @Test
    fun clearingTheHost_endsTheFullscreen(): Promise<Unit> =
        formTest {
            withView { view, page, host ->
                page.button().click()
                awaitUntil("on") { page.stage().classList.contains(PSEUDO) }
                val stage = page.stage()
                host.removeAll()
                awaitUntil("the destroy hook cleaned up") { !stage.classList.contains(PSEUDO) }
                escape()
                view.disposeFullscreen()
            }
        }

    @Test
    fun aPageHide_endsTheFullscreen(): Promise<Unit> =
        formTest {
            withView { _, page, _ ->
                page.button().click()
                awaitUntil("on") { page.stage().classList.contains(PSEUDO) }
                window.dispatchEvent(Event("pagehide"))
                assertFalse(page.stage().classList.contains(PSEUDO))
                assertEquals("false", page.button().getAttribute("aria-pressed"))
            }
        }

    @Test
    fun theConsentDialog_endsTheFullscreenFirst_andLivesOutsideTheStage(): Promise<Unit> =
        formTest {
            withView { view, page, _ ->
                page.button().click()
                awaitUntil("on") { page.stage().classList.contains(PSEUDO) }
                view.askCaptureConsent()
                awaitUntil("the dialog is open") { page.querySelector("[role=dialog].lapis-event-capture-ask") != null }
                assertFalse(page.stage().classList.contains(PSEUDO), "the dialog must not hide behind the stage")
                val dialog = assertNotNull(page.querySelector("[role=dialog].lapis-event-capture-ask"))
                assertFalse(page.stage().contains(dialog), "the dialog root is not inside the stage")
            }
        }

    @Test
    fun everyCallElementIsInsideTheStage(): Promise<Unit> =
        formTest {
            withView { _, page, _ ->
                val stage = page.stage()
                for (name in listOf("Mikrofon einschalten", "Kamera einschalten", "Geräte", "Vollbild", "Verlassen")) {
                    assertTrue(stage.contains(page.buttonNamed(name)), "$name is in the stage")
                }
                val stateLine = assertNotNull(page.querySelector("[role=status].fw-bold"), "state line")
                assertTrue(stage.contains(stateLine), "the state line is in the stage")
                assertTrue(stage.contains(assertNotNull(page.querySelector(".lapis-guest-grid"), ".lapis-guest-grid")))
                // a closed panel is not rendered at all: open it first
                if (page.querySelector(".lapis-guest-devices") == null) page.buttonNamed("Geräte").click()
                awaitUntil("the device panel is shown") { page.querySelector(".lapis-guest-devices") != null }
                assertTrue(stage.contains(assertNotNull(page.querySelector(".lapis-guest-devices"))))
                assertTrue(stage.contains(assertNotNull(page.querySelector(".lapis-guest-controls"), ".lapis-guest-controls")))
            }
        }

    @Test
    fun aTile_carriesItsWidthInTheClass_notInline(): Promise<Unit> =
        formTest {
            withView { view, _, _ ->
                val tile = view.ensureTile("anna", "Anna")
                assertTrue(tile.classList.contains("lapis-guest-tile"))
                assertFalse(tile.style.cssText.contains("width"), "no inline width: ${tile.style.cssText}")
            }
        }
}
