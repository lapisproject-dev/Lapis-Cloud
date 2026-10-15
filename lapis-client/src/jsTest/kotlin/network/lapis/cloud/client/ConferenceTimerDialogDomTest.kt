package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.133 -- the moderator's control dialog: presets with a default of five minutes, "Eigene Zeit" with an inline message and a "Starten" that
 * stays off while the input is invalid, extension buttons that are off when the remaining time would pass 60 minutes (with names that say what
 * they do), Escape closes, and the focus goes back to the control that opened it.
 */
class ConferenceTimerDialogDomTest {
    private companion object {
        const val EPOCH = "0123456789abcdef"

        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class FakeGateway : ConferenceTimerGateway {
        val calls = mutableListOf<String>()
        var next: ConferenceTimerDto? = null

        override suspend fun start(seconds: Int): ConferenceTimerDto? = next.also { calls += "start:$seconds" }

        override suspend fun pause(): ConferenceTimerDto? = next.also { calls += "pause" }

        override suspend fun resume(): ConferenceTimerDto? = next.also { calls += "resume" }

        override suspend fun stop(): ConferenceTimerDto? = next.also { calls += "stop" }

        override suspend fun extend(seconds: Int): ConferenceTimerDto? = next.also { calls += "extend:$seconds" }

        override suspend fun state(): ConferenceTimerDto? = next
    }

    private class Rig {
        val gateway = FakeGateway()
        var now = 1_000.0
        lateinit var dialog: ConferenceTimerDialog
        val trigger: HTMLButtonElement = (document.createElement("button") as HTMLButtonElement).also { it.textContent = "Timer" }
        val controller: ConferenceTimerController =
            ConferenceTimerController(
                gateway = gateway,
                scope = CoroutineScope(SupervisorJob()),
                perfNow = { now },
                onChanged = { if (::dialog.isInitialized) dialog.refresh() },
                announce = {},
                gong = {},
                startTicker = { _, _ -> ({}) },
                isModerator = true,
                isEventGuest = false,
            )

        init {
            document.body!!.appendChild(trigger)
            dialog = ConferenceTimerDialog(controller, CoroutineScope(SupervisorJob())) { trigger }
        }

        fun dto(
            seq: Long,
            status: ConferenceTimerStatus,
            initial: Long,
            remaining: Long,
        ) = ConferenceTimerDto(EPOCH, seq, status, initial, remaining, now.toLong() + 5_000_000)

        fun dispose() {
            trigger.remove()
        }
    }

    private fun HTMLElement.input(selector: String) = assertNotNull(querySelector(selector) as? HTMLInputElement, "no $selector")

    private fun HTMLElement.typeInto(
        selector: String,
        value: String,
    ) {
        val field = input(selector)
        field.value = value
        field.dispatchEvent(Event("input", EventInit(bubbles = true)))
    }

    private suspend fun shown(rig: Rig): HTMLElement {
        assertTrue(stylesLoaded)
        rig.dialog.open()
        awaitUntil("the dialog is shown") { document.querySelector(".modal.show .lapis-timer-dialog") != null }
        return lastOpenModal()
    }

    @Test
    fun anOffTimer_offersFivePresets_withFiveMinutesChosen_andOwnTime(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-presets") { _, _ ->
                val rig = Rig()
                try {
                    val modal = shown(rig)
                    assertEquals("Timer", modal.querySelector(".modal-title")?.textContent?.trim())
                    val labels = modal.allOf(".form-check-label").map { it.textContent.orEmpty().trim() }
                    assertEquals(listOf("1 Minute", "2 Minuten", "3 Minuten", "5 Minuten", "10 Minuten", "Eigene Zeit"), labels)
                    awaitUntil("five minutes is chosen") {
                        (modal.querySelectorAll("input[type=radio]").item(3) as HTMLInputElement).checked
                    }
                    assertEquals("Starten", modal.querySelector(".lapis-timer-start")?.textContent?.trim())
                    assertFalse((modal.querySelector(".lapis-timer-start") as HTMLButtonElement).disabled)
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun startingAPreset_sendsTheSecondsAndClosesTheDialog(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-start") { _, _ ->
                val rig = Rig()
                try {
                    val modal = shown(rig)
                    rig.gateway.next = rig.dto(1, ConferenceTimerStatus.RUNNING, 300_000, 300_000)
                    (modal.querySelector(".lapis-timer-start") as HTMLButtonElement).click()
                    awaitUntil("the call went out") { rig.gateway.calls == listOf("start:300") }
                    awaitUntil("the dialog closed") { document.querySelector(".modal.show") == null }
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun anOwnTimeUnderTenSeconds_disablesStarten_andShowsTheMessageTiedToTheFields(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-custom") { _, _ ->
                val rig = Rig()
                try {
                    val modal = shown(rig)
                    val start = modal.querySelector(".lapis-timer-start") as HTMLButtonElement
                    modal.typeInto(".lapis-timer-custom-min", "0")
                    modal.typeInto(".lapis-timer-custom-sec", "9")
                    awaitUntil("Starten is off") { start.disabled }
                    val error = assertNotNull(modal.querySelector(".lapis-timer-error") as? HTMLElement)
                    assertEquals("Zwischen 10 Sekunden und 60 Minuten", error.textContent?.trim())
                    assertTrue(error.getBoundingClientRect().height > 0, "the message is visible")
                    val describedBy = modal.input(".lapis-timer-custom-sec").getAttribute("aria-describedby")
                    assertEquals(error.id, describedBy, "the message is tied to the field")
                    assertTrue(
                        (modal.querySelectorAll("input[type=radio]").item(5) as HTMLInputElement).checked,
                        "typing chose 'Eigene Zeit'",
                    )
                    // a valid time switches Starten on and hides the message
                    modal.typeInto(".lapis-timer-custom-sec", "10")
                    awaitUntil("Starten is on") { !start.disabled }
                    awaitUntil("the message is gone") { error.getBoundingClientRect().height == 0.0 }
                    rig.gateway.next = rig.dto(1, ConferenceTimerStatus.RUNNING, 10_000, 10_000)
                    start.click()
                    awaitUntil("the own time was sent") { rig.gateway.calls == listOf("start:10") }
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun aRunningTimer_offersPauseExtensionsAndStop_andAnExtensionIsOffWhenItWouldPassAnHour(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-running") { _, _ ->
                val rig = Rig()
                try {
                    rig.controller.onPacket(rig.dto(1, ConferenceTimerStatus.RUNNING, 3_560_000, 3_560_000))
                    val modal = shown(rig)
                    assertNotNull(modal.querySelector(".lapis-timer-pause"))
                    assertNull(modal.querySelector(".lapis-timer-start"))
                    val e30 = modal.querySelector(".lapis-timer-extend-30") as HTMLButtonElement
                    val e60 = modal.querySelector(".lapis-timer-extend-60") as HTMLButtonElement
                    val e300 = modal.querySelector(".lapis-timer-extend-300") as HTMLButtonElement
                    assertEquals("+30 s", e30.textContent?.trim())
                    assertEquals("+1 min", e60.textContent?.trim())
                    assertEquals("+5 min", e300.textContent?.trim())
                    assertEquals("Um 30 Sekunden verlängern", e30.getAttribute("aria-label"))
                    assertEquals("Um 1 Minute verlängern", e60.getAttribute("aria-label"))
                    assertEquals("Um 5 Minuten verlängern", e300.getAttribute("aria-label"))
                    assertFalse(e30.disabled, "59:20 + 30 s is under an hour")
                    assertTrue(e60.disabled, "59:20 + 1 min is over an hour")
                    assertTrue(e300.disabled)
                    // as time passes, the buttons come back (updated in place, no rebuild)
                    rig.now += 100_000
                    rig.controller.tick()
                    rig.dialog.refresh()
                    assertFalse(e60.disabled)
                    assertEquals("Stoppen", modal.querySelector(".lapis-timer-stop")?.textContent?.trim())
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun pausing_swapsTheButton_andAPausedTimerOffersFortsetzen(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-pause") { _, _ ->
                val rig = Rig()
                try {
                    rig.controller.onPacket(rig.dto(1, ConferenceTimerStatus.RUNNING, 600_000, 600_000))
                    val modal = shown(rig)
                    rig.gateway.next = rig.dto(2, ConferenceTimerStatus.PAUSED, 600_000, 600_000)
                    (modal.querySelector(".lapis-timer-pause") as HTMLButtonElement).click()
                    awaitUntil("the pause call went out") { rig.gateway.calls == listOf("pause") }
                    awaitUntil("the button became Fortsetzen") { modal.querySelector(".lapis-timer-resume") != null }
                    assertNull(modal.querySelector(".lapis-timer-pause"))
                    assertEquals("Fortsetzen", modal.querySelector(".lapis-timer-resume")?.textContent?.trim())
                    assertTrue(modal.textContent.orEmpty().contains("Timer pausiert: noch 10:00"))
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun escape_closes_andTheFocusReturnsToTheControlThatOpenedIt(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-escape") { _, _ ->
                val rig = Rig()
                try {
                    rig.trigger.focus()
                    val modal = shown(rig)
                    assertTrue(rig.dialog.isOpen)
                    modal.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", cancelable = true, bubbles = true)))
                    awaitUntil("Escape closed the dialog") { !rig.dialog.isOpen }
                    awaitUntil("the focus is back on the trigger") { document.activeElement === rig.trigger }
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun anExpiredTimer_offersAFreshStartAndExtensions(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-expired") { _, _ ->
                val rig = Rig()
                try {
                    rig.controller.onPacket(rig.dto(1, ConferenceTimerStatus.EXPIRED, 60_000, 0))
                    val modal = shown(rig)
                    assertNotNull(modal.querySelector(".lapis-timer-start"))
                    assertTrue(modal.textContent.orEmpty().contains("Zeit abgelaufen"))
                    assertNotNull(modal.querySelector(".lapis-timer-extend-60"))
                    assertFalse((modal.querySelector(".lapis-timer-extend-300") as HTMLButtonElement).disabled)
                    assertEquals(
                        TimerDialogMode.START,
                        timerDialogMode(TimerDisplay(ConferenceTimerStatus.EXPIRED, 0, 60_000, TimerPhase.EXPIRED)),
                    )
                } finally {
                    rig.dispose()
                }
            }
        }

    @Test
    fun theDialogMode_followsTheStatus() {
        assertEquals(TimerDialogMode.START, timerDialogMode(TimerDisplay.OFF))
        assertEquals(TimerDialogMode.RUNNING, timerDialogMode(TimerDisplay(ConferenceTimerStatus.RUNNING, 5_000, 60_000, TimerPhase.FINAL)))
        assertEquals(TimerDialogMode.START, timerDialogMode(TimerDisplay(ConferenceTimerStatus.RUNNING, 0, 60_000, TimerPhase.EXPIRED)))
        assertEquals(TimerDialogMode.PAUSED, timerDialogMode(TimerDisplay(ConferenceTimerStatus.PAUSED, 5_000, 60_000, TimerPhase.NORMAL)))
    }

    @Test
    fun nothingOpensWithoutAnOpenCall(): Promise<Unit> =
        formTest {
            mountedForm("timer-dialog-closed") { _, _ ->
                val rig = Rig()
                try {
                    delay(50)
                    assertFalse(rig.dialog.isOpen)
                    assertNull(document.querySelector(".lapis-timer-dialog"))
                } finally {
                    rig.dispose()
                }
            }
        }
}
