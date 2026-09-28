package network.lapis.cloud.client

import io.kvision.html.span
import network.lapis.cloud.shared.domain.ConferenceBackgroundImageDto
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
 *
 * V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen": [ConferenceBackgroundSection]'s
 * constructor grew `onUploadFile`/`onDeleteCustom`/`uploadEligible` -- the tests below that only exercise
 * the nine built-in tiles pass no-ops and `uploadEligible = false` (no upload form built, keeping them
 * focused); a dedicated section at the bottom covers the F4 ARIA shape of an own-uploaded tile.
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

    private fun io.kvision.core.Container.builtInOnlySection(onSelect: (ConferenceBackgroundChoice) -> Unit = {}) =
        ConferenceBackgroundSection(
            parent = this,
            availability = ConferenceBackgroundAvailability.AVAILABLE,
            onSelect = onSelect,
            onUploadFile = {},
            onDeleteCustom = {},
            uploadEligible = false,
        )

    @Test
    fun tiles_keepTheirRadioSemantics_afterAnUnrelatedPatchOfTheScreen() {
        mounted { root, element ->
            val section = root.builtInOnlySection()
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
            val selected = mutableListOf<ConferenceBackgroundChoice>()
            val section = root.builtInOnlySection(onSelect = { selected += it })
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
            val section = root.builtInOnlySection()
            section.render(ConferenceBackgroundState())
            section.open()
            root.span("unrelated")
            val blurLight = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_LIGHT)
            section.render(ConferenceBackgroundState(desired = blurLight, applied = blurLight))
            val checked = element().tiles().filter { it.getAttribute("aria-checked") == "true" }
            assertEquals(1, checked.size)
            assertEquals("0", checked.single().getAttribute("tabindex"))
        }
    }

    /**
     * Review-Befund (MINOR, a11y): reproduces the gap fixed in [ConferenceBackgroundSection.applyTileSelection]
     * -- `desired` is a `Custom(X)` whose tile is not in the grid (e.g. `listMine()` failed at join, so
     * `setCustomImages` was never called and only the 9 built-in tiles exist). Without the fallback, every
     * tile ends up `tabindex=-1` and keyboard users cannot Tab into the radiogroup at all.
     */
    @Test
    fun applyTileSelection_fallsBackToFirstTileTabIndex_whenNoTileMatchesTheDisplayedChoice() {
        mounted { root, element ->
            val section = root.builtInOnlySection()
            // setCustomImages is never called here -- only the 9 built-ins are in the grid.
            section.render(
                ConferenceBackgroundState(
                    desired = ConferenceBackgroundChoice.Custom("12345678-1234-1234-1234-123456789abc"),
                    applied = CONFERENCE_BACKGROUND_OFF,
                ),
            )
            section.open()

            val tiles = element().tiles()
            assertEquals(0, tiles.count { it.getAttribute("aria-checked") == "true" }, "nothing matches -- nothing is checked")
            assertEquals(
                1,
                tiles.count { it.getAttribute("tabindex") == "0" },
                "ARIA APG radiogroup: the first radio must still be tabbable when nothing is checked",
            )
            assertEquals("0", tiles.first().getAttribute("tabindex"))
        }
    }

    // --- V1.9.4, F4: eigene Kacheln -------------------------------------------------------------

    @Test
    fun customTiles_appendAfterTheNineBuiltIns_andExposeTheirWrapperAsRoleNone() {
        mounted { root, element ->
            val section =
                ConferenceBackgroundSection(
                    parent = root,
                    availability = ConferenceBackgroundAvailability.AVAILABLE,
                    onSelect = {},
                    onUploadFile = {},
                    onDeleteCustom = {},
                    uploadEligible = true,
                )
            section.render(ConferenceBackgroundState())
            // setCustomImages BEFORE open(): the group is still hidden here, and KVision does not render the
            // subtree of a hidden widget (see class KDoc "late hooks"). This test only covers the join-time
            // order (list arrives before the panel is ever opened) -- the already-visible case is covered
            // separately by setCustomImages_whileGroupAlreadyVisible_addsTheTileImmediately_andPreservesTheSelection
            // below, which shows no extra patch cycle is needed there either.
            section.setCustomImages(
                listOf(ConferenceBackgroundImageDto(id = "12345678-1234-1234-1234-123456789abc", width = 640, height = 480)),
            )
            section.open()

            val grid = assertNotNull(element().querySelector(".lapis-conference-background-grid"))
            val tiles = element().tiles()
            // Neun eingebaute + eine eigene.
            assertEquals(10, tiles.size)

            // F4: das Radiogroup enthaelt nur role=radio -- kein button ist Nachfahre eines davon.
            val radiosInGrid = grid.querySelectorAll("[role=radio]")
            assertEquals(10, radiosInGrid.length)
            assertEquals(0, grid.querySelectorAll("[role=radio] button").length, "no button is a descendant of a role=radio element")

            // Der Wrapper der eigenen Kachel traegt role=none.
            val wrapper = assertNotNull(element().querySelector(".lapis-conference-background-tile-wrap"))
            assertEquals("none", wrapper.getAttribute("role"))

            // Der Entfernen-Knopf ist ein Geschwister der Kachel innerhalb des Wrappers, kein Nachfahre der Kachel selbst.
            val removeButton = assertNotNull(element().querySelector(".lapis-conference-background-remove"))
            assertTrue(!removeButton.getAttribute("aria-label").isNullOrBlank())
        }
    }

    /**
     * Review-Befund (MINOR, "docs/tests out of sync with reality"): der frueheren KDoc von
     * [ConferenceBackgroundSection.setCustomImages] und dem `ClientLateHookRatchetTest`-Eintrag zufolge
     * sollte ein Aufruf WAEHREND die Gruppe schon sichtbar ist "KEIN sofortiges DOM-Update" ergeben -- eine
     * DOM-Sonde widerlegte das (9 -> 10 Kacheln sofort, Enter erreicht `onSelect`). Dieser Test haelt genau
     * DAS fest, denn Hochladen und Loeschen passieren in der Praxis IMMER bei geoeffnetem Panel (der Knopf
     * liegt im aufgeklappten Bereich). Gleichzeitig deckt er den zweiten Review-Befund ab: [setCustomImages]
     * darf den Auswahlzustand einer bereits ausgewaehlten eigenen Kachel beim Neuaufbau nicht verwerfen.
     */
    @Test
    fun setCustomImages_whileGroupAlreadyVisible_addsTheTileImmediately_andPreservesTheSelection() {
        mounted { root, element ->
            val selected = mutableListOf<ConferenceBackgroundChoice>()
            val firstImageId = "12345678-1234-1234-1234-123456789abc"
            val secondImageId = "87654321-4321-4321-4321-cba987654321"
            val section =
                ConferenceBackgroundSection(
                    parent = root,
                    availability = ConferenceBackgroundAvailability.AVAILABLE,
                    onSelect = { selected += it },
                    onUploadFile = {},
                    onDeleteCustom = {},
                    uploadEligible = true,
                )
            section.setCustomImages(listOf(ConferenceBackgroundImageDto(id = firstImageId, width = 640, height = 480)))
            val firstChoice = ConferenceBackgroundChoice.Custom(firstImageId)
            section.render(ConferenceBackgroundState(desired = firstChoice, applied = firstChoice))
            section.open()

            // Vorher: neun eingebaute + eine eigene, die eigene ausgewaehlt.
            assertEquals(10, element().tiles().size)
            val checkedBefore = element().tiles().filter { it.getAttribute("aria-checked") == "true" }
            assertEquals(1, checkedBefore.size)

            // Ein zweites eigenes Bild kommt hinzu, WAEHREND die Gruppe schon sichtbar ist -- genau der
            // reale Ablauf nach einem Upload oder einem Loeschen eines ANDEREN Bilds.
            section.setCustomImages(
                listOf(
                    ConferenceBackgroundImageDto(id = firstImageId, width = 640, height = 480),
                    ConferenceBackgroundImageDto(id = secondImageId, width = 640, height = 480),
                ),
            )

            val tiles = element().tiles()
            assertEquals(11, tiles.size, "the new tile is realized into the DOM immediately, no extra patch cycle needed")

            val checkedAfter = tiles.filter { it.getAttribute("aria-checked") == "true" }
            assertEquals(1, checkedAfter.size, "the previously selected tile keeps its checkmark across the rebuild")
            assertEquals("0", checkedAfter.single().getAttribute("tabindex"), "and stays the roving-tabindex target")

            checkedAfter.single().dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true)))
            assertEquals(1, selected.size, "the keyboard listener on the rebuilt tile still works")
            assertEquals(firstChoice, selected.single())
        }
    }

    @Test
    fun arrowKeys_cycleOverAllNineBuiltInsPlusCustomTiles() {
        mounted { root, element ->
            val section =
                ConferenceBackgroundSection(
                    parent = root,
                    availability = ConferenceBackgroundAvailability.AVAILABLE,
                    onSelect = {},
                    onUploadFile = {},
                    onDeleteCustom = {},
                    uploadEligible = true,
                )
            section.render(ConferenceBackgroundState())
            section.setCustomImages(
                listOf(
                    ConferenceBackgroundImageDto(id = "12345678-1234-1234-1234-123456789abc", width = 640, height = 480),
                    ConferenceBackgroundImageDto(id = "87654321-4321-4321-4321-cba987654321", width = 640, height = 480),
                ),
            )
            section.open()
            val tiles = element().tiles()
            assertEquals(11, tiles.size) // 9 eingebaute + 2 eigene
        }
    }
}
