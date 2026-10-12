package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeJoinLeavePreference(
    var on: Boolean = true,
    private val throwing: Boolean = false,
) : JoinLeaveSoundPreference {
    val writes = mutableListOf<Boolean>()

    override fun isOn(): Boolean = on

    override fun setOn(on: Boolean) {
        writes += on
        this.on = on
        if (throwing) error("storage blocked")
    }
}

private class FakeJoinLeavePlayer : JoinLeaveSoundPlayer {
    var unlocks = 0
    var probes = 0
    var joins = 0
    var leaves = 0
    val sinks = mutableListOf<String?>()

    override fun unlock() {
        unlocks++
    }

    override fun playJoin() {
        joins++
    }

    override fun playLeave() {
        leaves++
    }

    override fun playProbe() {
        probes++
    }

    override fun setSink(deviceId: String?) {
        sinks += deviceId
    }

    override fun dispose() {}
}

/**
 * V1.9.120 -- the personal switch of the join/leave tones in a REAL mounted root: a `role="switch"` button, on by default, a hint that is always
 * visible (with the limit taken from the constant), switching on unlocks and plays the join tone once, a room option that is off disables the
 * switch (visible, with the reason) and nothing is ever announced.
 */
class JoinLeaveSoundSwitchDomTest {
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

    private fun HTMLElement.switchButton() = assertNotNull(querySelector("button[role=switch]") as? HTMLElement)

    private fun mount(
        preference: JoinLeaveSoundPreference,
        player: JoinLeaveSoundPlayer,
        roomOn: () -> Boolean = { true },
        block: (HTMLElement, JoinLeaveSoundSwitchRow) -> Unit,
    ) {
        withMountedRoot("join-leave-sound-switch-test") { root, element ->
            assertTrue(stylesLoaded)
            val row = JoinLeaveSoundSwitchRow(root, preference, player, roomOn)
            block(element(), row)
        }
    }

    @Test
    fun byDefault_theSwitchIsOn_withALabelAndAnAlwaysVisibleHint_andNoLiveRegion() {
        mount(FakeJoinLeavePreference(), FakeJoinLeavePlayer()) { element, _ ->
            val button = element.switchButton()
            assertEquals("button", button.tagName.lowercase())
            assertEquals("button", button.getAttribute("type"))
            assertEquals("true", button.getAttribute("aria-checked"))
            assertEquals("false", button.getAttribute("aria-disabled"))
            val label = assertNotNull(element.querySelector("#${assertNotNull(button.getAttribute("aria-labelledby"))}"))
            assertEquals("Töne bei Beitritt und Verlassen", label.textContent.orEmpty().trim())
            val hint = assertNotNull(element.querySelector("#${assertNotNull(button.getAttribute("aria-describedby"))}") as? HTMLElement)
            assertEquals(
                "Ein leiser Ton, wenn jemand beitritt oder den Raum verlässt. Bei mehr als $JOIN_LEAVE_SOUND_MAX_PARTICIPANTS Personen im Raum bleibt es still.",
                hint.textContent.orEmpty().trim(),
            )
            assertTrue(hint.getBoundingClientRect().height > 0, "the hint is always visible")
            assertNull(element.querySelector("[aria-live]"), "nothing is announced")
            assertNull(element.querySelector("[role=status]"), "no live region")
        }
    }

    @Test
    fun switchingOff_writesOff_andPlaysNothing_andSwitchingOnUnlocksAndPlaysTheProbeOnce(): Promise<Unit> =
        formTest {
            val preference = FakeJoinLeavePreference()
            val player = FakeJoinLeavePlayer()
            mountedForm("join-leave-sound-switch-click") { root, element ->
                val row = JoinLeaveSoundSwitchRow(root, preference, player) { true }
                val button = element().switchButton()
                button.click()
                assertEquals(listOf(false), preference.writes, "the click handler ran")
                awaitUntil("the switch is off") { button.getAttribute("aria-checked") == "false" }
                assertEquals(listOf(false), preference.writes)
                assertEquals(0, player.probes)
                assertEquals(0, player.unlocks)
                button.click()
                awaitUntil("the switch is on again") { button.getAttribute("aria-checked") == "true" }
                assertEquals(listOf(false, true), preference.writes)
                assertEquals(1, player.unlocks, "the context is created inside the click")
                assertEquals(1, player.probes, "the join tone sounds once as the confirmation")
            }
        }

    @Test
    fun aThrowingStorage_neverBreaksTheSwitch_theChoiceStaysInMemory(): Promise<Unit> =
        formTest {
            val preference = FakeJoinLeavePreference(throwing = true)
            mountedForm("join-leave-sound-switch-throwing") { root, element ->
                JoinLeaveSoundSwitchRow(root, preference, FakeJoinLeavePlayer()) { true }
                val button = element().switchButton()
                button.click()
                awaitUntil("the switch is off although the storage threw") { button.getAttribute("aria-checked") == "false" }
                assertFalse(preference.on)
            }
        }

    @Test
    fun withTheRoomOptionOff_theSwitchIsDisabledAndSaysWhy_andAClickDoesNothing(): Promise<Unit> =
        formTest {
            var roomOn = false
            val preference = FakeJoinLeavePreference()
            val player = FakeJoinLeavePlayer()
            mountedForm("join-leave-sound-switch-room-off") { root, element ->
                val row = JoinLeaveSoundSwitchRow(root, preference, player) { roomOn }
                val button = element().switchButton()
                assertEquals("true", button.getAttribute("aria-disabled"))
                assertEquals("false", button.getAttribute("aria-checked"))
                assertTrue(
                    element().textContent.orEmpty().contains("Die Moderation hat die Töne für diesen Raum ausgeschaltet."),
                    "the reason is visible text",
                )
                element().switchButton().click()
                kotlinx.coroutines.delay(50)
                assertTrue(preference.writes.isEmpty())
                assertEquals(0, player.probes)
                // the moderation switches the tones on again: the row follows without a rebuild
                roomOn = true
                row.refresh()
                awaitUntil("the switch is enabled and on again") {
                    button.getAttribute("aria-disabled") == "false" && button.getAttribute("aria-checked") == "true"
                }
                assertFalse(row.roomOffReasonVisible, "the reason line is gone again")
            }
        }
}
