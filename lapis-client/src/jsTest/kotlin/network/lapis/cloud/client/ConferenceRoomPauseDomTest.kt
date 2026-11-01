package network.lapis.cloud.client

import io.kvision.panel.vPanel
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.254 -- the pause toggle in a real mounted root: `aria-pressed`, an unavailable option is `aria-disabled` + `aria-describedby` (never
 * `disabled`), the click of an unavailable option is swallowed, the reason text is in the DOM. The call screen itself (the one banner) cannot be
 * mounted in Karma; its text and state logic is pinned in [ConferenceRoomPauseTest].
 */
class ConferenceRoomPauseDomTest {
    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    @Test
    fun theToggle_isAlwaysInTheDom_nextToModeration_notInsideTheClosedBody() {
        withMountedRoot("pause-toggle-present") { root, element ->
            ConferenceModerationPanel(
                parent = root.vPanel(),
                muteOnJoin = false,
                participantsMayUnmute = true,
                onMuteOnJoin = { _, _ -> },
                onLock = { _, _ -> },
            )
            val button = element().q("button.lapis-conference-pause-toggle")
            assertEquals("false", button.getAttribute("aria-pressed"))
            assertNull(element().querySelector(".lapis-conference-moderation-body"), "the body is closed, the toggle is still there")
        }
    }

    @Test
    fun anUnavailableToggle_isAriaDisabledWithAReason_notDisabled_andSwallowsTheClick() {
        withMountedRoot("pause-toggle-unavailable") { root, element ->
            var clicks = 0
            val panel =
                ConferenceModerationPanel(
                    parent = root.vPanel(),
                    muteOnJoin = false,
                    participantsMayUnmute = true,
                    onPause = { clicks++ },
                    onMuteOnJoin = { _, _ -> },
                    onLock = { _, _ -> },
                )
            panel.setPause(pauseButtonStateOf(null, null, null, null))
            val button = element().q("button.lapis-conference-pause-toggle")
            assertEquals("true", button.getAttribute("aria-disabled"))
            assertNull(button.getAttribute("disabled"), "never the disabled attribute: nobody would hear why")
            val reasonId = assertNotNull(button.getAttribute("aria-describedby"))
            assertEquals(
                "Weder Aufzeichnung noch Live-Stream laufen.",
                element()
                    .q("#$reasonId")
                    .textContent
                    .orEmpty()
                    .trim(),
            )
            button.click()
            assertEquals(0, clicks, "an unavailable option swallows the click")
        }
    }

    @Test
    fun anAvailableToggle_callsBack_andChangesIntoResumeWhilePaused() {
        withMountedRoot("pause-toggle-available") { root, element ->
            var clicks = 0
            val panel =
                ConferenceModerationPanel(
                    parent = root.vPanel(),
                    muteOnJoin = false,
                    participantsMayUnmute = true,
                    onPause = { clicks++ },
                    onMuteOnJoin = { _, _ -> },
                    onLock = { _, _ -> },
                )
            panel.setPause(PauseButtonState(label = "⏸ Pause", pressed = false, available = true, busy = false, reason = null))
            val button = element().q("button.lapis-conference-pause-toggle")
            assertNull(button.getAttribute("aria-disabled"))
            assertNull(button.getAttribute("aria-describedby"))
            button.click()
            assertEquals(1, clicks)

            panel.setPause(PauseButtonState(label = "▶ Fortsetzen", pressed = true, available = true, busy = false, reason = null))
            assertEquals("true", button.getAttribute("aria-pressed"))
            assertTrue(button.textContent.orEmpty().contains("Fortsetzen"))

            panel.setPause(PauseButtonState(label = "Wird fortgesetzt …", pressed = true, available = false, busy = true, reason = null))
            assertEquals("true", button.getAttribute("aria-busy"))
            assertEquals("true", button.getAttribute("aria-disabled"))
        }
    }
}
