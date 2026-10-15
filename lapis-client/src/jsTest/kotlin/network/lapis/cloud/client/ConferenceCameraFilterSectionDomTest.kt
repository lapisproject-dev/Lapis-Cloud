package network.lapis.cloud.client

import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.142 -- the camera filter panel in a REAL, mounted `Root`: no node at all while it is not offered, the disabled row with the Chromium
 * explanation, a labelled `<input type=range>` with `<output>` and `aria-valuetext` per slider, the keyboard (arrows +-1, Page up/down +-10,
 * Home/End), the main switch, "Alle Filter aus", the honest notice shown exactly while the picture is changed, and the pause line.
 */
class ConferenceCameraFilterSectionDomTest {
    private fun mounted(block: (io.kvision.panel.Root, () -> HTMLElement) -> Unit) = withMountedRoot("camera-filter-section-test", block)

    private class Recorder {
        var settings = CameraFilterSettings()
        var toggles = mutableListOf<Boolean>()
        var resets = 0
        var retries = 0
    }

    private fun io.kvision.core.Container.section(
        recorder: Recorder,
        offered: Boolean = true,
        availability: ConferenceBackgroundAvailability = ConferenceBackgroundAvailability.AVAILABLE,
        stageAvailable: Boolean = true,
    ) = ConferenceCameraFilterSection(
        parent = this,
        offered = offered,
        availability = availability,
        stageAvailable = stageAvailable,
        onSlider = { change -> recorder.settings = change(recorder.settings) },
        onToggle = { recorder.toggles += it },
        onResetAll = { recorder.resets++ },
        onRetrySmoothing = { recorder.retries++ },
    )

    private fun ui(
        settings: CameraFilterSettings = CameraFilterSettings(),
        phase: CameraFilterPhase = CameraFilterPhase.IDLE,
        pause: SmoothingPause = SmoothingPause.NONE,
        live: String? = null,
    ) = CameraFilterUiState(true, ConferenceBackgroundAvailability.AVAILABLE, settings, phase, pause, liveMessage = live)

    private fun HTMLElement.slider(id: String): HTMLInputElement = assertNotNull(querySelector("#$id") as? HTMLInputElement, "slider $id")

