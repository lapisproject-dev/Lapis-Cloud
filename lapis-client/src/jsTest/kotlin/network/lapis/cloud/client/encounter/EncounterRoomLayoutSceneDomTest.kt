package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.allowedFor
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.152 (stage 2c) -- the room template in the running room: the one spoken sentence, every drawing `aria-hidden`, "Szene aus" takes the drawings,
 * the table drawing and the sentence with it, the high-contrast rules hide the drawings, and above all: the template never changes WHO sits WHERE or how a
 * seat is named -- seat ids and accessible names are identical in every template (it is decoration around the same operation).
 */
class EncounterRoomLayoutSceneDomTest {
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

    private var clock = 1_000_000.0
    private val sixPeople = seatedCrowd()

    private suspend fun withRoom(
        profile: EncounterProfile,
        layout: EncounterRoomLayout,
        block: suspend (HTMLElement) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        withEncounterRoom(
            entry = testEntry(),
            peopleOf = { sixPeople },
            clock = { clock },
            space = testSpace(profile = profile, roomLayout = layout),
        ) { _, element ->
            awaitUntil("six seats occupied") {
                element.allOf(".lapis-encounter-seat").count { it.getAttribute("aria-disabled") == "true" } >=
                    6
            }
            block(element)
        }
    }

    private fun HTMLElement.seatSnapshot(): List<String> =
        allOf(".lapis-encounter-seat").map {
            "${it.getAttribute("aria-label")}|${it.getAttribute("aria-disabled")}|${it.getAttribute("data-seat")}"
        }

    private val combinations =
        EncounterProfile.entries.flatMap { profile -> EncounterRoomLayout.entries.filter { it.allowedFor(profile) }.map { profile to it } }

    @Test
    fun everyTemplate_saysItsNameInOneSentence_thatIsNotALiveRegion_andIsFirstInTheStage(): Promise<Unit> =
        formTest {
            combinations.forEach { (profile, layout) ->
                withRoom(profile, layout) { element ->
                    val stage = assertNotNull(element.querySelector(".lapis-encounter-stage") as? HTMLElement)
                    val sentence = assertNotNull(stage.querySelector(".lapis-encounter-room-sentence") as? HTMLElement)
                    assertEquals(layoutRoomSentence(layout), sentence.textContent)
                    assertTrue(stage.firstElementChild === sentence, "$layout: the sentence is the first element of the stage")
                    assertNull(sentence.closest("[aria-live]"), "$layout: the sentence is read, never announced")
                    assertNull(sentence.getAttribute("aria-live"))
                    assertNull(sentence.getAttribute("aria-hidden"), "$layout: it is the one thing the screen reader gets")
                }
            }
        }

    @Test
    fun everyDrawing_isInsideAnAriaHiddenLayer_andTheRowsAndTheTemplateClassFollowTheTemplate(): Promise<Unit> =
        formTest {
            combinations.forEach { (profile, layout) ->
                withRoom(profile, layout) { element ->
                    val spec = EncounterRoomLayouts.spec(layout)
                    val layer = assertNotNull(element.querySelector(".lapis-encounter-scene-layer") as? HTMLElement)
                    assertEquals("true", layer.getAttribute("aria-hidden"))
                    val drawings = layer.allOf(".lapis-encounter-scene-object")
                    assertEquals(spec.objects.size, drawings.size, "$layout: one element per placed drawing")
                    assertTrue(drawings.size in 1..ENCOUNTER_LAYOUT_MAX_OBJECTS)
                    assertEquals(
                        drawings.size,
                        element.allOf(".lapis-encounter-scene-object").size,
                        "no drawing outside the aria-hidden layer",
                    )
                    drawings.forEach {
                        assertTrue(
                            it.style.getPropertyValue("--lapis-enc-object").contains("/assets/encounter-objects-v1/"),
                        )
                    }
                    val rows = assertNotNull(element.querySelector(".lapis-encounter-scene-rows") as? HTMLElement)
                    assertTrue(rows.style.getPropertyValue("--lapis-enc-scene-row").contains("/${spec.row.file}.svg"))
                    assertNotNull(
                        element.querySelector(".lapis-encounter--layout-${layoutCssKey(layout)}"),
                        "$layout: the root carries the template class",
                    )
                    val tablesHost = assertNotNull(element.querySelector(".lapis-encounter-tables-host") as? HTMLElement)
                    assertTrue(tablesHost.style.getPropertyValue("--lapis-enc-table-art").contains("/${spec.table.file}.svg"))
                }
            }
        }

