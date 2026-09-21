package network.lapis.cloud.client

import io.kvision.form.text.Text
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Evidence for the "late hooks" audit's HARMLESS verdicts: sites where a hook is registered after the widget
 * was rendered into a mounted tree, but where the outcome is correct anyway. Each test pins the reason, so
 * a change of the surrounding code that removes it turns the verdict red instead of silently making it wrong.
 * (The generic mechanism itself is pinned in [KvisionHookOrderDomTest].)
 */
class LateHookAuditDomTest {
    private fun mounted(block: (io.kvision.panel.Root, () -> HTMLElement) -> Unit) = withMountedRoot("late-hook-audit-test", block)

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    // ── raw child created in a late insert hook (video grid/stage zones, whiteboard canvas, recording <video>, badges) ──

    @Test
    fun rawChildCreatedInALateInsertHook_existsExactlyOnceOnTheLiveElement() {
        mounted { root, _ ->
            val host = root.div { }
            var fired = 0
            host.addAfterInsertHook { vnode ->
                fired++
                (vnode.elm as HTMLElement).appendChild(document.createElement("canvas"))
            }
            root.span("next widget of the screen") // the patch that swaps the element and fires the hook
            root.span("and more patches")
            root.span("and more patches")

            assertEquals(1, fired, "the hook ran once, for the element that stays")
            assertEquals(1, host.getElement()!!.querySelectorAll("canvas").length, "exactly one raw child, on the live element")
        }
    }

    @Test
    fun rawChildOfALateHook_isMissingUntilSomePatchHappens() {
        // The one way a late insert hook is NOT harmless: nothing patches the tree after it was registered.
        // Documented, not asserted as desirable -- every audited site is followed by more widgets.
        mounted { root, _ ->
            val host = root.div { }
            host.addAfterInsertHook { vnode -> (vnode.elm as HTMLElement).appendChild(document.createElement("canvas")) }
            assertEquals(0, host.getElement()!!.querySelectorAll("canvas").length)
            root.span("the patch")
            assertEquals(1, host.getElement()!!.querySelectorAll("canvas").length)
        }
    }

    // ── attributes / listeners written by a late hook onto the replacement ─────────────────────────────────────────────────

    @Test
    fun inputAttributesAndListenerOfALateHook_endUpOnTheLiveInput() {
        mounted { root, _ ->
            val row = root.div { }
            val input = Text()
            row.add(input)
            var enter = 0
            row.addAfterInsertHook { vnode ->
                val el = (vnode.elm as HTMLElement).querySelector("input") as HTMLInputElement
                el.setAttribute("autocomplete", "new-password")
                el.addEventListener("keydown", { e -> if ((e as? KeyboardEvent)?.key == "Enter") enter++ })
                el.focus()
            }
            root.span("next")
            val live = row.getElement()!!.querySelector("input") as HTMLInputElement
            assertEquals("new-password", live.getAttribute("autocomplete"))
            assertSame(live, document.activeElement, "focus() in the hook reaches the element that stays")
            live.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter")))
            assertEquals(1, enter, "one listener on one element -- no duplicate from the discarded generation")
        }
    }

    // ── widgets built in a tree that is not (yet) mounted have no element: their hooks are early ──────────────────────────

    @Test
    fun hookOnAWidgetInADetachedTree_isEarly_andFiresOnceWhenTheTreeIsMounted() {
        // The modal pattern (BankAccountsScreen's `hardenSecretInput`): everything is built first, `show()` /
        // `add` comes last, so the hook is registered before the widget has ever been rendered.
        mounted { root, _ ->
            val panel = SimplePanel()
            val row = Div("row")
            panel.add(row)
            assertTrue(row.getElement() == null, "nothing is rendered in a detached tree")
            var fired = 0
            row.addAfterInsertHook { fired++ }
            root.add(panel)
            val element = assertNotNull(row.getElement())
            root.span("unrelated")
            assertEquals(1, fired)
            assertSame(element, row.getElement())
        }
    }

    @Test
    fun hiddenSubtree_isNotRendered_soHooksRegisteredInsideItAreEarly() {
        // `ConferenceBackgroundSection` hides its group before building the tiles; `RawAttributes` and the
        // tile keyboard hook rely on it (see `ConferenceBackgroundSectionDomTest`). The same goes for a
        // widget that is hidden first and hooked second (the conference banners): a hidden widget has no
        // element at all, so its key is set before its first vnode exists.
        mounted { root, _ ->
            val group = root.div { }
            group.hide()
            assertTrue(group.getElement() == null, "a hidden widget has no element")
            val child = group.div("child")
            assertTrue(child.getElement() == null, "no element for a child of a hidden widget")
            var fired = 0
            child.addAfterInsertHook { fired++ }
            group.show()
            root.span("unrelated")
            assertEquals(1, fired, "fired once, when the group was shown -- and not again for a replacement")
            assertTrue(child.getElement()!!.isConnected)
        }
    }

