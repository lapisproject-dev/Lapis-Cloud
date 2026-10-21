package network.lapis.cloud.client

import io.kvision.html.button
import io.kvision.panel.vPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.ConferenceReaction
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.127 -- the reaction menu (roles, keyboard, Escape, the disabled state), the tile overlay (at most three, plain words) and the hand badge. */
class ConferenceReactionMenuDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun key(
        element: HTMLElement,
        key: String,
    ) = element.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))

    // -- pure --------------------------------------------------------------------------------------------------------------------------

    @Test
    fun theMenuKeysWrapAndHomeEndJump() {
        assertEquals(1, conferenceReactionMenuTarget(0, "ArrowDown", 5))
        assertEquals(0, conferenceReactionMenuTarget(4, "ArrowDown", 5))
        assertEquals(4, conferenceReactionMenuTarget(0, "ArrowUp", 5))
        assertEquals(0, conferenceReactionMenuTarget(3, "Home", 5))
        assertEquals(4, conferenceReactionMenuTarget(1, "End", 5))
        assertNull(conferenceReactionMenuTarget(1, "a", 5))
        assertNull(conferenceReactionMenuTarget(9, "ArrowDown", 5))
        assertEquals(0, conferenceReactionMenuTarget(7, "ArrowDown", 8))
        assertEquals(7, conferenceReactionMenuTarget(0, "ArrowUp", 8))
    }

    @Test
    fun everyReactionHasAnEmojiAndAWord_andNoHand() {
        assertEquals(
            listOf("Applaus", "Herz", "Amen", "Daumen hoch", "Blume", "Lachen", "Staunen", "Frage"),
            ConferenceReaction.entries.map { conferenceReactionLabel(it) },
        )
        assertTrue(ConferenceReaction.entries.all { conferenceReactionEmoji(it).isNotEmpty() })
    }

    // -- the menu ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun theMenuIsARealMenu_withEightNamedItems_andTheEmojiIsDecoration() =
        test {
            withMountedRoot("reaction-menu-roles") { root, element ->
                val panel = root.vPanel()
                val trigger = panel.button("Reaktion")
                val popover = ConferenceReactionPopover(panel, trigger, onChoose = {})
                popover.open()
                val menu = assertNotNull(element().querySelector("[role=menu]") as? HTMLElement)
                assertEquals("Reaktion", menu.getAttribute("aria-label"))
                val items = element().all("[role=menuitem]")
                assertEquals(8, items.size)
                assertEquals(
                    ConferenceReaction.entries.map {
                        conferenceReactionEmoji(it)
                    },
                    items.map { (it.querySelector(".lapis-conference-reaction-emoji") as HTMLElement).textContent },
                )
                assertEquals(
                    listOf("Applaus", "Herz", "Amen", "Daumen hoch", "Blume", "Lachen", "Staunen", "Frage"),
                    items.map { (it.querySelector(".lapis-conference-reaction-word") as HTMLElement).textContent },
                )
                items.forEach {
                    assertEquals(
                        "true",
                        (it.querySelector(".lapis-conference-reaction-emoji") as HTMLElement).getAttribute("aria-hidden"),
                    )
                }
                assertEquals("menu", trigger.getAttribute("aria-haspopup"))
                assertEquals("true", trigger.getAttribute("aria-expanded"))
                popover.dispose()
            }
        }

    @Test
    fun focusFollowsByScrolling() =
        test {
            withMountedRoot("reaction-menu-scroll") { root, element ->
                val panel = root.vPanel()
                val trigger = panel.button("Reaktion")
                val popover = ConferenceReactionPopover(panel, trigger, onChoose = {})
                popover.open()
                delay(20)
                val items = element().all("[role=menuitem]")
                var scrolled = 0
                items[7].asDynamic().scrollIntoView = { _: dynamic -> scrolled++ }
                key(items[0], "End")
                assertEquals(1, scrolled)
                popover.dispose()
            }
        }

    @Test
    fun aChoice_closesTheMenu_andReportsTheReaction() =
        test {
            withMountedRoot("reaction-menu-choose") { root, element ->
                val chosen = mutableListOf<ConferenceReaction>()
                val panel = root.vPanel()
                val trigger = panel.button("Reaktion")
                val popover = ConferenceReactionPopover(panel, trigger, onChoose = { chosen += it })
                popover.open()
                element().all("[role=menuitem]")[2].click()
                assertEquals(listOf(ConferenceReaction.AMEN), chosen)
                assertFalse(popover.isOpen)
                assertEquals("false", trigger.getAttribute("aria-expanded"))
            }
        }

    @Test
    fun escapeClosesTheMenu_andTheFocusGoesBackToTheButton() =
        test {
            withMountedRoot("reaction-menu-escape") { root, element ->
                val panel = root.vPanel()
                val trigger = panel.button("Reaktion")
                val popover = ConferenceReactionPopover(panel, trigger, onChoose = {})
                popover.open()
                delay(20)
                key(element().all("[role=menuitem]")[0], "Escape")
                assertFalse(popover.isOpen)
                assertEquals(trigger.getElement(), kotlinx.browser.document.activeElement)
            }
        }

    @Test
    fun arrowKeysMoveTheRovingFocus() =
        test {
            withMountedRoot("reaction-menu-arrows") { root, element ->
                val panel = root.vPanel()
                val trigger = panel.button("Reaktion")
                val popover = ConferenceReactionPopover(panel, trigger, onChoose = {})
                popover.open()
                delay(20)
                val items = element().all("[role=menuitem]")
                key(items[0], "ArrowDown")
                assertEquals(items[1], kotlinx.browser.document.activeElement)
                assertEquals(listOf("-1", "0", "-1", "-1", "-1", "-1", "-1", "-1"), items.map { it.getAttribute("tabindex") })
                key(items[1], "End")
                assertEquals(items[7], kotlinx.browser.document.activeElement)
                key(items[7], "ArrowDown")
                assertEquals(items[0], kotlinx.browser.document.activeElement)
                popover.dispose()
            }
        }

    @Test
    fun whenTheRoomSwitchedReactionsOff_theButtonIsAriaDisabled_andTheMenuDoesNotOpen() =
        test {
            withMountedRoot("reaction-menu-off") { root, _ ->
                var hint = 0
                val panel = root.vPanel()
                val trigger = panel.button("Reaktion")
                val popover = ConferenceReactionPopover(panel, trigger, onChoose = {}, onDisabledClick = { hint++ })
                popover.setEnabled(false)
                assertEquals("true", trigger.getAttribute("aria-disabled"))
                popover.toggle()
                assertFalse(popover.isOpen)
                assertEquals(1, hint)
                popover.setEnabled(true)
                assertNull(trigger.getAttribute("aria-disabled"))
                popover.toggle()
                assertTrue(popover.isOpen)
                // switching off while open closes it
                popover.setEnabled(false)
                assertFalse(popover.isOpen)
            }
        }

    // -- the tile ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun theOverlayShowsAtMostThree_inPlainWords_withoutALiveRegion() {
        val layer = createReactionLayer()
        assertEquals("true", layer.getAttribute("aria-hidden"))
        val scheduled = mutableListOf<() -> Unit>()
        val schedule: (Int, () -> Unit) -> Unit = { _, block -> scheduled += block }
        listOf(ConferenceReaction.APPLAUSE, ConferenceReaction.HEART, ConferenceReaction.AMEN, ConferenceReaction.FLOWER).forEach {
            showReactionInLayer(layer, it, schedule)
        }
        assertEquals(3, layer.childElementCount, "the oldest made room")
        assertEquals(
            listOf("Herz", "Amen", "Blume"),
            layer.all(".visually-hidden").map { it.textContent },
        )
        assertNull(layer.querySelector("[aria-live]"))
        assertNull(layer.querySelector("[role=status]"))
        assertNull(layer.querySelector("[role=alert]"))
        // the timer removes an item after its lifetime; an item that was already pushed out is not removed twice
        assertEquals(4, scheduled.size)
        scheduled.forEach { it() }
        assertEquals(0, layer.childElementCount)
    }

    @Test
    fun theHandBadge_isBuiltOnce_hasSymbolAndWords_andOnlyTogglesItsDisplay() {
        val badge = createHandBadge()
        assertEquals("none", badge.style.display)
        assertEquals("Hand gehoben", (badge.querySelector(".lapis-conference-hand-text") as HTMLElement).textContent)
        assertEquals("true", (badge.querySelector("i") as HTMLElement).getAttribute("aria-hidden"))
        val children = badge.childElementCount
        toggleHandBadge(badge, true)
        assertEquals("inline-flex", badge.style.display)
        toggleHandBadge(badge, false)
        assertEquals("none", badge.style.display)
        assertEquals(children, badge.childElementCount)
    }
}
