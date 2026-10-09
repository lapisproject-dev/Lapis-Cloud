package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MailingPauseReason
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.81 -- the progress panel of a running mailing-list send: a static `<progress>` with the text beside it, the pause with its
 * reason, the "unclear" and "failed" counters, a 15 s poll (here: 20 ms) that ends by itself, and a timer that dies with the widget.
 */
class MailingSendProgressDomTest {
    private fun progress(
        pending: Int,
        total: Int = 10,
        interrupted: Int = 0,
        failed: Int = 0,
        pausedUntil: LocalDateTime? = null,
        reason: MailingPauseReason? = null,
    ) = MailingSendProgressDto(
        messageId = "m1",
        total = total,
        sent = total - pending - interrupted - failed,
        failed = failed,
        interrupted = interrupted,
        skipped = 0,
        pending = pending,
        pausedUntil = pausedUntil,
        pauseReason = reason,
        remainingSeconds = if (pending > 0) pending * 360L else null,
    )

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun Root.mount(panel: MailingSendProgressPanel) {
        addWithLifecycle(panel, onInsert = { panel.start() }, onDestroy = { panel.stop() })
    }

    @Test
    fun aRunningSend_showsHeadlineAndAStaticProgressBar_withoutAnimationOrFixedWidth(): Promise<Unit> =
        formTest {
            mountedForm("msp-bar") { root, element ->
                val panel = MailingSendProgressPanel("m1", onFinished = {}, pollIntervalMs = 20, fetch = { progress(pending = 4) })
                root.mount(panel)
                awaitUntil("headline rendered") { element().textContent.orEmpty().contains("Wird versendet: 6 von 10") }
                val bar = element().first("progress")
                assertEquals("10", bar.getAttribute("max"))
                assertEquals("6", bar.getAttribute("value"))
                assertTrue(element().textContent.orEmpty().contains("noch ca. 25 Min."), element().textContent)
                // R54 (no transition), R55 (no fixed width): the bar is a plain full-width element
                val style = kotlinx.browser.window.getComputedStyle(bar)
                assertTrue(style.transitionDuration.split(",").all { it.trim() == "0s" }, "no transition: ${style.transitionDuration}")
                assertFalse(bar.getAttribute("style").orEmpty().contains("width"), "no inline width")
                assertEquals(0, element().querySelectorAll(".progress-bar").length, "no animated Bootstrap bar")
            }
        }

    @Test
    fun aPause_namesTheReasonAndTheClockTime(): Promise<Unit> =
        formTest {
            mountedForm("msp-pause") { root, element ->
                val at = LocalDateTime(2031, 1, 1, 11, 30)
                val panel =
                    MailingSendProgressPanel(
                        "m1",
                        onFinished = {},
                        pollIntervalMs = 20,
                        fetch = { progress(pending = 3, pausedUntil = at, reason = MailingPauseReason.HOURLY_BUDGET) },
                    )
                root.mount(panel)
                awaitUntil("pause shown") { element().textContent.orEmpty().contains("(Stundenbudget)") }
                val text = element().textContent.orEmpty()
                assertTrue(Regex("""pausiert bis ca. \d\d:\d\d \(Stundenbudget\)""").containsMatchIn(text), text)
                assertFalse(text.contains("###"), "no tr() marker on screen")
            }
        }

    @Test
    fun unclearAndFailedRecipients_areCountedInTheirOwnLines(): Promise<Unit> =
        formTest {
            mountedForm("msp-problems") { root, element ->
                val panel =
                    MailingSendProgressPanel(
                        "m1",
                        onFinished = {},
                        pollIntervalMs = 20,
                        fetch = { progress(pending = 1, interrupted = 2, failed = 3) },
                    )
                root.mount(panel)
                awaitUntil("counters shown") { element().textContent.orEmpty().contains("2 unklar (Unterbrechung)") }
                assertTrue(element().textContent.orEmpty().contains("3 fehlgeschlagen"))
            }
        }

    @Test
    fun noPauseAndNoProblems_showNoExtraLines(): Promise<Unit> =
        formTest {
            mountedForm("msp-quiet") { root, element ->
                val panel = MailingSendProgressPanel("m1", onFinished = {}, pollIntervalMs = 20, fetch = { progress(pending = 5) })
                root.mount(panel)
                awaitUntil("headline rendered") { element().textContent.orEmpty().contains("Wird versendet") }
                val text = element().textContent.orEmpty()
                assertFalse(text.contains("pausiert"))
                assertFalse(text.contains("unklar"))
                assertFalse(text.contains("fehlgeschlagen"))
            }
        }

    @Test
    fun thePoll_repeats_andEndsByItselfWithOneFinishedCallback(): Promise<Unit> =
        formTest {
            mountedForm("msp-finish") { root, _ ->
                var calls = 0
                var finished = 0
                val panel =
                    MailingSendProgressPanel(
                        "m1",
                        onFinished = { finished++ },
                        pollIntervalMs = 20,
                        fetch = {
                            calls++
                            progress(pending = if (calls < 3) 5 else 0)
                        },
                    )
                root.mount(panel)
                awaitUntil("finished") { finished == 1 }
                val atFinish = calls
                kotlinx.coroutines.delay(150)
                assertEquals(3, atFinish)
                assertEquals(atFinish, calls, "no further poll after the send is done")
                assertEquals(1, finished)
            }
        }

    @Test
    fun aFailedPoll_isSkipped_andTheNextOneStillRuns(): Promise<Unit> =
        formTest {
            mountedForm("msp-skip") { root, element ->
                var calls = 0
                val panel =
                    MailingSendProgressPanel(
                        "m1",
                        onFinished = {},
                        pollIntervalMs = 20,
                        fetch = {
                            calls++
                            if (calls == 1) null else progress(pending = 2)
                        },
                    )
                root.mount(panel)
                awaitUntil("second poll rendered") { element().textContent.orEmpty().contains("Wird versendet: 8 von 10") }
                assertTrue(calls >= 2)
            }
        }

    @Test
    fun theTimerDiesWithTheWidget_noPollIsLeftBehindAfterNavigating(): Promise<Unit> =
        formTest {
            mountedForm("msp-destroy") { root, element ->
                var calls = 0
                val panel =
                    MailingSendProgressPanel("m1", onFinished = {}, pollIntervalMs = 20, fetch = {
                        calls++
                        progress(pending = 5)
                    })
                root.mount(panel)
                awaitUntil("polling") { calls >= 2 }
                root.removeAll()
                awaitUntil("destroy hook ran") { element().querySelector("progress") == null }
                kotlinx.coroutines.delay(60)
                val afterRemoval = calls
                kotlinx.coroutines.delay(200)
                assertEquals(afterRemoval, calls, "the poll stopped when the panel left the document")
            }
        }
}
