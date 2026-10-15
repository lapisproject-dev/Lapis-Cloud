package network.lapis.cloud.client

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeGongPreference(
    var on: Boolean = true,
    private val throwing: Boolean = false,
) : TimerGongPreference {
    val writes = mutableListOf<Boolean>()

    override fun isOn(): Boolean = on

    override fun setOn(on: Boolean) {
        writes += on
        this.on = on
        if (throwing) error("storage blocked")
    }
}

private class FakeGongPlayer : JoinLeaveSoundPlayer {
    var unlocks = 0
    var gongs = mutableListOf<Boolean>()

    override fun unlock() {
        unlocks++
    }

    override fun playJoin() {}

    override fun playLeave() {}

    override fun playProbe() {}

    override fun playTimerGong(confirmation: Boolean) {
        gongs += confirmation
    }

    override fun setSink(deviceId: String?) {}

    override fun dispose() {}
}

/** V1.9.133 -- the personal gong switch: on by default, a hint that is always visible, "off" is the only value ever written, a blocked storage means "on". */
class TimerGongSwitchDomTest {
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

    @Test
    fun byDefault_theSwitchIsOn_withALabelAndAnAlwaysVisibleHint_andNoLiveRegion() {
        withMountedRoot("timer-gong-switch-default") { root, element ->
            assertTrue(stylesLoaded)
            TimerGongSwitchRow(root, FakeGongPreference(), FakeGongPlayer())
            val host = element()
            val button = host.switchButton()
            assertEquals("button", button.getAttribute("type"))
            assertEquals("true", button.getAttribute("aria-checked"))
            val label = assertNotNull(host.querySelector("#${assertNotNull(button.getAttribute("aria-labelledby"))}"))
            assertEquals("Gong am Ende", label.textContent.orEmpty().trim())
            val hint = assertNotNull(host.querySelector("#${assertNotNull(button.getAttribute("aria-describedby"))}") as? HTMLElement)
            assertTrue(hint.textContent.orEmpty().contains("Ein leiser Gong"))
            assertTrue(hint.getBoundingClientRect().height > 0, "the hint is always visible")
            assertNull(host.querySelector("[aria-live]"))
        }
    }

    @Test
    fun switchingOff_writesOff_andPlaysNothing_andSwitchingOnUnlocksAndPlaysTheGongOnce(): Promise<Unit> =
        formTest {
            val preference = FakeGongPreference()
            val player = FakeGongPlayer()
            mountedForm("timer-gong-switch-click") { root, element ->
                TimerGongSwitchRow(root, preference, player)
                val button = element().switchButton()
                button.click()
                assertEquals(listOf(false), preference.writes)
                awaitUntil("the switch is off") { button.getAttribute("aria-checked") == "false" }
                assertTrue(player.gongs.isEmpty(), "switching off plays nothing")
                assertEquals(0, player.unlocks)
                button.click()
                awaitUntil("the switch is on") { button.getAttribute("aria-checked") == "true" }
                assertEquals(listOf(true), player.gongs, "once, as a confirmation")
                assertEquals(1, player.unlocks, "inside the click")
            }
        }

    @Test
    fun aStorageThatThrowsOnWrite_neverBreaksTheSwitch(): Promise<Unit> =
        formTest {
            val preference = FakeGongPreference(throwing = true)
            mountedForm("timer-gong-switch-throwing") { root, element ->
                TimerGongSwitchRow(root, preference, FakeGongPlayer())
                val button = element().switchButton()
                button.click()
                awaitUntil("the switch followed the click") { button.getAttribute("aria-checked") == "false" }
            }
        }

    @Test
    fun theRealPreference_writesOnlyOff_andABlockedStorageMeansOn() {
        val storage = window.localStorage
        storage.removeItem(TIMER_GONG_STORAGE_KEY)
        val preference = LocalStorageTimerGongPreference()
        assertTrue(preference.isOn(), "on by default")
        preference.setOn(false)
        assertEquals("off", storage.getItem(TIMER_GONG_STORAGE_KEY))
        assertFalse(preference.isOn())
        preference.setOn(true)
        assertNull(storage.getItem(TIMER_GONG_STORAGE_KEY), "on removes the entry")
        // a blocked storage: the accessor throws -> "on", and the choice lives in memory without a throw
        val original = js("Object.getOwnPropertyDescriptor(window, 'localStorage')")
        val define: dynamic = js("(function (descriptor) { Object.defineProperty(window, 'localStorage', descriptor); })")
        val block: dynamic = js("({ configurable: true, get: function () { throw new Error('blocked'); } })")
        try {
            define(block)
            val blocked = LocalStorageTimerGongPreference()
            assertTrue(blocked.isOn(), "blocked storage means on")
            blocked.setOn(false)
            assertFalse(blocked.isOn(), "the choice is kept in memory for this page")
        } finally {
            if (original != null && original != undefined) define(original) else js("delete window.localStorage")
        }
        window.localStorage.removeItem(TIMER_GONG_STORAGE_KEY)
    }
}
