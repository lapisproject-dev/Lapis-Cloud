package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.browser.localStorage
import network.lapis.cloud.client.livekit.ConferenceHandQueueView
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.127 -- the "Wortmeldungen" panel (numbered list, names as text, moderation controls only for the moderation), the bar toggle, the dock words. */
class ConferenceHandQueuePanelDomTest {
    private val a = "11111111-1111-1111-1111-111111111111"
    private val b = "22222222-2222-2222-2222-222222222222"

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun names(id: String): String? = mapOf(a to "Anna <b>fett</b>", b to "Ben").get(id)

    @Test
    fun theListIsANumberedOl_inOrder_withNamesAsText_andAnUnknownPersonIsNamedUnbekannt() {
        withMountedRoot("hands-panel-list") { root, element ->
            val panel =
                ConferenceHandQueuePanel(
                    root.vPanel(),
                    canModerate = false,
                    preference = HandAnnouncePreference(),
                    onLowerOf = {},
                    onLowerAll = {},
                )
            panel.render(listOf(b, a, "33333333-3333-3333-3333-333333333333"), ::names)
            val host = element()
            assertEquals("OL", assertNotNull(host.querySelector("ol")).tagName)
            val items = host.all("ol > li")
            assertEquals(listOf("Ben", "Anna <b>fett</b>", "Unbekannt"), items.map { it.textContent.orEmpty().trim() })
            assertNull(host.querySelector("ol b"), "a name is text, never markup")
            assertEquals("Wortmeldungen", assertNotNull(host.querySelector("[role=region]")).getAttribute("aria-label"))
        }
    }

    @Test
    fun anEmptyQueue_saysSo_andShowsNoList() {
        withMountedRoot("hands-panel-empty") { root, element ->
            val panel =
                ConferenceHandQueuePanel(
                    root.vPanel(),
                    canModerate = true,
                    preference = HandAnnouncePreference(),
                    onLowerOf = {},
                    onLowerAll = {},
                )
            panel.render(emptyList(), ::names)
            assertTrue(element().textContent.orEmpty().contains("Keine Wortmeldungen"))
            assertEquals(0, element().all("ol > li").size)
            assertTrue(element().all(".lapis-conference-hands-lower-all").isEmpty(), "nothing to lower")
            panel.render(listOf(a), ::names)
            assertFalse(element().textContent.orEmpty().contains("Keine Wortmeldungen"))
            assertEquals(1, element().all(".lapis-conference-hands-lower-all").size)
        }
    }

    @Test
    fun onlyTheModerationGetsTheLoweringControls_andTheAnnounceSwitch() {
        withMountedRoot("hands-panel-controls") { root, element ->
            val participant =
                ConferenceHandQueuePanel(
                    root.vPanel(),
                    canModerate = false,
                    preference = HandAnnouncePreference(),
                    onLowerOf = {},
                    onLowerAll = {},
                )
            participant.render(listOf(a, b), ::names)
            assertEquals(0, element().all("ol button").size)
            assertNull(element().querySelector("[role=switch]"))
            assertNull(element().querySelector(".lapis-conference-hands-lower-all"))
        }
        val lowered = mutableListOf<String>()
        var all = 0
        withMountedRoot("hands-panel-controls-mod") { root, element ->
            val moderator =
                ConferenceHandQueuePanel(
                    root.vPanel(),
                    canModerate = true,
                    preference = HandAnnouncePreference(),
                    onLowerOf = { lowered += it },
                    onLowerAll = { all++ },
                )
            moderator.render(listOf(a, b), ::names)
            val buttons = element().all("ol button")
            assertEquals(2, buttons.size)
            assertEquals(listOf("Hand von Anna <b>fett</b> senken", "Hand von Ben senken"), buttons.map { it.getAttribute("aria-label") })
            buttons[1].click()
            assertEquals(listOf(b), lowered)
            element().q(".lapis-conference-hands-lower-all").click()
            assertEquals(1, all)
            assertEquals("switch", element().q("[role=switch]").getAttribute("role"))
        }
    }

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    @Test
    fun theAnnounceSwitch_isOnByDefault_andRemembersOff() {
        val key = HAND_ANNOUNCE_STORAGE_KEY
        localStorage.removeItem(key)
        val preference = HandAnnouncePreference()
        assertTrue(preference.isOn())
        preference.setOn(false)
        assertEquals("off", localStorage[key])
        assertFalse(HandAnnouncePreference().isOn())
        preference.setOn(true)
        assertNull(localStorage[key])
        assertTrue(HandAnnouncePreference().isOn())
    }

    @Test
    fun aStorageThatThrows_doesNotBreakThePreference() {
        val original = js("Object.getOwnPropertyDescriptor(window, 'localStorage')")
        try {
            js("Object.defineProperty(window, 'localStorage', { configurable: true, get: function () { throw new Error('blocked'); } })")
            val preference = HandAnnouncePreference()
            assertTrue(preference.isOn(), "a blocked storage means on")
            preference.setOn(false)
            assertFalse(preference.isOn(), "the choice lives in memory")
        } finally {
            if (original != null) js("Object.defineProperty(window, 'localStorage', original)")
        }
    }

    // -- the bar toggle ----------------------------------------------------------------------------------------------------------------

    @Test
    fun theBarToggle_carriesPressedState_theVerb_andThePosition() {
        withMountedRoot("hands-bar-toggle") { root, element ->
            val control = root.conferenceHandControl()
            val button = control.button
            control.apply(raised = false, position = null, busy = false)
            assertEquals("false", button.getAttribute("aria-pressed"))
            assertEquals("Hand heben", button.getAttribute("aria-label"))
            control.apply(raised = true, position = 3, busy = false)
            assertEquals("true", button.getAttribute("aria-pressed"))
            assertEquals("Hand senken, Position 3", button.getAttribute("aria-label"))
            val badge = element().q(".lapis-conference-control-badge")
            assertEquals("3", badge.textContent)
            assertTrue(badge.classList.contains("lapis-conference-control-badge-on"))
            control.apply(raised = false, position = null, busy = true)
            assertEquals("true", button.getAttribute("aria-busy"))
            assertFalse(badge.classList.contains("lapis-conference-control-badge-on"))
        }
    }

    // -- the dock ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun theMiniBarSaysItInWords_ownHandFirst_andOnlyAnumberOtherwise() {
        assertEquals(emptyList(), conferenceHandBadges(handRaisedSelf = false, raisedHandsCount = null))
        assertEquals(listOf("Ihre Hand ist gehoben"), conferenceHandBadges(handRaisedSelf = true, raisedHandsCount = null))
        assertEquals(
            listOf("Ihre Hand ist gehoben", "Gehobene Hände: 3"),
            conferenceHandBadges(handRaisedSelf = true, raisedHandsCount = 3),
        )
        assertEquals(listOf("Gehobene Hände: 2"), conferenceHandBadges(handRaisedSelf = false, raisedHandsCount = 2))
    }

    @Test
    fun theViewIsUnchangedForAnOldSnapshotWithoutHands() {
        val view = ConferenceHandQueueView(self = a)
        view.accept(ConferenceHandQueueDto("0123456789abcdef", 1, listOf(a)))
        assertEquals(1, view.positionOfSelf())
    }
}
