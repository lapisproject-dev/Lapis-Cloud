package network.lapis.cloud.client

import io.kvision.html.span
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.129 -- the overlay panel in a REAL, mounted `Root`: no node at all while the option is off, the radio semantics of every group, the
 * keyboard (arrow keys move AND choose, the corner grid moves by row and column), the honest notice that is shown exactly while something is
 * drawn, and the persisted-state round trip through the `onChange` callback.
 */
class ConferenceVideoOverlaySectionDomTest {
    private val logoId = "11111111-1111-4111-8111-111111111111"
    private val frameId = "22222222-2222-4222-8222-222222222222"
    private val logo = VideoOverlayItemDto(logoId, VideoOverlayKind.LOGO, "Vereinslogo", 600, 300, "/api/video-overlays/${"A".repeat(43)}")
    private val frame =
        VideoOverlayItemDto(frameId, VideoOverlayKind.FRAME, "Goldrahmen", 1920, 1080, "/api/video-overlays/${"B".repeat(43)}")

    private fun mounted(block: (io.kvision.panel.Root, () -> HTMLElement) -> Unit) = withMountedRoot("video-overlay-section-test", block)

    private fun ui(
        settings: VideoOverlaySettings = VideoOverlaySettings(),
        library: List<VideoOverlayItemDto> = listOf(logo, frame),
        scene: VideoOverlayScene? = null,
        offered: Boolean = true,
        availability: ConferenceBackgroundAvailability = ConferenceBackgroundAvailability.AVAILABLE,
    ) = VideoOverlayUiState(offered, availability, settings, library, libraryLoaded = true, scene = scene, state = VideoOverlayState())

    private fun io.kvision.core.Container.section(
        offered: Boolean = true,
        availability: ConferenceBackgroundAvailability = ConferenceBackgroundAvailability.AVAILABLE,
        onChange: (VideoOverlaySettings) -> Unit = {},
    ) = ConferenceVideoOverlaySection(parent = this, offered = offered, availability = availability, onChange = onChange)

    private fun HTMLElement.all(selector: String): List<HTMLElement> {
        val list = querySelectorAll(selector)
        return (0 until list.length).map { list.item(it) as HTMLElement }
    }

    private fun HTMLElement.group(label: String): HTMLElement =
        assertNotNull(all("[role=radiogroup]").firstOrNull { it.getAttribute("aria-label") == label }, "radiogroup '$label'")

    private fun HTMLElement.radios(): List<HTMLElement> = all("[role=radio]")