    @Test
    fun szeneAus_takesTheDrawings_theTableDrawingAndTheSentenceWithIt(): Promise<Unit> =
        formTest {
            withRoom(EncounterProfile.ASSEMBLY, EncounterRoomLayout.FOYER) { element ->
                val layer = assertNotNull(element.querySelector(".lapis-encounter-scene-layer") as? HTMLElement)
                val rows = assertNotNull(element.querySelector(".lapis-encounter-scene-rows") as? HTMLElement)
                val sentence = assertNotNull(element.querySelector(".lapis-encounter-room-sentence") as? HTMLElement)
                assertTrue(layer.isShown() && rows.isShown(), "the scene is on")
                element.barControl("Szene ausblenden").click()
                awaitUntil("the scene is off") { element.querySelector(".lapis-encounter--scene-off") != null }
                assertFalse(layer.isShown() || rows.isShown(), "every drawing layer leaves the picture")
                assertEquals("none", window.getComputedStyle(sentence).display, "the sentence belongs to the scene")
                element.barControl("Szene ausblenden").click()
                awaitUntil("the scene is back") { element.querySelector(".lapis-encounter--scene-off") == null }
                assertTrue(layer.isShown() && sentence.textContent == layoutRoomSentence(EncounterRoomLayout.FOYER))
            }
        }

    @Test
    fun theSeatsAndTheirNames_areIdenticalInEveryTemplateOfAProfile(): Promise<Unit> =
        formTest {
            EncounterProfile.entries.forEach { profile ->
                val snapshots = mutableMapOf<EncounterRoomLayout, List<String>>()
                EncounterRoomLayout.entries.filter { it.allowedFor(profile) }.forEach { layout ->
                    withRoom(profile, layout) { element -> snapshots[layout] = element.seatSnapshot() }
                }
                val first = snapshots.values.first()
                assertTrue(first.size >= 6, "the snapshot is not vacuous: ${first.size} seats")
                snapshots.forEach { (layout, snapshot) -> assertEquals(first, snapshot, "$profile / $layout: same seats, same names") }
            }
        }

    @Test
    fun theHighContrastRules_hideEveryDrawingLayer_butNotTheSentence(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val hiding = mutableListOf<String>()
            val sheets = document.styleSheets
            for (i in 0 until sheets.length) {
                val rules: dynamic =
                    try {
                        sheets.item(i).asDynamic().cssRules
                    } catch (e: Throwable) {
                        null
                    }
                if (rules == null || rules == undefined) continue
                for (j in 0 until (rules.length as Int)) {
                    val rule: dynamic = rules[j]
                    val condition = rule.conditionText as? String ?: continue
                    if (!condition.contains("forced-colors: active") || !condition.contains("prefers-contrast: more")) continue
                    val inner: dynamic = rule.cssRules
                    for (k in 0 until (inner.length as Int)) {
                        val styleRule: dynamic = inner[k]
                        if (styleRule.style.display == "none") hiding += (styleRule.selectorText as String)
                    }
                }
            }
            val selectors = hiding.joinToString(",")
            assertTrue(selectors.contains(".lapis-encounter-scene-layer"), "the drawings are hidden in high contrast: $selectors")
            assertTrue(
                selectors.contains(".lapis-encounter-scene,") || selectors.contains(".lapis-encounter-scene "),
                "the row layer too: $selectors",
            )
            assertFalse(selectors.contains("room-sentence"), "the sentence stays for people who use a screen reader: $selectors")
        }
}
