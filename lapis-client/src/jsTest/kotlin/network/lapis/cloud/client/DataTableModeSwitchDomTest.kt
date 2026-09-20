package network.lapis.cloud.client

import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.ContainerType
import io.kvision.panel.Root
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shared fake viewport of the `dataTable` tests: a [NarrowViewportSource] without a real browser resize. */
internal class FakeNarrowViewport(
    var narrow: Boolean,
) : NarrowViewportSource {
    val listeners = mutableListOf<(Boolean) -> Unit>()
    override val matches: Boolean get() = narrow

    override fun subscribe(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun change(value: Boolean) {
        narrow = value
        listeners.toList().forEach { it(value) }
    }
}

/**
 * The mode switch of [dataTable] in a REAL, mounted KVision [Root] -- Karma runs these tests in a real
 * Chrome, so `refresh()`'s `getRoot()?.reRender()` really patches the document here and the snabbdom
 * create/insert/destroy hooks really fire.
 *
 * Why this file exists next to `DataTableWidgetTest` (V1.4.25 bug "table stays on a narrow resize"):
 * that test builds the panel in a bare `SimplePanel()`, which never reaches a `Root`. `getRoot()` is
 * `null` there, so every `refresh()` is a silent no-op, no vnode is ever created and NO hook ever runs --
 * `modeSwitch_rendersAgainOnlyOnARealFlip` therefore counted `renderCount` in a widget tree that the
 * snabbdom patch cycle never touched, and could not see that a real patch tore the host out of the
 * document and released the media-query listener. These tests assert on the DOM instead.
 */
class DataTableModeSwitchDomTest {
    private data class Person(
        val name: String,
        val amount: String,
    )

    private val people = listOf(Person(name = "Ada", amount = "12,00"), Person(name = "Grace", amount = "3,50"))

    private fun columns() =
        listOf(
            textColumn<Person>(title = tr("Name"), primary = true, sortKey = "name") { it.name },
            textColumn<Person>(title = tr("Betrag"), numeric = true) { it.amount },
        )

    /**
     * Mounts a real [Root] on a fresh `div` in the document and disposes both afterwards. The element
     * handed to [block] is resolved lazily on every call: KVision's `Root` constructor renders its vnode
     * with the element name as `nodeName` gives it (`DIV`, upper case) while snabbdom's `emptyNodeAt`
     * derives `div` from the existing element -- the selectors differ, so the very first patch REPLACES
     * the container element. A handle captured before that is detached and finds nothing.
     */
    private fun withMountedRoot(block: (Root, () -> HTMLElement) -> Unit) {
        val id = "data-table-mode-switch-test"
        val container = document.createElement("div") as HTMLElement
        container.id = id
        document.body!!.appendChild(container)
        val root = Root(id = id, containerType = ContainerType.NONE, addRow = false)
        try {
            block(root) { document.getElementById(id) as HTMLElement }
        } finally {
            root.dispose()
            document.getElementById(id)?.remove()
        }
    }

    private fun Root.build(viewport: FakeNarrowViewport): DataTablePanel =
        dataTableWith(
            columns = columns(),
            rows = people,
            sort = SortState("name", SortDirection.ASC),
            onSort = {},
            sortOptions = SortOptions(),
            actions = null,
            focusSortKey = null,
            viewport = viewport,
        ) as DataTablePanel

    private fun HTMLElement.hasTable(): Boolean = querySelector("table") != null

    private fun HTMLElement.cardCount(): Int = querySelectorAll(".lapis-data-card").length

    @Test
    fun modeSwitch_inARealRoot_flipsBackAndForthRepeatedly() {
        withMountedRoot { root, element ->
            val viewport = FakeNarrowViewport(narrow = false)
            root.build(viewport)
            assertTrue(element().hasTable(), "wide: a table")

            viewport.change(true)
            assertEquals(people.size, element().cardCount(), "1st flip wide -> narrow: cards")
            assertNull(element().querySelector("table"), "1st flip: the table is gone")

            viewport.change(false)
            assertTrue(element().hasTable(), "2nd flip narrow -> wide: a table again")
            assertEquals(0, element().cardCount())

            viewport.change(true)
            assertEquals(people.size, element().cardCount(), "3rd flip wide -> narrow: cards again")

            viewport.change(false)
            assertTrue(element().hasTable(), "4th flip narrow -> wide: a table again")
        }
    }

    @Test
    fun modeSwitch_inARealRoot_stillFlipsAfterAnUnrelatedPatchOfTheRoot() {
        withMountedRoot { root, element ->
            val viewport = FakeNarrowViewport(narrow = false)
            root.build(viewport)
            // Anything else on the page re-rendering (a sibling section finishing its load, a status
            // text changing) patches the WHOLE root -- that must not disturb the table.
            root.span("an unrelated sibling")
            assertTrue(element().hasTable())

            viewport.change(true)
            assertEquals(people.size, element().cardCount(), "the flip after a foreign patch still renders cards")
        }
    }

    @Test
    fun hostElement_survivesAnUnrelatedPatchOfTheRoot() {
        // The root cause of the bug above: KVision's `addAfterInsertHook`/`addAfterDestroyHook` assign the
        // widget a distinct snabbdom key (`useSnabbdomDistinctKey`). Registered AFTER the panel was already
        // added to a mounted container, the key changes between two renders -- snabbdom then throws the
        // element away, builds a new one and runs the destroy hook on a widget that is still very much alive.
        withMountedRoot { root, element ->
            val panel = root.build(FakeNarrowViewport(narrow = false))
            val hostElement = assertNotNull(panel.getElement(), "the panel is mounted")
            root.span("an unrelated sibling")
            assertSame(hostElement, panel.getElement(), "the same DOM element, not a replacement")
            assertTrue(element().hasTable())
        }
    }

    @Test
    fun listener_isReleasedOnlyWhenTheHostLeavesTheDocument() {
        withMountedRoot { root, _ ->
            val viewport = FakeNarrowViewport(narrow = false)
            val panel = root.build(viewport)
            root.span("an unrelated sibling")
            assertEquals(1, viewport.listeners.size, "still listening while mounted")

            root.remove(panel)
            assertEquals(0, viewport.listeners.size, "released once it really left the document")
        }
    }

    @Test
    fun host_isMountedAndConnectedAfterTheFirstRender() {
        withMountedRoot { root, element ->
            val panel = root.build(FakeNarrowViewport(narrow = false))
            val hostElement = assertNotNull(panel.getElement(), "the panel has an element")
            assertTrue(hostElement.isConnected, "and it is in the document")
            assertSame(element().children[0], hostElement, "it is the root's first child")
            // The insert hook really ran -- registered after `add(host)` it never did, which silently
            // disabled the "left the document without a destroy hook" guard of the listener.
            assertTrue(panel.mounted, "the insert hook has fired")
        }
    }

    @Test
    fun host_resubscribesWhenItIsReattachedAfterADestroy() {
        // `I18n.language`'s setter restarts the KVision root: every element is destroyed and built again
        // from the SAME widget objects. Without the re-subscribe in the insert hook, a language switch
        // silently ended the mode switch for the rest of the session.
        withMountedRoot { root, element ->
            val viewport = FakeNarrowViewport(narrow = false)
            val panel = root.build(viewport)
            root.restart()
            assertTrue(panel.mounted)
            assertEquals(1, viewport.listeners.size, "still listening after a root restart")

            viewport.change(true)
            assertEquals(people.size, element().cardCount(), "and the flip still works")
        }
    }
}
