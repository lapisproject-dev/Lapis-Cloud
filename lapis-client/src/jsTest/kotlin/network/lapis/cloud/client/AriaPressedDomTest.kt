package network.lapis.cloud.client

import io.kvision.html.button
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.31 (W5): the conference view toggles (roster, chat, more, whiteboard, notes) set `aria-pressed` through KVision's
 * `Widget.setAttribute` instead of a raw `getElement()?.setAttribute`. What THIS test pins is the KVision behaviour that choice relies on
 * -- on a plain button in a mounted root: the attribute reaches the element, follows every later change and survives a further patch of the
 * root (which a raw attribute did not). It does NOT render the conference screen, and it says nothing about the conference's own attribute
 * writes (audit round: an earlier text claimed more, and named a "attributes module" that plays no part here).
 */
class AriaPressedDomTest {
    @Test
    fun setAttribute_updatesTheMountedElement_andSurvivesFurtherPatches() {
        withMountedRoot("aria-pressed-toggle") { root, element ->
            val toggle = root.button("Teilnehmende")
            toggle.setAttribute("aria-pressed", "false")
            val button = { element().querySelector("button") as HTMLElement }
            assertEquals("false", button().getAttribute("aria-pressed"))
            toggle.setAttribute("aria-pressed", "true")
            assertEquals("true", button().getAttribute("aria-pressed"))
            root.button("Chat") // another patch of the same root
            assertEquals("true", (element().querySelector("button") as HTMLElement).getAttribute("aria-pressed"))
        }
    }
}
