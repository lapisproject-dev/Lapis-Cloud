package network.lapis.cloud.client

import io.kvision.panel.simplePanel
import network.lapis.cloud.shared.domain.EventCaptureConsentCountDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun HTMLElement.text(): String = textContent.orEmpty()

/**
 * V1.9.280 -- the moderator's line "Zustimmung zur Aufzeichnung: N von M" ([EventCaptureModeratorCount]): numbers only, the check mark and the full
 * sentence once everybody agreed, a SEPARATE polite live region that is fed at most once per 1500 ms (the latest text wins), a reason for the start
 * buttons while not everybody agreed, and nothing left behind after `dispose` (timer cleared, both regions removed).
 */
class EventCaptureModeratorCountDomTest {
    private class FakeClock {
        var now = 0.0
        var next = 1
        val timers = HashMap<Int, Pair<Int, () -> Unit>>()
        val cleared = mutableListOf<Int>()

        fun set(
            delayMs: Int,
            action: () -> Unit,
        ): Int {
            val id = next++
            timers[id] = delayMs to action
            return id
        }

        fun clear(id: Int) {
            cleared += id
            timers.remove(id)
        }

        /** Lets [ms] pass and fires the timers that are due. */
        fun advance(ms: Int) {
            now += ms
            timers.entries.toList().filter { it.value.first <= ms }.forEach { (id, t) ->
                timers.remove(id)
                t.second()
            }
        }
    }

    private fun HTMLElement.live() = assertNotNull(querySelector("[role=status][aria-live=polite].visually-hidden") as? HTMLElement)

    @Test
    fun theWords_theCheckMark_andTheStartReason(): Promise<Unit> =
        formTest {
            mountedForm("moderator-count-words") { root, element ->
                val clock = FakeClock()
                val counter =
                    EventCaptureModeratorCount(root.simplePanel(), nowMs = { clock.now }, setTimer = clock::set, clearTimer = clock::clear)
                assertEquals("", counter.shownText, "hidden until the server sent a number")
                assertNull(eventCaptureStartBlockedReason(counter.current))
                counter.render(EventCaptureConsentCountDto(acknowledged = 2, total = 5))
                assertEquals("Zustimmung zur Aufzeichnung: 2 von 5", counter.shownText)
                assertFalse(counter.allAgreed)
                assertEquals(null, element().querySelector(".lapis-conference-consent-count-icon.fa-circle-check"))
                assertEquals("Noch nicht alle im Raum haben zugestimmt (2 von 5).", eventCaptureStartBlockedReason(counter.current))
                counter.render(EventCaptureConsentCountDto(acknowledged = 5, total = 5))
                assertTrue(counter.allAgreed)
                assertTrue(counter.shownText.contains("5 von 5"))
                assertTrue(counter.shownText.contains("Alle im Raum haben zugestimmt."))
                assertNotNull(element().querySelector(".lapis-conference-consent-count-icon.fa-circle-check"), "the check mark")
                assertNull(eventCaptureStartBlockedReason(counter.current), "everybody agreed: the start is no longer blocked")
                // nothing names anybody
                assertFalse(element().text().contains("abgelehnt"))
                counter.dispose()
            }
        }

    @Test
    fun announcements_areBundled_atMostOnePer1500ms_andTheLatestTextWins(): Promise<Unit> =
        formTest {
            mountedForm("moderator-count-announce") { root, element ->
                val clock = FakeClock()
                val counter =
                    EventCaptureModeratorCount(root.simplePanel(), nowMs = { clock.now }, setTimer = clock::set, clearTimer = clock::clear)
                counter.render(EventCaptureConsentCountDto(1, 10))
                assertEquals("Zustimmung zur Aufzeichnung: 1 von 10", counter.announcedText, "the first one is announced at once")
                // a burst within the quiet period: nothing new is announced yet, one timer is pending
                clock.advance(100)
                counter.render(EventCaptureConsentCountDto(2, 10))
                clock.advance(100)
                counter.render(EventCaptureConsentCountDto(3, 10))
                clock.advance(100)
                counter.render(EventCaptureConsentCountDto(4, 10))
                assertEquals("Zustimmung zur Aufzeichnung: 1 von 10", counter.announcedText)
                assertEquals(1, clock.timers.size, "exactly one pending announcement")
                // the visible line already shows the latest number
                assertEquals("Zustimmung zur Aufzeichnung: 4 von 10", counter.shownText)
                // the period is over: ONE announcement with the latest text
                clock.advance(1500)
                assertEquals("Zustimmung zur Aufzeichnung: 4 von 10", counter.announcedText)
                assertEquals(0, clock.timers.size)
                // ... and the live region (a visually hidden polite status) carries it
                awaitUntil("the live region shows the latest text") { element().live().text() == "Zustimmung zur Aufzeichnung: 4 von 10" }
                counter.dispose()
            }
        }

    @Test
    fun dispose_clearsThePendingTimer_andRemovesBothRegions(): Promise<Unit> =
        formTest {
            mountedForm("moderator-count-dispose") { root, element ->
                val clock = FakeClock()
                val counter =
                    EventCaptureModeratorCount(root.simplePanel(), nowMs = { clock.now }, setTimer = clock::set, clearTimer = clock::clear)
                counter.render(EventCaptureConsentCountDto(1, 3))
                counter.render(EventCaptureConsentCountDto(2, 3))
                assertEquals(1, clock.timers.size)
                counter.dispose()
                assertEquals(1, clock.cleared.size, "the pending timer was cleared")
                assertEquals(0, clock.timers.size)
                awaitUntil("both regions are gone") {
                    element().querySelector(".lapis-conference-consent-count") == null &&
                        element().querySelector("[role=status].visually-hidden") == null
                }
                // a late packet after the call ended renders nothing
                counter.render(EventCaptureConsentCountDto(3, 3))
                assertNull(element().querySelector(".lapis-conference-consent-count"))
                counter.dispose()
            }
        }

    @Test
    fun theTextOfTheLiveRegion_isTheNumbersOnly(): Promise<Unit> =
        formTest {
            mountedForm("moderator-count-privacy") { root, element ->
                val clock = FakeClock()
                val counter =
                    EventCaptureModeratorCount(root.simplePanel(), nowMs = { clock.now }, setTimer = clock::set, clearTimer = clock::clear)
                counter.render(EventCaptureConsentCountDto(0, 0))
                assertTrue(Regex("""^Zustimmung zur Aufzeichnung: \d+ von \d+$""").matches(counter.announcedText))
                awaitUntil("the live region") { element().live().text() == counter.announcedText }
                counter.dispose()
            }
        }
}
