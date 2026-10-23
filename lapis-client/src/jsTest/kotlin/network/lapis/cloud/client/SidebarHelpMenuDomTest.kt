package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** V1.9.187: the "Hilfe und Feedback" menu of the sidebar in a real mounted root. */
class SidebarHelpMenuDomTest {
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

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        PageIntroStorage.expandAll()
    }

    @AfterTest
    fun tearDown() {
        PageIntroStorage.expandAll()
        SidebarHelpMenu.disposeCurrent()
    }

    private fun HTMLElement.trigger() = querySelector("button.lapis-sidebar-help__trigger") as HTMLElement

    private fun HTMLElement.items() =
        querySelectorAll("[role=menuitem]").let { list -> (0 until list.length).map { list[it] as HTMLElement } }

    private fun key(
        target: HTMLElement,
        key: String,
    ) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
    }

    private suspend inline fun <T> menu(
        route: String = Routes.CONTRIBUTIONS,
        noinline rerender: () -> Unit = {},
        block: (HTMLElement, SidebarHelpMenu) -> T,
    ): T =
        withMountedRoot("sidebar-help-menu") { root, element ->
            val panel = SimplePanel()
            root.add(panel)
            val menu = SidebarHelpMenu(panel, currentRoute = { route }, rerender = rerender)
            try {
                block(element(), menu)
            } finally {
                menu.dispose()
            }
        }

    @Test
    fun trigger_announcesAPopover_andTogglesExpanded(): Promise<Unit> =
        formTest {
            menu { element, _ ->
                val trigger = element.trigger()
                assertEquals("menu", trigger.getAttribute("aria-haspopup"))
                assertEquals("false", trigger.getAttribute("aria-expanded"))
                assertTrue(trigger.textContent.orEmpty().contains("Hilfe und Feedback"))
                trigger.click()
                delay(50)
                assertEquals("true", trigger.getAttribute("aria-expanded"))
                assertNotNull(element.querySelector("[role=menu]"))
                trigger.click()
                delay(50)
                assertEquals("false", trigger.getAttribute("aria-expanded"))
            }
        }

    @Test
    fun menu_alwaysOffersHelpCentreAndWhatsNew_asSafeNewTabLinks(): Promise<Unit> =
        formTest {
            menu { element, _ ->
                element.trigger().click()
                delay(50)
                val links = element.items().filter { it.tagName == "A" }
                assertEquals(listOf(Branding.helpIndexUrl(), Branding.whatsNewUrl()), links.map { it.getAttribute("href") })
                links.forEach { link ->
                    assertEquals("_blank", link.getAttribute("target"))
                    val rel = link.getAttribute("rel").orEmpty()
                    assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), rel)
                    assertEquals("true", (link.querySelector("[aria-hidden]") as HTMLElement).getAttribute("aria-hidden"))
                }
                val text = element.textContent.orEmpty()
                assertTrue(text.contains("Hilfe-Center") && text.contains("Was ist neu"))
            }
        }

    @Test
    fun menu_hasNoReportAProblemAndNoShortcutItem(): Promise<Unit> =
        formTest {
            menu { element, _ ->
                element.trigger().click()
                delay(50)
                val text = element.textContent.orEmpty()
                assertFalse(text.contains("Problem melden"))
                assertFalse(text.contains("Tastenkürzel"))
                assertEquals(2, element.items().size, "nothing hidden, no view topic: exactly the two website links")
            }
        }

    @Test
    fun viewHelp_appearsOnlyWhenTheCurrentViewHasATopicWithAPage(): Promise<Unit> =
        formTest {
            menu(route = Routes.TRAVEL_EXPENSES) { element, _ ->
                element.trigger().click()
                delay(50)
                val first = element.items().first()
                assertEquals(Branding.helpUrl("travel-expenses"), first.getAttribute("href"))
                assertTrue(first.textContent.orEmpty().contains("Hilfe zu dieser Ansicht"))
                assertEquals(3, element.items().size)
            }
            menu(route = Routes.CONTRIBUTIONS) { element, _ ->
                element.trigger().click()
                delay(50)
                assertFalse(element.textContent.orEmpty().contains("Hilfe zu dieser Ansicht"))
            }
            menu(route = "/begegnung/some-room-id") { element, _ ->
                element.trigger().click()
                delay(50)
                assertFalse(element.textContent.orEmpty().contains("Hilfe zu dieser Ansicht"), "a detail route has no view help")
            }
        }

    @Test
    fun resetItem_appearsOnlyWhenAnIntroIsCollapsed_clearsTheKeys_andRedraws(): Promise<Unit> =
        formTest {
            var redraws = 0
            menu(rerender = { redraws++ }) { element, menu ->
                element.trigger().click()
                delay(50)
                assertFalse(element.textContent.orEmpty().contains("Einführungen wieder aufklappen"))
                menu.close(restoreFocus = false)
                PageIntroStorage.collapse("contributions")
                menu.open()
                delay(50)
                val reset = element.items().single { it.textContent.orEmpty().contains("Einführungen wieder aufklappen") }
                assertEquals("BUTTON", reset.tagName)
                reset.click()
                delay(50)
                assertFalse(PageIntroStorage.anyCollapsed())
                assertEquals(1, redraws)
                assertFalse(menu.isOpen, "the menu closes after the choice")
            }
        }

    @Test
    fun keyboard_arrowsHomeEnd_escapeReturnsFocus_tabCloses(): Promise<Unit> =
        formTest {
            menu(route = Routes.TRAVEL_EXPENSES) { element, menu ->
                val trigger = element.trigger()
                trigger.click()
                delay(60)
                val items = element.items()
                assertSame(items[0], document.activeElement, "the first item takes the focus")
                key(items[0], "ArrowDown")
                assertSame(items[1], document.activeElement)
                key(items[1], "End")
                assertSame(items[2], document.activeElement)
                key(items[2], "ArrowDown")
                assertSame(items[0], document.activeElement, "arrows wrap")
                key(items[0], "ArrowUp")
                assertSame(items[2], document.activeElement)
                key(items[2], "Home")
                assertSame(items[0], document.activeElement)
                key(items[0], "Escape")
                delay(50)
                assertFalse(menu.isOpen)
                assertSame(trigger, document.activeElement, "Escape returns the focus to the trigger")
                trigger.click()
                delay(60)
                key(element.items()[0], "Tab")
                delay(50)
                assertFalse(menu.isOpen, "Tab closes the menu")
            }
        }

    @Test
    fun clickOutside_closesTheMenu(): Promise<Unit> =
        formTest {
            menu { element, menu ->
                element.trigger().click()
                delay(60)
                assertTrue(menu.isOpen)
                document.body!!.click()
                delay(50)
                assertFalse(menu.isOpen)
                assertEquals("false", element.trigger().getAttribute("aria-expanded"))
            }
        }

    @Test
    fun menu_isNotAScroller_andOpensUpward(): Promise<Unit> =
        formTest {
            menu { element, _ ->
                element.trigger().click()
                delay(60)
                val wrapper = element.querySelector(".lapis-sidebar-help") as HTMLElement
                val panel = element.querySelector("[role=menu]") as HTMLElement
                val panelStyle = kotlinx.browser.window.getComputedStyle(panel)
                assertEquals("absolute", panelStyle.getPropertyValue("position"))
                assertTrue(
                    panel.getBoundingClientRect().bottom <= wrapper.getBoundingClientRect().top + 1,
                    "the popover sits above its foot",
                )
                assertEquals(
                    "visible",
                    kotlinx.browser.window
                        .getComputedStyle(wrapper)
                        .getPropertyValue("overflow-y"),
                )
                assertNull(wrapper.getAttribute("style"))
            }
        }

    @Test
    fun pureTarget_wrapsAndJumps() {
        assertEquals(1, helpMenuTarget(0, "ArrowDown", 3))
        assertEquals(2, helpMenuTarget(0, "ArrowUp", 3))
        assertEquals(0, helpMenuTarget(2, "Home", 3))
        assertEquals(2, helpMenuTarget(0, "End", 3))
        assertNull(helpMenuTarget(0, "a", 3))
        assertNull(helpMenuTarget(0, "ArrowDown", 0))
    }

    @Test
    fun currentPathRoute_dropsTheQuery() {
        val before = kotlinx.browser.window.location.hash
        try {
            kotlinx.browser.window.location.hash = "#/travel-expenses?report=abc"
            assertEquals("/travel-expenses", currentPathRoute())
        } finally {
            kotlinx.browser.window.location.hash = before
        }
    }
}
