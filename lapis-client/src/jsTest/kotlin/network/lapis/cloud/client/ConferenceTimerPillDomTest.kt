package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.133 -- the timer display above the tiles: digits, ring and WORDS for every phase (never colour alone), a button only for the
 * moderation, the pulse only without a reduced-motion request, an expired timer that stays put and never shows a negative time, and a live
 * region of its own that is not part of the timer element.
 */
class ConferenceTimerPillDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun running(
        remaining: Long,
        initial: Long = 300_000,
    ) = TimerDisplay(ConferenceTimerStatus.RUNNING, remaining, initial, TimerPhase.NORMAL)

    private fun mounted(
        pill: ConferenceTimerPill,
        block: (HTMLElement) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        host.appendChild(pill.element)
        try {
            block(pill.element)
        } finally {
            host.remove()
        }
    }

    private fun HTMLElement.text(selector: String): String = (querySelector(selector) as HTMLElement).textContent.orEmpty().trim()

    @Test
    fun anOffTimer_isHidden() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            assertEquals("none", pill.timerElement.style.display)
            assertEquals("none", element.style.display)
        }
    }

    @Test
    fun theCountdown_showsRoundedUpDigitsAnAccessibleNameAndNoStateWord() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            pill.render(running(135_000))
            assertEquals("02:15", element.text(".lapis-conference-timer-digits"))
            assertEquals("Timer: noch 2 Minuten 15 Sekunden", pill.timerElement.getAttribute("aria-label"))
            assertEquals("DIV", pill.timerElement.tagName)
            assertEquals("timer", pill.timerElement.getAttribute("role"))
            assertEquals("none", (element.querySelector(".lapis-conference-timer-state") as HTMLElement).style.display)
            assertFalse(pill.timerElement.classList.contains("is-final"))
            // a millisecond left still reads 00:01, never 00:00
            pill.render(running(1))
            assertEquals("00:01", element.text(".lapis-conference-timer-digits"))
        }
    }

    @Test
    fun theRing_followsTheRemainingShare() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            val progress = assertNotNull(element.querySelector(".lapis-conference-timer-ring-progress"))
            pill.render(running(150_000, initial = 300_000))
            val half = progress.getAttribute("stroke-dashoffset")!!.toDouble()
            pill.render(running(75_000, initial = 300_000))
            val quarter = progress.getAttribute("stroke-dashoffset")!!.toDouble()
            assertTrue(quarter > half, "less time left: more of the ring is empty")
            pill.render(running(300_000, initial = 300_000))
            assertEquals(0.0, progress.getAttribute("stroke-dashoffset")!!.toDouble(), 1e-9)
            assertEquals("true", assertNotNull(element.querySelector("svg")).getAttribute("aria-hidden"))
        }
    }

    @Test
    fun theFinalPhase_hasAThickerOutlineAndTheWords_notJustAColour() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            pill.render(TimerDisplay(ConferenceTimerStatus.RUNNING, 8_000, 60_000, TimerPhase.FINAL))
            assertTrue(pill.timerElement.classList.contains("is-final"))
            assertEquals("Gleich vorbei", element.text(".lapis-conference-timer-state"))
            assertEquals("", (element.querySelector(".lapis-conference-timer-state") as HTMLElement).style.display)
            assertEquals("3px", document.defaultView!!.getComputedStyle(pill.timerElement).borderTopWidth, "the outline is 3 px")
            assertTrue(pill.timerElement.classList.contains("is-pulsing"), "pulses when nobody asked for less motion")
            assertEquals(
                "lapis-conference-timer-pulse",
                document.defaultView!!.getComputedStyle(pill.timerElement).animationName,
                "the 1 Hz pulse is defined for the no-preference case",
            )
        }
    }

    @Test
    fun withAReducedMotionRequest_thereIsNoPulse() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { true })
        mounted(pill) { _ ->
            pill.render(TimerDisplay(ConferenceTimerStatus.RUNNING, 8_000, 60_000, TimerPhase.FINAL))
            assertTrue(pill.timerElement.classList.contains("is-final"), "the words and the outline stay")
            assertFalse(pill.timerElement.classList.contains("is-pulsing"), "no pulse")
            assertEquals(
                "none",
                document.defaultView!!
                    .getComputedStyle(pill.timerElement)
                    .animationName
                    .ifEmpty { "none" },
            )
        }
    }

    @Test
    fun anExpiredTimer_standsStill_saysSo_andNeverShowsANegativeTime() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            pill.render(TimerDisplay(ConferenceTimerStatus.EXPIRED, 0, 60_000, TimerPhase.EXPIRED))
            assertEquals("00:00", element.text(".lapis-conference-timer-digits"))
            assertEquals("Zeit abgelaufen", element.text(".lapis-conference-timer-state"))
            assertTrue(pill.timerElement.classList.contains("is-expired"))
            assertFalse(pill.timerElement.classList.contains("is-pulsing"))
            assertFalse(element.textContent.orEmpty().contains("-"), "no minus sign anywhere")
            assertEquals("Timer: Zeit abgelaufen", pill.timerElement.getAttribute("aria-label"))
        }
    }

    @Test
    fun aPausedTimer_saysPausiert() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            pill.render(TimerDisplay(ConferenceTimerStatus.PAUSED, 45_000, 60_000, TimerPhase.NORMAL))
            assertEquals("00:45", element.text(".lapis-conference-timer-digits"))
            assertEquals("Pausiert", element.text(".lapis-conference-timer-state"))
            assertTrue(pill.timerElement.classList.contains("is-paused"))
            assertEquals("Timer pausiert: noch 45 Sekunden", pill.timerElement.getAttribute("aria-label"))
        }
    }

    @Test
    fun forTheModeration_theTimerIsAButton_thatOpensTheControls_andForEverybodyElseItIsNot() {
        var activated = 0
        val moderator = ConferenceTimerPill(isModerator = true, onActivate = { activated++ }, reducedMotion = { false })
        mounted(moderator) { element ->
            moderator.render(running(135_000))
            assertEquals("BUTTON", moderator.timerElement.tagName)
            assertEquals("button", moderator.timerElement.getAttribute("type"))
            assertEquals("Timer steuern, noch 2 Minuten 15 Sekunden", moderator.timerElement.getAttribute("aria-label"))
            assertNotNull(element.querySelector("[role=timer]"), "the digits carry the timer role inside the button")
            moderator.timerElement.click()
            assertEquals(1, activated)
        }
        val participant = ConferenceTimerPill(isModerator = false, onActivate = { activated++ }, reducedMotion = { false })
        mounted(participant) { element ->
            participant.render(running(135_000))
            assertEquals("DIV", participant.timerElement.tagName)
            assertNull(element.querySelector("button"), "nothing to press")
            assertNotNull(element.querySelector("[role=timer]"))
            participant.timerElement.click()
            assertEquals(1, activated)
        }
    }

    @Test
    fun theElementsAreBuiltOnce_andOnlyFilledAfterwards() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            pill.render(running(90_000))
            val digits = element.querySelector(".lapis-conference-timer-digits")
            val ring = element.querySelector(".lapis-conference-timer-ring")
            repeat(5) { index -> pill.render(running(90_000 - index * 1_000L)) }
            assertSame(digits, element.querySelector(".lapis-conference-timer-digits"))
            assertSame(ring, element.querySelector(".lapis-conference-timer-ring"))
        }
    }

    @Test
    fun theResetNote_showsWithoutATimer() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            pill.render(TimerDisplay.OFF.copy(resetNotice = true))
            assertEquals("", element.style.display)
            assertEquals("none", pill.timerElement.style.display)
            assertEquals("Timer wurde zurückgesetzt", element.text(".lapis-conference-timer-note"))
            pill.render(TimerDisplay.OFF)
            assertEquals("none", element.style.display)
        }
    }

    @Test
    fun theLiveRegion_isPoliteAndOutsideTheTimerElement() {
        val pill = ConferenceTimerPill(isModerator = false, onActivate = {}, reducedMotion = { false })
        mounted(pill) { element ->
            val live = assertNotNull(element.querySelector("[aria-live]") as? HTMLElement)
            assertEquals("polite", live.getAttribute("aria-live"))
            assertFalse(pill.timerElement.contains(live), "the countdown itself is never a live region")
            assertTrue(live.classList.contains("visually-hidden"))
            pill.announce("Noch 10 Sekunden")
            assertEquals("Noch 10 Sekunden", pill.liveText)
            assertNull(pill.timerElement.getAttribute("aria-live"))
        }
    }
}
