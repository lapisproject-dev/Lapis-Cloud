package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventOnlineKind
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.9.113 -- the join button of an event: three states in one place, aria-disabled (never `disabled`), a live clock. */
class EventOnlineJoinButtonDomTest {
    private val opens = LocalDateTime(2026, 11, 1, 17, 45)
    private val closes = LocalDateTime(2026, 11, 1, 23, 0)

    private fun conference() =
        testEvent(onlineKind = EventOnlineKind.LAPIS_CONFERENCE, onlineWindowOpensAt = opens, onlineWindowClosesAt = closes)

    private fun HTMLElement.joinButton(): HTMLElement =
        assertNotNull(querySelector("[id^='lapis-event-online-']:not([id$='-reason'])") as? HTMLElement, "no join button")

    @Test
    fun anEventWithoutALapisKind_addsNothing(): Promise<Unit> =
        formTest {
            mountedForm("event-online-button-none") { root, element ->
                val button = root.eventOnlineJoinButton(testEvent(), now = { opens }) { }
                assertNull(button)
                assertNull(element().querySelector("[id^='lapis-event-online-']"))
            }
        }

    @Test
    fun beforeTheWindow_theButtonStaysFocusableAndExplainsItself_andDoesNotJoin(): Promise<Unit> =
        formTest {
            mountedForm("event-online-button-notyet") { root, element ->
                var joins = 0
                root.eventOnlineJoinButton(conference(), now = { LocalDateTime(2026, 11, 1, 17, 0) }) { joins++ }
                val button = element().joinButton()
                assertTrue(button.textContent.orEmpty().startsWith("Teilnehmen ab"))
                assertEquals("true", button.getAttribute("aria-disabled"))
                assertNull(button.getAttribute("disabled"), "never the disabled attribute: the button must stay a tab stop")
                val reasonId = assertNotNull(button.getAttribute("aria-describedby"))
                assertTrue(
                    element()
                        .querySelector("[id='$reasonId']")
                        ?.textContent
                        .orEmpty()
                        .isNotBlank(),
                )
                button.click()
                assertEquals(0, joins)
            }
        }

    @Test
    fun insideTheWindow_theClickJoins_andTheLabelFollowsTheKind(): Promise<Unit> =
        formTest {
            mountedForm("event-online-button-open") { root, element ->
                var joins = 0
                root.eventOnlineJoinButton(conference(), now = { opens }) { joins++ }
                val button = element().joinButton()
                assertEquals("Teilnehmen", button.textContent.orEmpty().trim())
                assertEquals("false", button.getAttribute("aria-disabled"))
                button.click()
                assertEquals(1, joins)
            }
        }

    @Test
    fun theClockMovesTheButtonFromNotYetToOpen_andAnnouncesIt(): Promise<Unit> =
        formTest {
            mountedForm("event-online-button-tick") { root, element ->
                var wall = LocalDateTime(2026, 11, 1, 17, 0)
                root.eventOnlineJoinButton(conference(), now = { wall }, tickMs = 20) { }
                val button = element().joinButton()
                assertEquals("true", button.getAttribute("aria-disabled"))
                wall = opens
                awaitUntil("the button opens") { button.getAttribute("aria-disabled") == "false" }
                assertEquals("Teilnehmen", button.textContent.orEmpty().trim())
                assertTrue(
                    element()
                        .querySelector("[role='status']")
                        ?.textContent
                        .orEmpty()
                        .contains("Teilnehmen"),
                )
                wall = LocalDateTime(2026, 11, 2, 0, 30)
                awaitUntil("the button ends") { button.textContent.orEmpty().trim() == "Beendet" }
                assertEquals("true", button.getAttribute("aria-disabled"))
            }
        }
}