    private fun key(
        element: HTMLElement,
        key: String,
    ) = element.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, cancelable = true, bubbles = true)))

    private val allSliders =
        listOf(
            CameraFilterSliderIds.BRIGHTNESS,
            CameraFilterSliderIds.CONTRAST,
            CameraFilterSliderIds.WARMTH,
            CameraFilterSliderIds.BACKLIGHT,
            CameraFilterSliderIds.SMOOTHING,
        )

    private fun open(section: ConferenceCameraFilterSection) {
        section.toggleButton!!.getElement()!!.click()
    }

    @Test
    fun notOffered_nothingIsInTheDom() {
        mounted { root, element ->
            val section = root.section(Recorder(), offered = false)
            section.render(ui())
            assertNull(element().querySelector(".lapis-conference-camera-filter-group"))
            assertNull(element().querySelector("button[aria-controls]"))
            assertEquals(0, element().querySelectorAll("button").length)
            assertFalse(section.present)
        }
    }

    @Test
    fun anUnsupportedBrowser_keepsADisabledRowAndExplainsChromium() {
        mounted { root, element ->
            val section = root.section(Recorder(), stageAvailable = false)
            section.render(ui())
            assertTrue(assertNotNull(section.toggleButton?.getElement()).hasAttribute("disabled"))
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Chromium"), text)
            assertEquals(0, element().querySelectorAll("input[type=range]").length)
        }
    }

    @Test
    fun theAppWebView_getsItsOwnExplanation() {
        mounted { root, element ->
            val section = root.section(Recorder(), availability = ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW)
            section.render(ui())
            assertTrue(element().textContent.orEmpty().contains("in der App"), element().textContent)
        }
    }

    @Test
    fun theRowCarriesTheStateInItsTextAndExposesAriaExpanded() {
        mounted { root, element ->
            val section = root.section(Recorder())
            section.render(ui())
            val toggle = assertNotNull(section.toggleButton?.getElement())
            assertTrue(toggle.textContent.orEmpty().contains("Kamera-Filter: Aus"), toggle.textContent)
            assertEquals("false", toggle.getAttribute("aria-expanded"))
            toggle.click()
            assertEquals("true", toggle.getAttribute("aria-expanded"))
            assertNotNull(element().querySelector("#" + toggle.getAttribute("aria-controls")))
            section.render(ui(CameraFilterSettings(enabled = true, brightness = 5)))
            assertTrue(toggle.textContent.orEmpty().contains("Kamera-Filter: An"), toggle.textContent)
        }
    }

    @Test
    fun everySlider_hasALabel_anOutput_andAnAriaValueText() {
        mounted { root, element ->
            val section = root.section(Recorder())
            section.render(ui(CameraFilterSettings(true, 20, -10, 0, 50, 70)))
            open(section)
            val expected =
                mapOf(
                    CameraFilterSliderIds.BRIGHTNESS to ("Helligkeit" to "+20 %"),
                    CameraFilterSliderIds.CONTRAST to ("Kontrast" to "-10 %"),
                    CameraFilterSliderIds.WARMTH to ("Farbtemperatur" to "0 %"),
                    CameraFilterSliderIds.BACKLIGHT to ("Gegenlicht ausgleichen" to "50 %"),
                    CameraFilterSliderIds.SMOOTHING to ("Stärke" to "70 %"),
                )
            for ((id, pair) in expected) {
                val input = element().slider(id)
                assertEquals("range", input.type)
                val label = assertNotNull(element().querySelector("label[for=$id]"), "label for $id")
                assertEquals(pair.first, label.textContent)
                assertEquals(pair.second, input.getAttribute("aria-valuetext"), id)
                val output = assertNotNull(element().querySelector("output[for=$id]"), "output for $id")
                assertEquals(pair.second, output.textContent, id)
            }
            assertEquals("20", element().slider(CameraFilterSliderIds.BRIGHTNESS).value)
            assertEquals("-40", element().slider(CameraFilterSliderIds.BRIGHTNESS).min)
            assertEquals("40", element().slider(CameraFilterSliderIds.BRIGHTNESS).max)
            assertEquals("-50", element().slider(CameraFilterSliderIds.WARMTH).min)
            assertEquals("100", element().slider(CameraFilterSliderIds.SMOOTHING).max)
        }
    }

    @Test
    fun theGroups_areFieldsetsWithLegends() {
        mounted { root, element ->
            val section = root.section(Recorder())
            section.render(ui())
            open(section)
            val legends =
                (0 until element().querySelectorAll("fieldset > legend").length).map {
                    element().querySelectorAll("fieldset > legend").item(it)!!.textContent
                }
            assertEquals(listOf("Licht und Farbe", "Hautglättung"), legends)
            assertTrue(element().textContent.orEmpty().contains("Hautglättung verändert die Darstellung Ihres Gesichts."))
        }
    }

    @Test
    fun theKeyboard_arrowsMoveByOne_pageKeysByTen_homeAndEndToTheEnds() {
        mounted { root, element ->
            val recorder = Recorder().also { it.settings = CameraFilterSettings(true, brightness = 5) }
            val section = root.section(recorder)
            section.render(ui(recorder.settings))
            open(section)
            val brightness = element().slider(CameraFilterSliderIds.BRIGHTNESS)

            // the controller re-renders after every change; the test does the same so the slider shows the new value
            fun press(key: String) {
                key(brightness, key)
                section.render(ui(recorder.settings))
            }
            press("ArrowRight")
            assertEquals(6, recorder.settings.brightness)
            press("ArrowLeft")
            assertEquals(5, recorder.settings.brightness)
            press("PageUp")
            assertEquals(15, recorder.settings.brightness)
            press("PageDown")
            assertEquals(5, recorder.settings.brightness)
            press("End")
            assertEquals(40, recorder.settings.brightness)
            press("Home")
            assertEquals(-40, recorder.settings.brightness)
            // another key does nothing and is not swallowed
            val before = recorder.settings
            assertTrue(key(brightness, "a"))
            assertEquals(before, recorder.settings)
        }
    }

    @Test
    fun theKeys_neverLeaveTheRange() {
        assertEquals(40, cameraFilterSliderKeyTarget(35, -40, 40, "PageUp"))
        assertEquals(-40, cameraFilterSliderKeyTarget(-35, -40, 40, "PageDown"))
        assertEquals(0, cameraFilterSliderKeyTarget(0, 0, 100, "ArrowDown"))
        assertEquals(100, cameraFilterSliderKeyTarget(100, 0, 100, "ArrowUp"))
        assertNull(cameraFilterSliderKeyTarget(5, 0, 100, "Tab"))
    }

    @Test
    fun anInputEvent_movesTheSlider() {
        mounted { root, element ->
            val recorder = Recorder()
            val section = root.section(recorder)
            section.render(ui())
            open(section)
            val warmth = element().slider(CameraFilterSliderIds.WARMTH)
            warmth.value = "-30"
            warmth.dispatchEvent(Event("input", EventInit(bubbles = true)))
            assertEquals(-30, recorder.settings.warmth)
        }
    }

    @Test
    fun theMainSwitch_isARealSwitch_andReportsTheNewState() {
        mounted { root, element ->
            val recorder = Recorder()
            val section = root.section(recorder)
            section.render(ui())
            open(section)
            val switch = assertNotNull(element().querySelector("[role=switch]") as? HTMLElement)
            assertEquals("false", switch.getAttribute("aria-checked"))
            switch.click()
            assertEquals(listOf(true), recorder.toggles)
            section.render(ui(CameraFilterSettings(enabled = true, brightness = 3)))
            assertEquals("true", switch.getAttribute("aria-checked"))
            switch.click()
            assertEquals(listOf(true, false), recorder.toggles)
        }
    }

    @Test
    fun alleFilterAus_callsBack() {
        mounted { root, element ->
            val recorder = Recorder()
            val section = root.section(recorder)
            section.render(ui())
            open(section)
            val button =
                (0 until element().querySelectorAll("button").length)
                    .map { element().querySelectorAll("button").item(it) as HTMLElement }
                    .first { it.textContent == "Alle Filter aus" }
            button.click()
            assertEquals(1, recorder.resets)
        }
    }

    @Test
    fun theHonestNotice_isShownExactlyWhileThePictureIsChanged() {
        mounted { root, element ->
            val section = root.section(Recorder())
            section.render(ui())
            open(section)
            assertNull(element().querySelector(".lapis-conference-filter-hint"))
            section.render(ui(CameraFilterSettings(true, brightness = 5), CameraFilterPhase.ACTIVE))
            val hint = assertNotNull(element().querySelector(".lapis-conference-filter-hint") as? HTMLElement)
            assertTrue(hint.textContent.orEmpty().contains("alle sehen es"), hint.textContent)
            assertTrue(hint.textContent.orEmpty().contains("Aufnahme und Livestream"), hint.textContent)
            section.render(ui(CameraFilterSettings(true, brightness = 5), CameraFilterPhase.IDLE))
            assertNull(element().querySelector(".lapis-conference-filter-hint"))
        }
    }

    @Test
    fun thePauseLine_showsOnlyWhilePaused_andRetryCallsBack() {
        mounted { root, element ->
            val recorder = Recorder()
            val section = root.section(recorder)
            section.render(ui(CameraFilterSettings(true, smoothing = 50), CameraFilterPhase.ACTIVE))
            open(section)
            assertNull(element().querySelector(".lapis-conference-filter-pause"))
            section.render(ui(CameraFilterSettings(true, smoothing = 50), CameraFilterPhase.ACTIVE, SmoothingPause.PERFORMANCE))
            val line = assertNotNull(element().querySelector(".lapis-conference-filter-pause") as? HTMLElement)
            assertTrue(line.textContent.orEmpty().contains("Pausiert"), line.textContent)
            assertEquals("50", element().slider(CameraFilterSliderIds.SMOOTHING).value, "the slider keeps its value while paused")
            val retry = assertNotNull(line.querySelector("button") as? HTMLElement)
            retry.click()
            assertEquals(1, recorder.retries)
            section.render(ui(CameraFilterSettings(true, smoothing = 50), CameraFilterPhase.ACTIVE, SmoothingPause.NONE))
            assertNull(element().querySelector(".lapis-conference-filter-pause"))
        }
    }

    @Test
    fun theLiveRegion_announcesOnlyWhatTheControllerSays() {
        mounted { root, element ->
            val section = root.section(Recorder())
            section.render(ui())
            open(section)
            val live = assertNotNull(element().querySelector("[aria-live=polite]") as? HTMLElement)
            assertEquals("", live.textContent)
            section.render(ui(live = "Alle Filter ausgeschaltet"))
            assertEquals("Alle Filter ausgeschaltet", live.textContent)
            section.render(ui()) // a plain re-render does not erase or repeat it
            assertEquals("Alle Filter ausgeschaltet", live.textContent)
        }
    }

    @Test
    fun focusMainSwitch_putsFocusOnTheSwitch() {
        mounted { root, element ->
            val section = root.section(Recorder())
            section.render(ui())
            open(section)
            section.focusMainSwitch()
            val switch = element().querySelector("[role=switch]")
            assertTrue(kotlinx.browser.document.activeElement === switch)
        }
    }
}
