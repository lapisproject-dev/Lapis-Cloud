package network.lapis.cloud.client

import io.kvision.panel.vPanel
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.133 -- the timer in the mini bar and the floating window: digits and phase only, words for the phases, a 2 px bar, hidden without a timer. */
class ConferenceTimerDockChipTest {
    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = true,
            screenSharing = false,
            recording = false,
            streaming = false,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    private fun running(remaining: Long) = TimerDisplay(ConferenceTimerStatus.RUNNING, remaining, 300_000, TimerPhase.NORMAL)

    @Test
    fun theDocksViewOfTheTimer_isNullWithoutATimer_andCarriesTheDigitsAndThePhase() {
        assertNull(dockTimerOf(TimerDisplay.OFF))
        assertEquals(DockTimer("02:15", final = false, expired = false, paused = false, fraction = 0.45), dockTimerOf(running(135_000)))
        assertTrue(assertNotNull(dockTimerOf(TimerDisplay(ConferenceTimerStatus.RUNNING, 7_000, 60_000, TimerPhase.FINAL))).final)
        val expired = assertNotNull(dockTimerOf(TimerDisplay(ConferenceTimerStatus.EXPIRED, 0, 60_000, TimerPhase.EXPIRED)))
        assertTrue(expired.expired)
        assertEquals("00:00", expired.text)
        assertTrue(assertNotNull(dockTimerOf(TimerDisplay(ConferenceTimerStatus.PAUSED, 30_000, 60_000, TimerPhase.NORMAL))).paused)
    }

    @Test
    fun theBarAndTheWindow_showTheTimerOnlyWhileTheCallIsLive() {
        val timer = dockTimerOf(running(135_000))
        val live = DockState.Live(attached = false, snapshot = snap.copy(timer = timer))
        assertEquals(timer, dockBarViewOf(live, narrow = false).timer)
        assertEquals(timer, floatWindowViewOf(live).timer)
        val joining = DockState.Joining(attached = false)
        assertNull(dockBarViewOf(joining, narrow = false).timer)
        val resolving = DockState.Resolving(attached = false, snapshot = snap.copy(timer = timer))
        assertNull(floatWindowViewOf(resolving).timer, "while the connection is being switched there is nothing to show")
        assertNull(dockBarViewOf(DockState.Live(attached = false, snapshot = snap), narrow = false).timer)
    }

    @Test
    fun aChangedTimer_isAChangedSnapshot_soTheDockRepaints() {
        assertFalse(snap == snap.copy(timer = dockTimerOf(running(135_000))))
        assertEquals(snap.copy(timer = dockTimerOf(running(135_000))), snap.copy(timer = dockTimerOf(running(135_000))))
    }

    @Test
    fun theChip_saysTheTimeInWords_theWarnSignIsDecorative_andItHidesWithoutATimer() {
        withMountedRoot("timer-dock-chip") { root, element ->
            val chip = ConferenceTimerDockChip(root.vPanel())

            // resolved on every use: the first patch of the mounted root replaces its container element
            fun host() = element()

            fun el(selector: String): HTMLElement =
                assertNotNull(host().querySelector(selector), "$selector in ${host().outerHTML}") as HTMLElement

            fun text(selector: String) =
                (
                    assertNotNull(
                        host().querySelector(selector),
                        "$selector in ${host().outerHTML}",
                    ) as HTMLElement
                ).textContent.orEmpty().trim()
            chip.render(dockTimerOf(running(135_000)))
            assertEquals("Timer 02:15", text(".lapis-dock-timer-text"))
            assertEquals("", text(".lapis-dock-timer-words"))
            assertNull(
                host().querySelector(".lapis-dock-timer-warn"),
                "no warn sign before the last 10 s (a hidden widget is not rendered)",
            )
            chip.render(dockTimerOf(TimerDisplay(ConferenceTimerStatus.RUNNING, 7_000, 60_000, TimerPhase.FINAL)))
            assertEquals("Timer 00:07", text(".lapis-dock-timer-text"))
            assertEquals("Gleich vorbei", text(".lapis-dock-timer-words"))
            val warn = el(".lapis-dock-timer-warn")
            assertEquals("⚠", warn.textContent)
            assertEquals("true", warn.getAttribute("aria-hidden"))
            assertTrue(host().querySelector(".lapis-dock-timer")!!.classList.contains("is-final"))
            chip.render(dockTimerOf(TimerDisplay(ConferenceTimerStatus.EXPIRED, 0, 60_000, TimerPhase.EXPIRED)))
            assertEquals("Zeit abgelaufen", text(".lapis-dock-timer-text"))
            chip.render(dockTimerOf(TimerDisplay(ConferenceTimerStatus.PAUSED, 30_000, 60_000, TimerPhase.NORMAL)))
            assertEquals("Pausiert", text(".lapis-dock-timer-words"))
            chip.render(null)
            assertNull(host().querySelector(".lapis-dock-timer"), "hidden without a timer")
        }
    }

    @Test
    fun theBar_followsTheRemainingShare() {
        withMountedRoot("timer-dock-chip-bar") { root, element ->
            val chip = ConferenceTimerDockChip(root.vPanel())
            chip.render(dockTimerOf(running(150_000)))
            val fill = element().querySelector(".lapis-dock-timer-bar-fill") as HTMLElement
            assertTrue(fill.getAttribute("style").orEmpty().contains("width: 50%"), "style=${fill.getAttribute("style")}")
            chip.render(dockTimerOf(running(30_000)))
            assertTrue(fill.getAttribute("style").orEmpty().contains("width: 10%"))
        }
    }
}
