package network.lapis.cloud.client

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.206: the tab widget after the WAI-ARIA tabs pattern. */
class PageTabsDomTest {
    private fun HTMLElement.tab(id: String) = querySelector("#$id") as HTMLElement

    private fun key(
        target: HTMLElement,
        key: String,
    ) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
    }

    private val tabs =
        listOf(
            PageTab(LedgerTab.SETUP, "Einrichtung", "t-setup", "p-setup"),
            PageTab(LedgerTab.ACCOUNTS, "Konten", "t-accounts", "p-accounts"),
            PageTab(LedgerTab.JOURNAL, "Journal", "t-journal", "p-journal"),
        )

    @Test
    fun rolesAndAttributes_andOnlyTheSelectedPanelIsVisible(): Promise<Unit> =
        formTest {
            mountedForm("page-tabs-roles") { root, element ->
                val rendered = mutableListOf<LedgerTab>()
                root.pageTabs(tabs, LedgerTab.ACCOUNTS, "Bereiche") { tab, _ -> rendered += tab }
                val el = element()
                awaitUntil("tabs exist") { el.querySelector("[role=tablist]") != null }
                assertEquals("Bereiche", el.querySelector("[role=tablist]")?.getAttribute("aria-label"))
                assertEquals(3, el.allOf("[role=tab]").size)
                assertEquals("true", el.tab("t-accounts").getAttribute("aria-selected"))
                assertEquals("false", el.tab("t-setup").getAttribute("aria-selected"))
                assertEquals("0", el.tab("t-accounts").getAttribute("tabindex"))
                assertEquals("-1", el.tab("t-setup").getAttribute("tabindex"))
                assertEquals("p-accounts", el.tab("t-accounts").getAttribute("aria-controls"))
                val panel = el.querySelector("#p-accounts") as HTMLElement
                assertEquals("tabpanel", panel.getAttribute("role"))
                assertEquals("t-accounts", panel.getAttribute("aria-labelledby"))
                assertTrue(panel.getAttribute("hidden") == null)
                assertTrue((el.querySelector("#p-setup") as HTMLElement).hasAttribute("hidden"))
                assertTrue((el.querySelector("#p-journal") as HTMLElement).hasAttribute("hidden"))
                assertEquals(listOf(LedgerTab.ACCOUNTS), rendered)
            }
        }

    @Test
    fun arrowKeysWrapAround_homeAndEndJump_andTheSelectionFollowsTheFocus(): Promise<Unit> =
        formTest {
            mountedForm("page-tabs-keys") { root, element ->
                val handle = root.pageTabs(tabs, LedgerTab.SETUP, "Bereiche") { _, _ -> }
                val el = element()
                awaitUntil("tabs exist") { el.querySelector("#t-setup") != null }
                key(el.tab("t-setup"), "ArrowLeft")
                assertEquals(LedgerTab.JOURNAL, handle.selected, "wraps from the first to the last")
                assertEquals("true", el.tab("t-journal").getAttribute("aria-selected"))
                key(el.tab("t-journal"), "ArrowRight")
                assertEquals(LedgerTab.SETUP, handle.selected, "wraps from the last to the first")
                key(el.tab("t-setup"), "End")
                assertEquals(LedgerTab.JOURNAL, handle.selected)
                key(el.tab("t-journal"), "Home")
                assertEquals(LedgerTab.SETUP, handle.selected)
                key(el.tab("t-setup"), "x")
                assertEquals(LedgerTab.SETUP, handle.selected, "other keys do nothing")
            }
        }

    @Test
    fun replaceState_changesTheAddressWithoutAHashchange(): Promise<Unit> =
        formTest {
            var hashchanges = 0
            val listener: (org.w3c.dom.events.Event) -> Unit = { hashchanges++ }
            window.addEventListener("hashchange", listener)
            try {
                val before = window.location.href
                window.history.replaceState(null, "", ledgerHash(LedgerTab.JOURNAL))
                assertTrue(window.location.hash.contains("tab=journal"))
                // give a hashchange the chance to arrive
                kotlinx.coroutines.delay(100)
                assertEquals(0, hashchanges)
                window.history.replaceState(null, "", before)
            } finally {
                window.removeEventListener("hashchange", listener)
            }
        }
}