    // ── DataTable sort header button (`singleRender` keeps the button unrendered when its hook is registered) ───────────────

    private data class Person(
        val name: String,
    )

    private fun io.kvision.core.Container.sortableTable(
        viewport: FakeNarrowViewport,
        focusSortKey: String?,
    ): DataTablePanel =
        dataTableWith(
            columns = listOf(textColumn<Person>(title = "Name", primary = true, sortKey = "name") { it.name }),
            rows = listOf(Person("Ada")),
            sort = SortState("name", SortDirection.ASC),
            onSort = {},
            sortOptions = SortOptions(),
            actions = null,
            focusSortKey = focusSortKey,
            viewport = viewport,
        ) as DataTablePanel

    @Test
    fun sortButton_getsTheFocusHandOver_andSurvivesAnUnrelatedPatch() {
        mounted { root, element ->
            val viewport = FakeNarrowViewport(narrow = true) // cards first: no sort button yet, focus request stays pending
            root.sortableTable(viewport, focusSortKey = "name")
            assertEquals(0, element().querySelectorAll("th button").length)

            viewport.change(false) // re-render of a MOUNTED host: the buttons are created inside `singleRender`
            val button = assertNotNull(element().querySelector("th button") as? HTMLElement)
            assertSame(button, document.activeElement, "the focus request was consumed by the button that is live")

            root.span("unrelated")
            assertSame(button, element().querySelector("th button"), "same button element: no key change, no replacement")
            assertSame(button, document.activeElement, "and it still has the focus")
        }
    }

    // ── real code, fixed: the conference title rename input ─────────────────────────────────────────────────────────────────

    @Test
    fun titleEditInput_isFocusedAndSubmitsOnEnter_withoutAnyFurtherPatch() {
        // The rename input's hook used to be registered after the input was rendered, as the last statement of
        // the edit mode: no patch followed, so neither the autofocus nor the Enter listener existed until an
        // unrelated widget patched the screen.
        mounted { root, element ->
            var submits = 0
            root.titleEditInput("Weekly") { submits++ }
            val input = assertNotNull(element().querySelector("input") as? HTMLInputElement)
            assertSame(input, document.activeElement, "autofocus right away")
            input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true)))
            assertEquals(1, submits, "Enter submits right away")
        }
    }

    @Test
    fun titleEditInput_staysTheSameElement_andSubmitsOncePerEnter_afterUnrelatedPatches() {
        mounted { root, element ->
            var submits = 0
            root.titleEditInput("Weekly") { submits++ }
            val input = element().querySelector("input") as HTMLInputElement
            root.span("a chat message arrived")
            root.span("the roster changed")
            assertSame(input, element().querySelector("input"), "no element replacement while the user types")
            assertSame(input, document.activeElement, "and no lost focus")
            input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true)))
            assertEquals(1, submits)
        }
    }

    // ── real screen: EventCheckInScreen's code row ─────────────────────────────────────────────────────────────────────────

    @Test
    fun eventCheckInCodeField_isPreparedAndFocused_inTheRealScreen(): Promise<Unit> =
        test {
            withMountedRoot("late-hook-audit-checkin") { root, element ->
                renderEventCheckInScreen(root, "00000000-0000-0000-0000-000000000000")
                delay(300) // roster RPC failed under Karma and patched the screen once more
                val input = assertNotNull(element().querySelector("input[placeholder='ABCD-EFGH-JKMN-PQRS']") as? HTMLInputElement)
                assertEquals("characters", input.getAttribute("autocapitalize"))
                assertEquals("off", input.getAttribute("autocomplete"))
                assertSame(input, document.activeElement, "autofocus landed on the field that is live")
                input.value = "garbage"
                input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true)))
                // W4b: Enter reaches the submit handler through the field's own `keydown` listener, and the local pre-check now
                // reports at the FIELD (no round-trip, no banner).
                assertTrue(
                    element().querySelector(".lapis-field-error--shown") != null,
                    "Enter reaches the submit handler and reports at the field",
                )
            }
        }
}
