package network.lapis.cloud.client

import io.kvision.panel.simplePanel
import kotlinx.browser.document
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun notice(
    label: String = "2026-11-04.v1",
    sha: Char = 'a',
    headline: String = "Aufzeichnung im Saal",
) = EventCaptureNoticeDto(
    versionLabel = label,
    sha256 = sha.toString().repeat(64),
    headline = headline,
    keyPoints = listOf("Punkt eins", "Punkt zwei", "Punkt drei"),
    fullText = "$headline\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz.",
)

private fun HTMLElement.text(): String = textContent.orEmpty()

/**
 * V1.9.280 -- the question INSIDE an event room ([showInRoomCaptureConsent], mode `IN_ROOM`): a blocking dialog with the focus on the heading, Escape that
 * does nothing, a primary button that is `aria-disabled` (never `disabled`) with its reason until the box is ticked, "Ablehnen und Raum verlassen" that
 * leaves at once, a stale text that reloads and says so, and nothing left in the document after `dispose` / `close`.
 */
class EventCaptureInRoomConsentDomTest {
    private fun HTMLElement.dialog(): HTMLElement =
        assertNotNull(querySelector("[role=dialog].lapis-event-capture-ask") as? HTMLElement, "no dialog")

    private fun HTMLElement.box(): HTMLInputElement = allOf("input[type=checkbox]").map { it as HTMLInputElement }.single()

