package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterises KVision 9.6.0's hook/key behaviour in a REAL, mounted [io.kvision.panel.Root] and proves
 * [addWithLifecycle]. The first block pins the trap the "late hooks" audit is about (it must stay green:
 * it documents what the library does, and turns red if a KVision upgrade changes it); the second block is
 * the fix idiom.
 */
class KvisionHookOrderDomTest {
    private fun mounted(block: (io.kvision.panel.Root, () -> HTMLElement) -> Unit) = withMountedRoot("kvision-hook-order-test", block)

    // ── The trap ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun lateDestroyHook_firesOnALiveWidget_afterAnUnrelatedPatch() {
        mounted { root, _ ->
            val widget = root.div("live")
            val before = assertNotNull(widget.getElement())
            var destroyed = 0
            widget.addAfterDestroyHook { destroyed++ } // AFTER the widget was rendered into a mounted tree
            assertEquals(0, destroyed, "nothing happens at registration time")

            root.span("unrelated") // any patch of the root

            assertEquals(1, destroyed, "destroy ran although the widget is still in the tree")
            assertNotSame(before, widget.getElement(), "and its DOM element was replaced")
            assertTrue(widget.getElement()!!.isConnected, "the widget itself is alive")
            // The trap is a one-off: the key is stable afterwards.
            val after = widget.getElement()
            root.span("second unrelated patch")
            assertSame(after, widget.getElement())
            assertEquals(1, destroyed)
        }
    }

    @Test
    fun lateInsertHook_neverFiresForTheOriginalElement_onlyForTheReplacement() {
        mounted { root, _ ->
            val widget = root.div("live")
            val original = assertNotNull(widget.getElement())
            val seen = mutableListOf<HTMLElement>()
            widget.addAfterInsertHook { vnode -> seen += vnode.elm as HTMLElement }
            assertTrue(seen.isEmpty(), "not fired at registration: the element was already inserted")

            root.span("unrelated")

            assertEquals(1, seen.size, "fired exactly once, for the replacement")
            assertNotSame(original, seen.single())
            assertSame(widget.getElement(), seen.single(), "the hook saw the element that is live now")
        }
    }

    @Test
    fun lateInsertHook_neverFiresAtAll_whenNoFurtherPatchHappens() {
        // The other half of "harmless only by accident": without a patch after the registration, a late
        // insert hook is silently dead.
        mounted { root, _ ->
            val widget = root.div("live")
            var fired = 0
            widget.addAfterInsertHook { fired++ }
            assertEquals(0, fired)
            assertNotNull(widget.getElement())
        }
    }

    @Test
    fun rawAttributeSetInALateInsertHook_landsOnTheLiveElement() {
        // The `setAttribute("role", "alert")` family: late registration is harmless there -- the attribute
        // ends up on the replacement, i.e. on the element that stays -- provided a later patch happens
        // (in the conference screen every following `add` is one).
        mounted { root, _ ->
            val widget = root.div("banner")
            widget.addAfterInsertHook { vnode -> (vnode.elm as? HTMLElement)?.setAttribute("role", "alert") }
            root.span("next widget of the screen")
            assertEquals("alert", widget.getElement()!!.getAttribute("role"))
            root.span("and another")
            assertEquals("alert", widget.getElement()!!.getAttribute("role"), "stable afterwards")
        }
    }

    @Test
    fun hookRegisteredBeforeTheFirstRender_isStableInARealRoot() {
        // The `getElement() ?: addAfterInsertHook { }` idiom of `setStaticA11yLabel`: no element yet, so
        // the key is set before the first vnode exists.
        mounted { root, _ ->
            val widget = Div("stable")
            assertNull(widget.getElement())
            var inserted = 0
            var destroyed = 0
            widget.addAfterInsertHook { inserted++ }
            widget.addAfterDestroyHook { destroyed++ }
            root.add(widget)
            val element = assertNotNull(widget.getElement())
            root.span("unrelated")
            assertSame(element, widget.getElement())
            assertEquals(1, inserted)
            assertEquals(0, destroyed)
        }
    }

    @Test
    fun hookInsideTheDslInitLambda_isRegisteredBeforeTheFirstRender() {
        // Answers the open question of the audit plan: the DSL runs `init` BEFORE `add`, so a hook set
        // inside it is early enough. (Hooks set on the RETURN value of the factory are not.)
        mounted { root, _ ->
            var destroyed = 0
            val widget = root.div("live") { addAfterDestroyHook { destroyed++ } }
            val element = assertNotNull(widget.getElement())
            root.span("unrelated")
            assertSame(element, widget.getElement())
            assertEquals(0, destroyed)
        }
    }

    // ── The fix idiom ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun addWithLifecycle_survivesAnUnrelatedPatch_andInsertsForTheOriginalElement() {
        mounted { root, _ ->
            var inserted = 0
            var destroyed = 0
            val widget = root.addWithLifecycle(Div("live"), onInsert = { inserted++ }, onDestroy = { destroyed++ })
            val element = assertNotNull(widget.getElement())
            assertEquals(1, inserted, "the insert hook ran for the very first element")

            root.span("unrelated")
            root.span("another")

            assertSame(element, widget.getElement(), "same DOM element")
            assertEquals(1, inserted)
            assertEquals(0, destroyed)
        }
    }

    @Test
    fun addWithLifecycle_runsDestroyExactlyOnce_whenTheWidgetReallyLeaves() {
        mounted { root, _ ->
            var destroyed = 0
            val widget = root.addWithLifecycle(Div("live"), onDestroy = { destroyed++ })
            root.remove(widget)
            assertEquals(1, destroyed)
        }
    }

    @Test
    fun addWithLifecycle_runsDestroyWhenAnEnclosingContainerIsEmptied() {
        // What `Routing.show` does with `pageContainer.removeAll()`: the widget is a grandchild, its
        // destroy hook must still fire (snabbdom recurses into the removed subtree).
        mounted { root, _ ->
            var destroyed = 0
            val screen = root.div { }
            val panel = SimplePanel()
            screen.add(panel)
            panel.addWithLifecycle(Div("deep"), onDestroy = { destroyed++ })
            root.removeAll()
            assertEquals(1, destroyed)
        }
    }

    @Test
    fun addWithLifecycle_reattachesAfterARootRestart() {
        // `I18n.language`'s setter restarts the root: destroy, then insert again on the same widget object.
        mounted { root, _ ->
            var inserted = 0
            var destroyed = 0
            val widget = root.addWithLifecycle(Div("live"), onInsert = { inserted++ }, onDestroy = { destroyed++ })
            assertEquals(1, inserted)

            root.restart()

            assertEquals(1, destroyed, "the old element was destroyed")
            assertEquals(2, inserted, "and the rebuilt one was inserted")
            assertTrue(widget.getElement()!!.isConnected)
        }
    }
}
