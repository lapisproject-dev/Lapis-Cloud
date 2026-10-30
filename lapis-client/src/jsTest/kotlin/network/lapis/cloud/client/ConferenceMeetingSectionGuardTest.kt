package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.FocusEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.242 -- the guard on its own, with plain elements: when it draws at once, when it waits, and in which order the events release it. */
class ConferenceMeetingSectionGuardTest {
    private class Rig {
        val host = document.createElement("div") as HTMLElement
        val outside = document.createElement("button") as HTMLElement
        val inside = document.createElement("input") as HTMLElement
        var draws = 0
        val guards = SectionGuards()
        val guard = guards.add(SectionGuard({ host }) { draws++ })

        init {
            host.appendChild(inside)
            document.body!!.appendChild(host)
            document.body!!.appendChild(outside)
        }

        fun close() {
            guards.dispose()
            host.remove()
            outside.remove()
        }
    }

    private suspend fun tick() = kotlinx.coroutines.delay(30)

    @Test
    fun anIdleSection_isDrawnAtOnce(): Promise<Unit> =
        formTest {
            val rig = Rig()
            try {
                rig.guard.requestRender()
                assertEquals(1, rig.draws)
                assertFalse(rig.guard.pending)
            } finally {
                rig.close()
            }
        }

    @Test
    fun aFocusInside_defersTheDrawing_andFocusoutToTheOutsideReleasesIt(): Promise<Unit> =
        formTest {
            val rig = Rig()
            try {
                rig.inside.focus()
                rig.guard.requestRender()
                rig.guard.requestRender()
                assertEquals(0, rig.draws)
                assertTrue(rig.guard.pending)
                rig.outside.focus()
                rig.inside.dispatchEvent(FocusEvent("focusout", FocusEventInit(relatedTarget = rig.outside, bubbles = true)))
                assertEquals(1, rig.draws, "one drawing for any number of requests")
                assertFalse(rig.guard.pending)
            } finally {
                rig.close()
            }
        }

    @Test
    fun aFocusMovingWithinTheSection_doesNotRelease(): Promise<Unit> =
        formTest {
            val rig = Rig()
            try {
                val second = document.createElement("input") as HTMLElement
                rig.host.appendChild(second)
                rig.inside.focus()
                rig.guard.requestRender()
                second.focus()
                rig.inside.dispatchEvent(FocusEvent("focusout", FocusEventInit(relatedTarget = second, bubbles = true)))
                tick()
                assertEquals(0, rig.draws)
                assertTrue(rig.guard.pending)
            } finally {
                rig.close()
            }
        }

    @Test
    fun theClick_comesBeforeTheDrawing_afterAPointerPress(): Promise<Unit> =
        formTest {
            val rig = Rig()
            try {
                val order = mutableListOf<String>()
                val target = document.createElement("button") as HTMLElement
                rig.host.appendChild(target)
                target.addEventListener("click", { order += "click" })
                val guarded = SectionGuard({ rig.host }) { order += "render" }
                rig.guards.add(guarded)
                target.dispatchEvent(Event("pointerdown"))
                guarded.requestRender()
                assertEquals(emptyList(), order, "deferred while the pointer is down")
                target.dispatchEvent(Event("pointerup"))
                target.click()
                assertEquals(listOf("click"), order, "the click ran on the original node first")
                tick()
                assertEquals(listOf("click", "render"), order)
            } finally {
                rig.close()
            }
        }

    @Test
    fun renderNow_ignoresTheGuard(): Promise<Unit> =
        formTest {
            val rig = Rig()
            try {
                rig.inside.focus()
                rig.guard.requestRender()
                assertEquals(0, rig.draws)
                rig.guard.renderNow()
                assertEquals(1, rig.draws)
                assertFalse(rig.guard.pending)
            } finally {
                rig.close()
            }
        }

    @Test
    fun aDroppedDrawing_isNeverDone_andADisposedGuardsIgnoresEvents(): Promise<Unit> =
        formTest {
            val rig = Rig()
            try {
                rig.inside.focus()
                rig.guard.requestRender()
                rig.guard.drop()
                rig.outside.focus()
                rig.guards.flushAll()
                assertEquals(0, rig.draws)
                rig.inside.focus()
                rig.guard.requestRender()
                rig.guards.dispose()
                rig.outside.focus()
                rig.inside.dispatchEvent(FocusEvent("focusout", FocusEventInit(relatedTarget = rig.outside, bubbles = true)))
                tick()
                assertEquals(0, rig.draws)
            } finally {
                rig.close()
            }
        }
}

/** V1.9.242 -- the loading state of a button that has to fetch something before it can act ("Sitzung zuordnen"). */
class ButtonShowBusyDomTest {
    @Test
    fun busy_disablesMarksAndRenamesTheButton_andIdleRestoresIt(): Promise<Unit> =
        formTest {
            mountedForm("busy-button") { root, element ->
                val button = Button("Sitzung zuordnen", style = ButtonStyle.OUTLINEPRIMARY)
                root.add(button)
                awaitUntil("button") { element().querySelector("button") != null }
                val dom = assertNotNull(element().querySelector("button") as? HTMLElement)
                button.showBusy(true, busyText = "Sitzungen werden geladen …", idleText = "Sitzung zuordnen")
                awaitUntil("busy") { dom.getAttribute("aria-busy") == "true" }
                assertTrue(dom.hasAttribute("disabled"))
                assertTrue(dom.textContent.orEmpty().contains("Sitzungen werden geladen …"))
                assertTrue(dom.querySelector(".fa-spinner") != null, "a spinner")
                button.showBusy(false, busyText = "", idleText = "Sitzung zuordnen")
                awaitUntil("idle") { !dom.hasAttribute("aria-busy") }
                assertFalse(dom.hasAttribute("disabled"))
                assertEquals("Sitzung zuordnen", dom.textContent.orEmpty().trim())
                assertTrue(dom.querySelector(".fa-spinner") == null)
            }
        }
}
