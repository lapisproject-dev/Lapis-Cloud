package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [listFilterField]: the name filter above an already loaded list, in a real mounted root. */
class ListFilterDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private val people = listOf("Anna Berg", "Hans Müller", "Johanna Klein", "Karl Roth")

    private fun HTMLElement.field(): HTMLInputElement = assertNotNull(querySelector("input") as? HTMLInputElement, "no filter input")

    private fun HTMLElement.rows(): List<String> = allOf("[data-row]").map { it.textContent.orEmpty().trim() }

    /** A list that re-renders from the filter, the way the screens do. */
    private fun io.kvision.panel.Root.filteredList(loaded: () -> List<String>): ListFilter = filteredListWithRender(loaded).first

    private fun io.kvision.panel.Root.filteredListWithRender(loaded: () -> List<String>): Pair<ListFilter, () -> Unit> {
        val filter = listFilterField()
        val rows = div()

        fun render() {
            rows.removeAll()
            val visible = filter.apply(loaded(), nameOf = { it })
            visible.forEach { name -> rows.div(name) { setAttribute("data-row", "") } }
        }
        filter.subscribe { render() }
        render()
        return filter to ::render
    }

    private suspend fun typeAndSettle(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
        delay(ListFilter.DEBOUNCE_MS + 120L)
    }

    @Test
    fun typing_narrowsTheList_afterTheDebounce_andShowsACount() =
        test {
            withMountedRoot("list-filter-typing") { root, element ->
                root.filteredList { people }
                assertEquals(people, element().rows())
                typeAndSettle(element().field(), "anna")
                assertEquals(listOf("Anna Berg", "Johanna Klein"), element().rows())
                assertEquals("2 von 4", element().querySelector("[role=status]")?.textContent?.trim())
            }
        }

    @Test
    fun diacriticsAndWordOrder_areHandled_andTheOriginalOrderIsKept() =
        test {
            withMountedRoot("list-filter-order") { root, element ->
                root.filteredList { listOf("Johanna Klein", "Hans Müller", "Anna Berg") }
                typeAndSettle(element().field(), "mueller hans")
                assertEquals(listOf("Hans Müller"), element().rows())
                typeAndSettle(element().field(), "anna")
                // "Anna Berg" (word start) does NOT jump ahead of "Johanna Klein" here: the roster keeps the screen's order.
                assertEquals(listOf("Johanna Klein", "Anna Berg"), element().rows())
                typeAndSettle(element().field(), "hanna")
                assertEquals(listOf("Johanna Klein"), element().rows())
            }
        }

    @Test
    fun clearButton_emptiesTheFilter_atOnce_andHidesItself() =
        test {
            withMountedRoot("list-filter-clear") { root, element ->
                root.filteredList { people }
                val clear = { element().allOf("button").first { it.getAttribute("aria-label") != null } }
                typeAndSettle(element().field(), "roth")
                assertEquals(listOf("Karl Roth"), element().rows())
                clear().click()
                assertEquals(people, element().rows())
                assertEquals("", element().field().value)
                assertEquals(
                    "",
                    element()
                        .querySelector("[role=status]")
                        ?.textContent
                        ?.trim()
                        .orEmpty(),
                )
            }
        }

    @Test
    fun theFilterText_survivesAReloadOfTheList() =
        test {
            withMountedRoot("list-filter-reload") { root, element ->
                var loaded = people
                val (filter, render) = root.filteredListWithRender { loaded }
                typeAndSettle(element().field(), "berg")
                assertEquals("berg", filter.term)
                assertEquals(listOf("Anna Berg"), element().rows())
                loaded = people + "Berta Berg" // the list is reloaded: the screen renders again, the filter object keeps its text
                render()
                assertEquals("berg", filter.term)
                assertEquals(listOf("Anna Berg", "Berta Berg"), element().rows())
                assertEquals("berg", element().field().value)
            }
        }

    @Test
    fun noMatch_givesAnEmptyList_andTheCountSaysZero() =
        test {
            withMountedRoot("list-filter-nomatch") { root, element ->
                root.filteredList { people }
                typeAndSettle(element().field(), "zzz")
                assertTrue(element().rows().isEmpty())
                assertEquals("0 von 4", element().querySelector("[role=status]")?.textContent?.trim())
            }
        }

    @Test
    fun hidden_filter_keepsItsText_andCanBeShownAgain() =
        test {
            withMountedRoot("list-filter-visible") { root, element ->
                val filter = root.filteredList { people }
                typeAndSettle(element().field(), "karl")
                filter.setVisible(false)
                assertFalse(element().querySelector(".lapis-list-filter")?.let { (it as HTMLElement).offsetHeight > 0 } ?: false)
                filter.setVisible(true)
                assertEquals("karl", filter.term)
            }
        }
}