    private fun key(
        element: HTMLElement,
        key: String,
    ) = element.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, cancelable = true, bubbles = true)))

    private val sceneWithName =
        VideoOverlayScene(mapOf(OverlayCorner.TOP_LEFT to SceneCorner.Name("Erika", OverlaySize.MEDIUM, OverlayOpacity.FULL)), null)

    @Test
    fun optionOff_nothingIsInTheDom() {
        mounted { root, element ->
            val section = root.section(offered = false)
            section.render(ui(offered = false))
            root.span("something else")
            assertNull(element().querySelector(".lapis-conference-video-overlay-group"))
            assertNull(element().querySelector("button[aria-controls]"))
            assertFalse(section.present)
            assertEquals(0, element().all("button").size)
        }
    }

    @Test
    fun unsupportedBrowser_theRowStaysDisabledWithAnExplanation_andNoControls() {
        mounted { root, element ->
            val section = root.section(availability = ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER)
            section.render(ui(availability = ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER))
            val toggle = assertNotNull(section.toggleButton?.getElement())
            assertTrue(toggle.hasAttribute("disabled"))
            assertTrue(element().textContent.orEmpty().contains("nicht unterstützt"))
            assertEquals(0, element().all("[role=radio]").size)
        }
    }

    @Test
    fun theToggleRowCarriesTheStateInItsTextAndExposesAriaExpanded() {
        mounted { root, element ->
            val section = root.section()
            section.render(ui())
            val toggle = assertNotNull(section.toggleButton?.getElement())
            assertTrue(toggle.textContent.orEmpty().contains("Overlays: Aus"), toggle.textContent)
            assertEquals("false", toggle.getAttribute("aria-expanded"))
            toggle.click()
            assertEquals("true", toggle.getAttribute("aria-expanded"))
            assertNotNull(element().querySelector("#" + toggle.getAttribute("aria-controls")))
            section.render(ui(settings = VideoOverlaySettings(enabled = true)))
            assertTrue(toggle.textContent.orEmpty().contains("Overlays: An"), toggle.textContent)
        }
    }

    @Test
    fun theHonestNoticeIsShownExactlyWhileSomethingIsDrawn() {
        mounted { root, element ->
            val section = root.section()
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            // KVision does not render a hidden widget at all: "hidden" means "not in the document".
            assertNull(element().querySelector(".lapis-conference-overlay-hint"), "nothing is drawn: no notice")
            section.render(ui(settings = VideoOverlaySettings(enabled = true), scene = sceneWithName))
            val hint = assertNotNull(element().querySelector(".lapis-conference-overlay-hint") as? HTMLElement)
            assertTrue(hint.textContent.orEmpty().contains("alle sehen es"), hint.textContent)
            assertTrue(hint.textContent.orEmpty().contains("Aufnahme und Livestream"), hint.textContent)
            section.render(ui(settings = VideoOverlaySettings(enabled = true), scene = null))
            assertNull(element().querySelector(".lapis-conference-overlay-hint"), "gone again")
        }
    }

    @Test
    fun theSwitch_isARoleSwitch_andAClickAsksForTheFlippedSetting() {
        mounted { root, element ->
            val changes = mutableListOf<VideoOverlaySettings>()
            val section = root.section(onChange = { changes += it })
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            val switch = assertNotNull(element().querySelector("button[role=switch]") as? HTMLElement)
            assertEquals("false", switch.getAttribute("aria-checked"))
            switch.click()
            assertEquals(listOf(VideoOverlaySettings(enabled = true)), changes)
            section.render(ui(settings = VideoOverlaySettings(enabled = true)))
            assertEquals("true", switch.getAttribute("aria-checked"))
            switch.click()
            assertEquals(false, changes.last().enabled)
        }
    }

    @Test
    fun theCornerGrid_isARadiogroupOfFour_withRovingTabindex_andMovesByRowAndColumn() {
        mounted { root, element ->
            val section = root.section()
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            val grid = element().group("Ecke wählen")
            val tiles = grid.radios()
            assertEquals(4, tiles.size)
            assertEquals(listOf("true", "false", "false", "false"), tiles.map { it.getAttribute("aria-checked") })
            assertEquals(listOf("0", "-1", "-1", "-1"), tiles.map { it.getAttribute("tabindex") })
            key(tiles[0], "ArrowRight") // top-left -> top-right
            assertEquals("true", tiles[1].getAttribute("aria-checked"))
            assertEquals("0", tiles[1].getAttribute("tabindex"))
            assertEquals("-1", tiles[0].getAttribute("tabindex"))
            key(tiles[1], "ArrowDown") // top-right -> bottom-right
            assertEquals("true", tiles[3].getAttribute("aria-checked"))
            key(tiles[3], "ArrowLeft") // bottom-right -> bottom-left
            assertEquals("true", tiles[2].getAttribute("aria-checked"))
            key(tiles[2], "ArrowUp") // bottom-left -> top-left
            assertEquals("true", tiles[0].getAttribute("aria-checked"))
            key(tiles[0], "ArrowLeft") // at the edge: nothing moves
            assertEquals("true", tiles[0].getAttribute("aria-checked"))
            // The tiles say what they hold, in their accessible name.
            assertEquals("Oben links: Leer", tiles[0].getAttribute("aria-label"))
        }
    }

    @Test
    fun contentSizeAndOpacityGroups_chooseForTheSelectedCorner() {
        mounted { root, element ->
            val changes = mutableListOf<VideoOverlaySettings>()
            val section = root.section(onChange = { changes += it })
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            val content = element().group("Inhalt")
            assertEquals(listOf("Leer", "Ihr Name", "Vereinslogo"), content.radios().map { it.textContent?.trim() })
            content.radios()[1].click()
            assertEquals(OverlayContent.OwnName, changes.last().slot(OverlayCorner.TOP_LEFT).content)
            // Another corner: its choices are separate.
            val tiles = element().group("Ecke wählen").radios()
            tiles[1].click()
            element().group("Inhalt").radios()[2].click()
            assertEquals(OverlayContent.Library(logoId), changes.last().slot(OverlayCorner.TOP_RIGHT).content)
            val size = element().group("Größe")
            assertEquals(listOf("Klein", "Mittelgroß", "Groß"), size.radios().map { it.textContent?.trim() })
            assertEquals("true", size.radios()[1].getAttribute("aria-checked"), "medium is the default")
            size.radios()[2].click()
            assertEquals(OverlaySize.LARGE, changes.last().slot(OverlayCorner.TOP_RIGHT).size)
            val opacity = element().group("Deckkraft")
            assertEquals(listOf("100 %", "70 %", "40 %"), opacity.radios().map { it.textContent?.trim() })
            opacity.radios()[2].click()
            assertEquals(OverlayOpacity.LIGHT, changes.last().slot(OverlayCorner.TOP_RIGHT).opacity)
        }
    }

    @Test
    fun anArrowKeyInAGroupMovesAndChoosesLikeANativeRadio() {
        mounted { root, element ->
            val changes = mutableListOf<VideoOverlaySettings>()
            val section = root.section(onChange = { changes += it })
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            val size = element().group("Größe").radios()
            key(size[1], "ArrowRight")
            assertEquals(OverlaySize.LARGE, changes.last().slot(OverlayCorner.TOP_LEFT).size)
            key(size[1], "ArrowLeft")
            assertEquals(OverlaySize.SMALL, changes.last().slot(OverlayCorner.TOP_LEFT).size)
        }
    }

    @Test
    fun theFrameGroup_offersNoFrameAndTheFrames_andItsOpacity() {
        mounted { root, element ->
            val changes = mutableListOf<VideoOverlaySettings>()
            val section = root.section(onChange = { changes += it })
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            val frames = element().group("Rahmen")
            assertEquals(listOf("Kein Rahmen", "Goldrahmen"), frames.radios().map { it.textContent?.trim() })
            assertEquals("true", frames.radios()[0].getAttribute("aria-checked"))
            frames.radios()[1].click()
            assertEquals(frameId, changes.last().frame?.assetId)
            section.render(ui(settings = changes.last()))
            assertEquals("true", element().group("Rahmen").radios()[1].getAttribute("aria-checked"))
            element().group("Deckkraft des Rahmens").radios()[1].click()
            assertEquals(OverlayOpacity.STRONG, changes.last().frame?.opacity)
            element().group("Rahmen").radios()[0].click()
            assertNull(changes.last().frame)
        }
    }

    @Test
    fun anEmptyLibrary_stillOffersTheName_andSaysThatNothingIsStored() {
        mounted { root, element ->
            val section = root.section()
            section.render(ui(library = emptyList()))
            section.toggleButton!!.getElement()!!.click()
            assertEquals(listOf("Leer", "Ihr Name"), element().group("Inhalt").radios().map { it.textContent?.trim() })
            assertTrue(element().textContent.orEmpty().contains("noch keine Logos oder Rahmen"))
        }
    }

    @Test
    fun anElementThatIsDeletedMeanwhile_isShownAsEmptyInTheCornerTile() {
        mounted { root, element ->
            val section = root.section()
            val settings =
                VideoOverlaySettings(
                    enabled = true,
                    corners =
                        mapOf(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.Library(logoId)),
                        ),
                )
            section.render(ui(settings = settings, library = listOf(logo)))
            section.toggleButton!!.getElement()!!.click()
            assertEquals("Oben links: Vereinslogo", element().group("Ecke wählen").radios()[0].getAttribute("aria-label"))
            section.render(ui(settings = settings, library = emptyList()))
            assertEquals("Oben links: Leer", element().group("Ecke wählen").radios()[0].getAttribute("aria-label"))
        }
    }

    @Test
    fun theControlsKeepTheirSemantics_afterAnUnrelatedPatchOfTheScreen() {
        mounted { root, element ->
            val section = root.section()
            section.render(ui())
            section.toggleButton!!.getElement()!!.click()
            root.span("an unrelated widget of the call screen")
            root.span("and another one")
            val groups = element().all("[role=radiogroup]")
            assertEquals(6, groups.size, "corners, content, size, opacity, frame, frame opacity")
            groups.forEach { g -> assertTrue(!g.getAttribute("aria-label").isNullOrBlank()) }
            assertTrue(element().radios().all { !it.getAttribute("aria-checked").isNullOrBlank() })
            assertEquals(1, element().all("button[role=switch]").size)
        }
    }
}