    @Test
    fun theDialog_isModal_labelled_andStartsOnTheHeading(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-basics") { root, element ->
                val host = root.simplePanel()
                val handle =
                    showInRoomCaptureConsent(parent = host, notice = notice(), onAccept = { InRoomAcceptResult.ACCEPTED }, onDecline = {})
                try {
                    awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                    val dialog = element().dialog()
                    assertEquals("true", dialog.getAttribute("aria-modal"))
                    val heading = assertNotNull(dialog.querySelector("h2") as? HTMLElement)
                    assertEquals("Ab jetzt darf dieser Raum aufgezeichnet werden", heading.text())
                    assertEquals(heading.getAttribute("id"), dialog.getAttribute("aria-labelledby"))
                    awaitUntil("the focus is on the heading", 1000) { document.activeElement === heading }
                    assertTrue(dialog.text().contains("Ohne Ihre Zustimmung können Sie nicht im Raum bleiben."))
                    assertTrue(dialog.text().contains("Aufzeichnung im Saal"))
                    assertTrue(dialog.text().contains("Detailabsatz."))
                    // the scrim is a sibling of the dialog: nothing that contains the call is filtered
                    assertNotNull(element().querySelector(".lapis-event-capture-ask-scrim"))
                    assertEquals(null, dialog.parentElement?.getAttribute("style")?.takeIf { it.contains("filter") })
                } finally {
                    handle.dispose()
                }
            }
        }

    @Test
    fun escapeDoesNothing_andTheFocusStaysInsideTheDialog(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-escape") { root, element ->
                val host = root.simplePanel()
                val handle =
                    showInRoomCaptureConsent(parent = host, notice = notice(), onAccept = { InRoomAcceptResult.ACCEPTED }, onDecline = {})
                try {
                    awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                    val escape = KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true))
                    element().dialog().dispatchEvent(escape)
                    assertTrue(escape.defaultPrevented, "Escape is swallowed")
                    assertTrue(handle.isOpen)
                    assertNotNull(element().querySelector("[role=dialog]"))
                    // Tab from the last control wraps to the first
                    val last = element().dialog().allOf("button").last()
                    last.focus()
                    val tab = KeyboardEvent("keydown", KeyboardEventInit(key = "Tab", bubbles = true, cancelable = true))
                    last.dispatchEvent(tab)
                    assertTrue(tab.defaultPrevented)
                    assertTrue(element().dialog().contains(document.activeElement))
                } finally {
                    handle.dispose()
                }
            }
        }

    @Test
    fun thePrimaryButton_isAriaDisabledWithItsReason_untilTheBoxIsTicked(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-primary") { root, element ->
                val host = root.simplePanel()
                val accepted = mutableListOf<EventCaptureAcknowledgmentInput>()
                val handle =
                    showInRoomCaptureConsent(
                        parent = host,
                        notice = notice(),
                        onAccept = {
                            accepted += it
                            InRoomAcceptResult.ACCEPTED
                        },
                        onDecline = {},
                    )
                awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                val dialog = element().dialog()
                val accept = dialog.buttonNamed("Zustimmen und bleiben")
                assertEquals("true", accept.getAttribute("aria-disabled"))
                assertFalse(accept.hasAttribute("disabled"), "never the native disabled")
                val reasonId = assertNotNull(accept.getAttribute("aria-describedby"))
                assertTrue(assertNotNull(dialog.querySelector("[id='$reasonId']") as? HTMLElement).text().contains("Haken"))
                // a click on the unticked button sends nothing
                accept.click()
                assertTrue(accepted.isEmpty())
                assertTrue(handle.isOpen)
                dialog.box().click()
                // a fresh lookup, like a person looking at the screen again (the widget may have been re-rendered)
                assertEquals("false", dialog.buttonNamed("Zustimmen und bleiben").getAttribute("aria-disabled"))
                dialog.buttonNamed("Zustimmen und bleiben").click()
                awaitUntil("closed after the server accepted") { !handle.isOpen }
                assertEquals(listOf(EventCaptureAcknowledgmentInput("2026-11-04.v1", "a".repeat(64))), accepted)
                assertNull(element().querySelector("[role=dialog]"), "nothing is left in the document")
            }
        }

    @Test
    fun aFailedAnswer_keepsTheDialogOpen(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-failed") { root, element ->
                val host = root.simplePanel()
                var answers = 0
                val handle =
                    showInRoomCaptureConsent(
                        parent = host,
                        notice = notice(),
                        onAccept = { if (++answers == 1) InRoomAcceptResult.FAILED else InRoomAcceptResult.ACCEPTED },
                        onDecline = {},
                    )
                awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                val dialog = element().dialog()
                dialog.box().click()
                dialog.buttonNamed("Zustimmen und bleiben").click()
                awaitUntil("the first answer is in") { answers == 1 }
                assertTrue(handle.isOpen, "the dialog closes only when the server accepted")
                awaitUntil("the button is usable again") {
                    dialog.buttonNamed("Zustimmen und bleiben").getAttribute("aria-disabled") ==
                        "false"
                }
                dialog.buttonNamed("Zustimmen und bleiben").click()
                awaitUntil("closed") { !handle.isOpen }
            }
        }

    @Test
    fun declining_leavesAtOnce_withoutAQuestion(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-decline") { root, element ->
                val host = root.simplePanel()
                var declined = 0
                val handle =
                    showInRoomCaptureConsent(
                        parent = host,
                        notice = notice(),
                        onAccept = { InRoomAcceptResult.ACCEPTED },
                        onDecline = { declined++ },
                    )
                awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                element().dialog().buttonNamed("Ablehnen und Raum verlassen").click()
                assertEquals(1, declined)
                assertFalse(handle.isOpen)
                assertNull(element().querySelector("[role=dialog]"))
                assertEquals(null, document.querySelector(".modal.show"), "no second question")
            }
        }

    @Test
    fun aStaleText_reloads_resetsTheBox_andSaysSo(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-outdated") { root, element ->
                val host = root.simplePanel()
                val sent = mutableListOf<EventCaptureAcknowledgmentInput>()
                val handle =
                    showInRoomCaptureConsent(
                        parent = host,
                        notice = notice(),
                        onAccept = {
                            sent += it
                            if (sent.size == 1) InRoomAcceptResult.OUTDATED else InRoomAcceptResult.ACCEPTED
                        },
                        onDecline = {},
                        reloadNotice = { notice(label = "2026-11-05.v2", sha = 'b', headline = "Neuer Hinweis") },
                    )
                awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                val dialog = element().dialog()
                dialog.box().click()
                dialog.buttonNamed("Zustimmen und bleiben").click()
                awaitUntil("the new text") { dialog.text().contains("Neuer Hinweis") }
                assertTrue(dialog.text().contains("Der Hinweistext wurde inzwischen geändert. Bitte lesen Sie ihn erneut."))
                assertFalse(dialog.box().checked, "the box is reset: the person reads the new text")
                assertEquals("true", dialog.buttonNamed("Zustimmen und bleiben").getAttribute("aria-disabled"))
                assertTrue(handle.isOpen)
                dialog.box().click()
                dialog.buttonNamed("Zustimmen und bleiben").click()
                awaitUntil("closed") { !handle.isOpen }
                assertEquals("2026-11-05.v2", sent.last().consentVersion)
                assertEquals("b".repeat(64), sent.last().consentSha256)
            }
        }

    @Test
    fun dispose_removesTheDialog_andIsIdempotent(): Promise<Unit> =
        formTest {
            mountedForm("in-room-consent-dispose") { root, element ->
                val host = root.simplePanel()
                val handle =
                    showInRoomCaptureConsent(parent = host, notice = notice(), onAccept = { InRoomAcceptResult.ACCEPTED }, onDecline = {})
                awaitUntil("the dialog") { element().querySelector("[role=dialog]") != null }
                handle.dispose()
                handle.dispose()
                handle.close()
                assertFalse(handle.isOpen)
                assertNull(element().querySelector("[role=dialog]"))
                assertNull(element().querySelector(".lapis-event-capture-ask-scrim"))
            }
        }
}
