package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.forbiddenScrollers
import network.lapis.cloud.client.formTest
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.68 (R59, one scroll surface) on the real encounter room: the room is a MINIMUM height now (the page scrolls when there are many
 * benches), the benches have no scroller of their own, the bar wraps instead of scrolling sideways, the fullscreen room is the one
 * scroll root while it is up (ledger class E0). The stage size is untouched (`EncounterStageModeDomTest`).
 */
class EncounterScrollSurfaceDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private var clock = 1_000_000.0
    private val sixPeople = seatedCrowd()

    private suspend fun withRoom(block: suspend (HTMLElement) -> Unit) =
        withEncounterRoom(
            entry = testEntry(),
            peopleOf = { sixPeople },
            clock = { clock },
            space = testSpace(),
        ) { _, element -> block(element) }

    private fun HTMLElement.q(selector: String) = querySelector(selector) as HTMLElement

    private fun HTMLElement.isScroller(): Boolean = window.getComputedStyle(this).overflowY.let { it == "auto" || it == "scroll" }

    @Test
    fun theRoomAndItsBenches_haveNoScrollerOfTheirOwn_atEveryWidth(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom { element ->
                awaitUntil("six seats") { element.occupied() == 6 }
                val room = element.q(".lapis-encounter-room")
                assertFalse(element.q(".lapis-encounter-benches-frame").isScroller(), "the benches grow, the page scrolls")
                assertFalse(element.q(".lapis-encounter-controls").let { window.getComputedStyle(it).overflowX == "auto" }, "the bar wraps")
                val host = room.parentElement as HTMLElement
                listOf(360, 768, 1280).forEach { width ->
                    host.style.width = "${width}px"
                    delay(100)
                    assertEquals(emptyList(), forbiddenScrollers(room), "no scroller of its own at ${width}px")
                    assertTrue(room.scrollWidth <= room.clientWidth + 1, "no sideways scroll at ${width}px")
                }
            }
        }

    @Test
    fun theRoomHeight_isAMinimum_notAFixedBox(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom { element ->
                val room = element.q(".lapis-encounter-room")
                val style = window.getComputedStyle(room)
                assertTrue(
                    style.minHeight.removeSuffix("px").toDouble() >= 480.0,
                    "min-height keeps the old room height: ${style.minHeight}",
                )
                // A very tall bench area makes the room grow past its minimum instead of clipping or scrolling inside.
                val frame = element.q(".lapis-encounter-benches-frame")
                val before = room.getBoundingClientRect().height
                val filler = window.document.createElement("div") as HTMLElement
                filler.style.height = "2500px"
                frame.appendChild(filler)
                delay(100)
                assertTrue(
                    room.getBoundingClientRect().height >= before + 2000,
                    "the room grew with the benches: $before -> ${room.getBoundingClientRect().height}",
                )
                assertFalse(frame.isScroller(), "and the frame still does not scroll")
            }
        }

    @Test
    fun aFullscreenRoom_isTheScrollRoot_andClipsNothingFocusable(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom { element ->
                awaitUntil("six seats") { element.occupied() == 6 }
                val room = element.q(".lapis-encounter-room")
                room.classList.add("is-pseudo-fullscreen")
                delay(100)
                val style = window.getComputedStyle(room)
                assertEquals("fixed", style.position)
                assertEquals("auto", style.overflowY, "ledger class E0: the fullscreen room is the scroll root")
                assertEquals("contain", style.getPropertyValue("overscroll-behavior-y"))
                // Nothing focusable hides behind the clip edge of the stage (`.lapis-encounter` is `overflow: hidden`).
                val clip = element.q(".lapis-encounter").getBoundingClientRect()
                room
                    .allOf(
                        "button, a[href], input, select, textarea, [tabindex='0']",
                    ).filter { it.closest(".lapis-encounter") != null }
                    .forEach {
                        val box = it.getBoundingClientRect()
                        if (box.width > 0 && box.height > 0) {
                            assertTrue(
                                box.bottom <= clip.bottom + 1 && box.right <= clip.right + 1,
                                "${it.tagName}.${it.className} lies outside the clipped stage: ${box.bottom} > ${clip.bottom}",
                            )
                        }
                    }
                room.classList.remove("is-pseudo-fullscreen")
            }
        }
}
