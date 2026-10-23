package network.lapis.cloud.client

import io.kvision.panel.simplePanel
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun noticeDto() =
    EventCaptureNoticeDto(
        versionLabel = "2026-10-18.v1",
        sha256 = "a".repeat(64),
        headline = "Aufzeichnung im Saal",
        keyPoints = listOf("Punkt eins", "Punkt zwei", "Punkt drei"),
        fullText = "Aufzeichnung im Saal\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz.",
    )

private fun HTMLElement.text(): String = textContent.orEmpty()

/**
 * V1.9.156 -- the entry screen of a member into a released event room: not a modal, the focus on the heading, the headline / key points / full text of the
 * consent behind a disclosure (always as text), "Teilnehmen" locked until every box is ticked, Escape and "Abbrechen" cancel; a guest account sees the
 * capture variant of the guest notice (never "Es wird nichts aufgezeichnet.") with a box of its own on the same screen.
 */
class EventCaptureConsentDomTest {
    private fun HTMLElement.boxes(): List<HTMLInputElement> = allOf("input[type=checkbox]").map { it as HTMLInputElement }

    @Test
    fun aMember_seesTheConsent_andJoinsOnlyAfterTickingTheBox(): Promise<Unit> =
        formTest {
            mountedForm("capture-consent-member") { root, element ->
                val host = root.simplePanel()
                val decision =
                    CoroutineScope(
                        Dispatchers.Default,
                    ).async { awaitEventCaptureNotice(host, noticeDto(), withGuestNotice = false) }
                awaitUntil("the screen") { element().text().contains("Aufzeichnung und Übertragung in diesem Raum") }
                val screen = element()
                assertTrue(screen.text().contains("Aufzeichnung im Saal"), "headline")
                listOf("Punkt eins", "Punkt zwei", "Punkt drei").forEach { assertTrue(screen.text().contains(it), it) }
                assertTrue(screen.querySelector("details summary") != null, "the full text sits behind a disclosure")
                assertTrue(screen.text().contains("Detailabsatz."))
                assertEquals(null, document.querySelector(".modal.show"), "not a modal")
                assertEquals(1, screen.boxes().size, "one box for a member")
                val heading = assertNotNull(screen.allOf("h2").firstOrNull { it.text().contains("Aufzeichnung und Übertragung") })
                assertEquals("-1", heading.getAttribute("tabindex"))
                awaitUntil("the focus is on the heading", 1000) { document.activeElement === heading }
                val join = screen.buttonNamed("Teilnehmen") as HTMLButtonElement
                assertTrue(join.disabled, "locked until the box is ticked")
                screen.boxes().single().click()
                awaitUntil("unlocked") { !(screen.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled }
                screen.buttonNamed("Teilnehmen").click()
                assertEquals(true, decision.await())
            }
        }

    @Test
    fun escapeAndAbbrechen_cancel(): Promise<Unit> =
        formTest {
            mountedForm("capture-consent-cancel") { root, element ->
                val host = root.simplePanel()
                val first =
                    CoroutineScope(
                        Dispatchers.Default,
                    ).async { awaitEventCaptureNotice(host, noticeDto(), withGuestNotice = false) }
                awaitUntil("the screen") { element().text().contains("Aufzeichnung und Übertragung in diesem Raum") }
                element().querySelector(".lapis-event-capture-consent")!!.dispatchEvent(
                    KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)),
                )
                assertEquals(false, first.await())
                // the screen of the cancelled entry is taken away (the caller does that), so the next wait sees the NEW screen
                host.removeAll()
                awaitUntil("the old screen is gone") { !element().text().contains("Aufzeichnung und Übertragung in diesem Raum") }
                val second =
                    CoroutineScope(
                        Dispatchers.Default,
                    ).async { awaitEventCaptureNotice(host, noticeDto(), withGuestNotice = false) }
                awaitUntil("the screen again") { element().text().contains("Aufzeichnung und Übertragung in diesem Raum") }
                element().buttonNamed("Abbrechen").click()
                assertEquals(false, second.await())
            }
        }

    @Test
    fun aGuestAccount_sees_theCaptureVariantOfTheNotice_andNeedsBothBoxes(): Promise<Unit> =
        formTest {
            mountedForm("capture-consent-guest") { root, element ->
                val host = root.simplePanel()
                val decision =
                    CoroutineScope(
                        Dispatchers.Default,
                    ).async { awaitEventCaptureNotice(host, noticeDto(), withGuestNotice = true) }
                awaitUntil(
                    "the screen",
                ) { element().text().contains("Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus.") }
                val screen = element()
                // the old promise must not appear in a released event
                assertFalse(screen.text().contains("Es wird nichts aufgezeichnet"))
                assertEquals(2, screen.boxes().size)
                // the consent comes FIRST, the guest notice and its box below it
                assertTrue(screen.text().indexOf("Ich willige ein") < screen.text().indexOf("Ich habe den Hinweis gelesen."))
                val join = { screen.buttonNamed("Teilnehmen") as HTMLButtonElement }
                assertTrue(join().disabled)
                screen.boxes()[0].click()
                assertTrue(join().disabled, "one box is not enough")
                screen.boxes()[1].click()
                awaitUntil("unlocked") { !join().disabled }
                join().click()
                assertEquals(true, decision.await())
            }
        }

    @Test
    fun theTextOfTheConsentIsShownAsText_neverAsMarkup(): Promise<Unit> =
        formTest {
            mountedForm("capture-consent-untrusted") { root, element ->
                val host = root.simplePanel()
                val hostile =
                    noticeDto().copy(
                        headline = "<img src=x onerror=alert(1)>Kopf",
                        keyPoints = listOf("<b>fett</b>", "zwei", "drei"),
                    )
                val decision = CoroutineScope(Dispatchers.Default).async { awaitEventCaptureNotice(host, hostile, withGuestNotice = false) }
                awaitUntil("the screen") { element().text().contains("Kopf") }
                assertEquals(null, element().querySelector("img[src=x]"))
                assertEquals(null, element().querySelector(".lapis-event-capture-consent b"))
                assertTrue(element().text().contains("<b>fett</b>"))
                element().buttonNamed("Abbrechen").click()
                assertEquals(false, decision.await())
            }
        }
}

