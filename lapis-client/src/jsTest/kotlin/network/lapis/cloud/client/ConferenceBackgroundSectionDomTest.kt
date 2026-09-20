package network.lapis.cloud.client

import io.kvision.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The background-effect tile grid in a REAL, mounted `Root` (the "late hooks" audit).
 *
 * A tile's `role="radio"`/`aria-label` are raw attributes written straight onto the tile's element (see
 * `RawAttributes`), and never again after the build; the keyboard listener hangs on a hook registered
 * after the tile root was created. That WOULD be the late-hook trap -- the next patch would replace the
 * element with a fresh one carrying none of those attributes -- if the tile had been rendered already.
 * It is not: the section hides its group before building the tiles, and KVision does not render the
 * subtree of a hidden widget, so at registration time no tile has an element yet and the hook is early
 * enough. These tests pin that assumption (it silently breaks if someone builds the group visible).
 */
class ConferenceBackgroundSectionDomTest {
    private fun mounted(block: (io.kvision.panel.Root, () -> HTMLElement) -> Unit) = withMountedRoot("background-section-test", block)

    private fun HTMLElement.tiles(): List<HTMLElement> {
        val list = querySelectorAll(".lapis-conference-background-tile")
        return (0 until list.length).map { list.item(it) as HTMLElement }
    }

    private fun ConferenceBackgroundSection.open() {
        toggleButton.getElement()!!.click()
    }

    @Test
    fun tiles_keepTheirRadioSemantics_afterAnUnrelatedPatchOfTheScreen() {
        mounted { root, element ->
            val section = ConferenceBackgroundSection(root, ConferenceBackgroundAvailability.AVAILABLE) { }
            section.render(ConferenceBackgroundState())
            section.open()
            root.span("an unrelated widget of the call screen")
            root.span("and another one")

            val tiles = element().tiles()
            assertEquals(ConferenceBackgroundEffect.entries.size, tiles.size)
            tiles.forEach { tile ->
                assertEquals("radio", tile.getAttribute("role"), "every tile is a radio")
                assertTrue(!tile.getAttribute("aria-label").isNullOrBlank(), "with an accessible name")
            }
            val grid = assertNotNull(element().querySelector(".lapis-conference-background-grid"))
            assertEquals("radiogroup", grid.getAttribute("role"))
        }
    }

    @Test
    fun tiles_reactToTheKeyboardExactlyOnce_afterAnUnrelatedPatchOfTheScreen() {
        mounted { root, element ->
            val selected = mutableListOf<ConferenceBackgroundEffect>()
            val section = ConferenceBackgroundSection(root, ConferenceBackgroundAvailability.AVAILABLE) { selected += it }
            section.render(ConferenceBackgroundState())
            section.open()
            root.span("an unrelated widget of the call screen")

            val first = element().tiles().first()
            first.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true)))
            assertEquals(1, selected.size, "one Enter is one selection, not one per element generation")
        }
    }

    @Test
    fun tiles_keepTheirCheckedState_whenTheStateChangesAfterAPatch() {
        mounted { root, element ->
            val section = ConferenceBackgroundSection(root, ConferenceBackgroundAvailability.AVAILABLE) { }
            section.render(ConferenceBackgroundState())
            section.open()
            root.span("unrelated")
            section.render(
                ConferenceBackgroundState(desired = ConferenceBackgroundEffect.BLUR_LIGHT, applied = ConferenceBackgroundEffect.BLUR_LIGHT),
            )
            val checked = element().tiles().filter { it.getAttribute("aria-checked") == "true" }
            assertEquals(1, checked.size)
            assertEquals("0", checked.single().getAttribute("tabindex"))
        }
    }
}
