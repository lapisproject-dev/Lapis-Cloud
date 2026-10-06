package network.lapis.cloud.client

import io.kvision.panel.hPanel
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.66: the moderation group of the conference bar in a real mounted root with the real stylesheet cascade. The call screen itself
 * cannot be mounted in Karma (its RPCs fail, see `ConferenceScreenRootLifecycleDomTest`), so the group is built the way the screen builds it.
 */
class ConferenceModerationControlsDomTest {
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

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.byLabel(label: String) = q("button[aria-label='$label']")

    private fun resolved(text: String) = text.removePrefix("###KvI18nS###")

    @Test
    fun aPlainParticipant_getsNoGroupAndNoEmptyGroupInTheDom() {
        withMountedRoot("moderation-none") { root, element ->
            val group = root.conferenceModerationGroup(canModerate = false, onRecord = {}, onStream = {}, onEndForAll = {})
            assertNull(group)
            assertNull(element().querySelector("[role=group][aria-label='Moderation']"))
            assertNull(element().querySelector("button"))
        }
    }

    @Test
    fun aModerator_getsTheGroupWithThreeNamedControls() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-group") { root, element ->
            val group = assertNotNull(root.conferenceModerationGroup(true, {}, {}, {}))
            group.setRecordingAvailable(true)
            group.setStreamingAvailable(true)
            val host = element()
            val box = host.q("[role=group][aria-label='Moderation']")
            assertEquals(3, box.querySelectorAll("button").length)
            for (name in listOf("Aufzeichnung", "Live-Stream", "Für alle beenden")) host.byLabel(name)
            assertNull(host.byLabel("Für alle beenden").getAttribute("aria-pressed"), "ending is an action, not a state")
        }
    }

    @Test
    fun recordingAndStreamingStayHidden_notDisabled_whileTheyAreNotConfigured() {
        withMountedRoot("moderation-unconfigured") { root, element ->
            val group = assertNotNull(root.conferenceModerationGroup(true, {}, {}, {}))
            val host = element()
            // A hidden KVision widget is not rendered at all: no control to click, nothing disabled and confusing.
            assertNull(host.querySelector("button[aria-label='Aufzeichnung']"))
            assertNull(host.querySelector("button[aria-label='Live-Stream']"))
            assertTrue(!group.recordButton.visible && !group.streamButton.visible)
            group.setRecordingAvailable(true)
            assertTrue(group.recordButton.visible)
            group.setRecordingAvailable(false)
            assertTrue(!group.recordButton.visible)
        }
    }

    @Test
    fun recordingState_isShownByPressedRingBadgeAndVerb() {
        withMountedRoot("moderation-recording-state") { root, element ->
            val group = assertNotNull(root.conferenceModerationGroup(true, {}, {}, {}))
            group.setRecordingAvailable(true)
            val host = element()
            val button = { host.byLabel("Aufzeichnung") }

            group.applyRecording(recordingToggleView(null, canStart = true))
            assertEquals("false", button().getAttribute("aria-pressed"))
            assertEquals("Aufzeichnung starten", button().getAttribute("title"))
            assertTrue(!button().classList.contains("active"))
            assertTrue(!button().q(".lapis-conference-control-badge").classList.contains("lapis-conference-control-badge-on"))

            group.applyRecording(recordingToggleView(ConferenceRecordingStatus.RECORDING, canStart = false))
            assertEquals("true", button().getAttribute("aria-pressed"))
            assertEquals("Aufzeichnung beenden", button().getAttribute("title"))
            assertEquals("Aufzeichnung", button().getAttribute("aria-label"), "the noun stays, the state is aria-pressed")
            assertTrue(button().classList.contains("active"))
            val badge = button().q(".lapis-conference-control-badge")
            assertTrue(badge.classList.contains("lapis-conference-control-badge-on"))
            assertEquals("●", badge.textContent?.trim())
            assertEquals("block", window.getComputedStyle(badge).display)
        }
    }

    @Test
    fun streamState_isShownLikewise_withTheDiamondSign() {
        withMountedRoot("moderation-stream-state") { root, element ->
            val group = assertNotNull(root.conferenceModerationGroup(true, {}, {}, {}))
            group.setStreamingAvailable(true)
            val host = element()
            group.applyStream(streamToggleView(ConferenceStreamStatus.LIVE, null, canStart = false))
            val button = host.byLabel("Live-Stream")
            assertEquals("true", button.getAttribute("aria-pressed"))
            assertEquals("Live-Stream beenden", button.getAttribute("title"))
            assertEquals("◆", button.q(".lapis-conference-control-badge").textContent?.trim())
            assertTrue(group.streamActive)
            group.applyStream(streamToggleView(null, null, canStart = true))
            assertEquals("false", button.getAttribute("aria-pressed"))
            assertEquals("Live-Stream starten …", button.getAttribute("title"))
        }
    }

    @Test
    fun loadingState_disablesAndMarksBusy_showsASpinnerInsteadOfTheIcon() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-busy") { root, element ->
            val bar = root.hPanel(spacing = 6) { addCssClasses("lapis-conference-controls-row") }
            val group = assertNotNull(bar.conferenceModerationGroup(true, {}, {}, {}))
            group.setRecordingAvailable(true)
            val host = element()
            val button = { host.byLabel("Aufzeichnung") }
            val spinner = { button().q(".lapis-conference-control-spinner") }
            assertNull(button().querySelector(".lapis-conference-control-spinner"), "no spinner at rest (not rendered)")

            group.applyRecording(recordingToggleView(ConferenceRecordingStatus.STOPPING, canStart = false))
            assertTrue(button().hasAttribute("disabled"))
            assertEquals("true", button().getAttribute("aria-busy"))
            assertTrue(spinner().classList.contains("fa-spin"))
            assertTrue(window.getComputedStyle(spinner()).display != "none", "the spinner shows")
            assertEquals("none", window.getComputedStyle(button().q("i.lapis-action-icon")).display, "the icon makes room")
            assertEquals(listOf("Aufzeichnung wird beendet …"), group.progressTexts.map { resolved(it) })

            group.applyRecording(recordingToggleView(ConferenceRecordingStatus.RECORDING, canStart = false))
            assertNull(button().getAttribute("aria-busy"))
            assertTrue(!button().hasAttribute("disabled"))
            assertNull(button().querySelector(".lapis-conference-control-spinner"))
            assertTrue(group.progressTexts.isEmpty())

            group.setRecordingBusy(true)
            assertTrue(button().hasAttribute("disabled"), "a running request blocks a second click")
            assertEquals("true", button().getAttribute("aria-busy"))
            group.setRecordingBusy(false)
            assertTrue(!button().hasAttribute("disabled"))
        }
    }

    @Test
    fun fontAwesomeStopsTheSpinnerUnderReducedMotion() {
        assertTrue(stylesLoaded)
        val rules = StringBuilder()
        for (i in 0 until document.styleSheets.length) {
            runCatching {
                val sheet = document.styleSheets.item(i).asDynamic()
                val list = sheet.cssRules
                for (r in 0 until (list.length as Int)) rules.append(list[r].cssText.toString()).append('\n')
            }
        }
        val css = rules.toString()
        val reduced = Regex("""@media \(prefers-reduced-motion[^{]*\)\s*\{[^@]*fa-spin""").containsMatchIn(css.replace("\n", " "))
        assertTrue(reduced, "Font Awesome must stop .fa-spin under prefers-reduced-motion")
    }

    @Test
    fun clicks_callTheMatchingCallback_only() {
        withMountedRoot("moderation-clicks") { root, element ->
            val calls = mutableListOf<String>()
            val group =
                assertNotNull(
                    root.conferenceModerationGroup(
                        true,
                        onRecord = { calls += "record" },
                        onStream = { calls += "stream" },
                        onEndForAll = { calls += "end" },
                    ),
                )
            group.setRecordingAvailable(true)
            group.setStreamingAvailable(true)
            val host = element()
            host.byLabel("Aufzeichnung").click()
            host.byLabel("Live-Stream").click()
            host.byLabel("Für alle beenden").click()
            assertEquals(listOf("record", "stream", "end"), calls)
        }
    }

    @Test
    fun keyboard_controlsAreFocusable_havePointerTargets_andAVisibleFocusRing() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-keyboard") { root, element ->
            val bar = root.hPanel(spacing = 6) { addCssClasses("lapis-conference-controls-row") }
            val group = assertNotNull(bar.conferenceModerationGroup(true, {}, {}, {}))
            group.setRecordingAvailable(true)
            group.setStreamingAvailable(true)
            val host = element()
            for (name in listOf("Aufzeichnung", "Live-Stream", "Für alle beenden")) {
                val button = host.byLabel(name)
                assertTrue(button.tabIndex >= 0, "$name is reachable by Tab")
                val style = window.getComputedStyle(button)
                assertTrue(style.minHeight.removeSuffix("px").toDouble() >= 44.0, "$name min-height ${style.minHeight}")
                assertTrue(style.minWidth.removeSuffix("px").toDouble() >= 44.0, "$name min-width ${style.minWidth}")
            }
            // The focus ring comes from one theme.css rule (the headless page has no keyboard modality to match :focus-visible).
            val css = themeRuleText()
            assertTrue(css.contains(".lapis-conference-controls-row .btn:focus-visible"), "focus-visible rule for the bar")
        }
    }

    private fun themeRuleText(): String {
        val out = StringBuilder()
        for (i in 0 until document.styleSheets.length) {
            runCatching {
                val list =
                    document.styleSheets
                        .item(i)
                        .asDynamic()
                        .cssRules
                for (r in 0 until (list.length as Int)) out.append((list[r].selectorText ?: "").toString()).append('\n')
            }
        }
        return out.toString()
    }
}