/** V1.9.156 -- the capture line of an event room: words AND a dot, a live region mounted once, no change on "nothing learned". */
class EventCaptureBannerDomTest {
    @Test
    fun theLineFollowsTheState_andNullKeepsTheLastOne(): Promise<Unit> =
        formTest {
            mountedForm("capture-banner") { root, element ->
                val banner = EventCaptureBanner(root)
                val region = assertNotNull(element().querySelector(".lapis-event-capture") as? HTMLElement)
                assertEquals("status", region.getAttribute("role"))
                assertEquals("polite", region.getAttribute("aria-live"))
                assertFalse(banner.visible, "nothing runs at the start")
                assertEquals("", banner.shownText)

                banner.render(EventCaptureLiveDto(recording = true, streaming = false))
                assertTrue(banner.visible)
                assertEquals("Diese Besprechung wird aufgezeichnet.", banner.shownText)
                banner.render(EventCaptureLiveDto(recording = false, streaming = true))
                assertEquals("Diese Besprechung wird live übertragen.", banner.shownText)
                banner.render(EventCaptureLiveDto(recording = true, streaming = true))
                assertEquals("Diese Besprechung wird aufgezeichnet und live übertragen.", banner.shownText)
                // a state that could not be learned changes nothing -- never "off on suspicion"
                banner.render(null)
                assertTrue(banner.visible)
                assertEquals("Diese Besprechung wird aufgezeichnet und live übertragen.", banner.shownText)
                banner.render(EventCaptureLiveDto(recording = false, streaming = false))
                assertFalse(banner.visible)
                // the region itself stays in the document (a screen reader announces a change in an existing region)
                assertTrue(element().querySelector(".lapis-event-capture") === region)
                // no close button, nothing to switch off
                assertEquals(0, region.allOf("button").size)
            }
        }

    @Test
    fun theDotIsDecoration_theWordsCarryTheMeaning(): Promise<Unit> =
        formTest {
            mountedForm("capture-banner-a11y") { root, element ->
                val banner = EventCaptureBanner(root)
                banner.render(EventCaptureLiveDto(recording = true, streaming = false))
                val dot = assertNotNull(element().querySelector(".lapis-event-capture-dot") as? HTMLElement)
                assertEquals("true", dot.getAttribute("aria-hidden"))
                assertTrue(element().text().contains("aufgezeichnet"))
            }
        }

    private fun sessionOf(memberId: String) =
        SessionInfoDto(
            memberId = memberId,
            displayName = "Testperson $memberId",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    @Test
    fun otherAccountInTheSameTab_isAskedAgain(): Promise<Unit> =
        formTest {
            val notice = noticeDto()
            try {
                AppState.setSession(sessionOf("member-a"))
                EventCaptureConsentMemory.remember("event-1", notice)
                assertTrue(EventCaptureConsentMemory.covers("event-1", notice), "a reconnect of the same person asks nothing")
                // a refresh of the SAME identity (new expiry) keeps the consent
                AppState.setSession(sessionOf("member-a").copy(expiresAt = LocalDateTime(2099, 1, 2, 0, 0)))
                assertTrue(EventCaptureConsentMemory.covers("event-1", notice), "same identity, new expiry")
                // sign-out: an SPA navigation, no reload
                AppState.setSession(null)
                assertFalse(EventCaptureConsentMemory.covers("event-1", notice), "after sign-out")
                // another account in the same tab
                EventCaptureConsentMemory.remember("event-1", notice)
                AppState.setSession(sessionOf("member-b"))
                assertFalse(EventCaptureConsentMemory.covers("event-1", notice), "member B never saw the dialog")
            } finally {
                AppState.setSession(null)
                EventCaptureConsentMemory.clear()
            }
        }

    @Test
    fun aMarkdownNotice_showsPlainSummary_andTheServersHtmlBehindTheDisclosure(): Promise<Unit> =
        formTest {
            mountedForm("capture-consent-markdown") { root, element ->
                val host = root.simplePanel()
                val markdown =
                    noticeDto().copy(
                        fullText = "Quelltext **roh**",
                        format = network.lapis.cloud.shared.domain.AgreementTextFormat.MARKDOWN,
                        renderedHtml = "<h3>Einzelheiten</h3>\n<p>Mit <strong>Gewicht</strong>.</p>\n",
                    )
                CoroutineScope(Dispatchers.Default).async { awaitEventCaptureNotice(host, markdown, withGuestNotice = false) }
                awaitUntil("the screen") { element().text().contains("Aufzeichnung im Saal") }
                val details = assertNotNull(element().querySelector("details") as? HTMLElement)
                assertEquals("Einzelheiten", details.querySelector(".lapis-agreement-body h3")?.textContent)
                assertEquals("Gewicht", details.querySelector(".lapis-agreement-body strong")?.textContent)
                assertFalse(details.text().contains("Quelltext"))
            }
        }
}
