package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.191 -- `segmentedRadioGroup` (WAI-ARIA radio pattern) in a real, mounted root: roles and roving tabindex, arrow/Home/End keys that
 * skip disabled segments and wrap, the choice that follows the focus, and the hint of a disabled segment.
 */
class SegmentedRadioGroupDomTest {
    private val options =
        listOf(
            SegmentOption("a", "Alpha"),
            SegmentOption("b", "Beta", enabled = false, disabledHint = "Beta ist gesperrt"),
            SegmentOption("c", "Gamma"),
            SegmentOption("d", "Delta"),
        )

    private fun HTMLElement.radios() = allOf("[role=radio]")

    private fun HTMLElement.checked() = radios().single { it.getAttribute("aria-checked") == "true" }

    private fun HTMLElement.press(
        key: String,
        on: HTMLElement,
    ): KeyboardEvent {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true))
        on.dispatchEvent(event)
        return event
    }

    private suspend fun withGroup(
        selected: String = "a",
        onSelect: (String) -> Unit = {},
        block: suspend (HTMLElement) -> Unit,
    ) {
        withMountedRoot("segmented-radio-group") { root, element ->
            root.segmentedRadioGroup(options = options, selected = selected, ariaLabel = tr("Buchstabe"), onSelect = onSelect)
            delay(30)
            block(element())
        }
    }

    @Test
    fun rolesAndRovingTabindex(): Promise<Unit> =
        formTest {
            withGroup(selected = "c") { screen ->
                val group = assertNotNull(screen.querySelector("[role=radiogroup]") as? HTMLElement)
                assertEquals("Buchstabe", group.getAttribute("aria-label"), "the marker of tr() never reaches the DOM")
                assertEquals(listOf("false", "false", "true", "false"), screen.radios().map { it.getAttribute("aria-checked") })
                assertEquals(listOf("-1", "-1", "0", "-1"), screen.radios().map { it.getAttribute("tabindex") })
                assertTrue(screen.radios().all { it.getAttribute("type") == "button" })
            }
        }

    @Test
    fun aDisabledSegmentKeepsItsHint_isAriaDisabled_andIgnoresClicks(): Promise<Unit> =
        formTest {
            val chosen = mutableListOf<String>()
            withGroup(onSelect = { chosen += it }) { screen ->
                val beta = screen.radios()[1]
                assertEquals("true", beta.getAttribute("aria-disabled"))
                assertFalse(beta.hasAttribute("disabled"), "no native disabled: the tooltip must survive")
                assertEquals("Beta ist gesperrt", beta.getAttribute("title"))
                assertTrue(beta.getAttribute("aria-label").orEmpty().contains("Beta ist gesperrt"))
                beta.click()
                delay(30)
                assertEquals(emptyList(), chosen)
                assertEquals("Alpha", screen.checked().textContent?.trim())
            }
        }

    @Test
    fun arrowKeys_moveToTheNextEnabledSegment_wrap_andTheChoiceFollowsTheFocus(): Promise<Unit> =
        formTest {
            val chosen = mutableListOf<String>()
            withGroup(onSelect = { chosen += it }) { screen ->
                screen.checked().focus()
                val first = screen.press("ArrowRight", screen.checked())
                assertTrue(first.defaultPrevented, "the page must not scroll")
                delay(30)
                assertEquals("Gamma", screen.checked().textContent?.trim(), "Beta is disabled and skipped")
                assertEquals(screen.checked(), document.activeElement, "the focus moved with the choice")
                screen.press("ArrowDown", screen.checked())
                delay(30)
                assertEquals("Delta", screen.checked().textContent?.trim())
                screen.press("ArrowRight", screen.checked())
                delay(30)
                assertEquals("Alpha", screen.checked().textContent?.trim(), "wraps around")
                screen.press("ArrowLeft", screen.checked())
                delay(30)
                assertEquals("Delta", screen.checked().textContent?.trim(), "wraps backwards")
                screen.press("ArrowUp", screen.checked())
                delay(30)
                assertEquals("Gamma", screen.checked().textContent?.trim())
                assertEquals(listOf("c", "d", "a", "d", "c"), chosen)
                assertEquals(listOf("-1", "-1", "0", "-1"), screen.radios().map { it.getAttribute("tabindex") })
            }
        }

    @Test
    fun homeAndEnd_goToTheFirstAndLastEnabledSegment(): Promise<Unit> =
        formTest {
            withGroup(selected = "c") { screen ->
                screen.press("End", screen.checked())
                delay(30)
                assertEquals("Delta", screen.checked().textContent?.trim())
                screen.press("Home", screen.checked())
                delay(30)
                assertEquals("Alpha", screen.checked().textContent?.trim())
            }
        }

    @Test
    fun onSelect_isCalledOnlyWhenTheChoiceChanges_andSpaceAndEnterAreLeftToTheNativeClick(): Promise<Unit> =
        formTest {
            val chosen = mutableListOf<String>()
            withGroup(onSelect = { chosen += it }) { screen ->
                screen.radios()[0].click() // already chosen
                delay(30)
                assertEquals(emptyList(), chosen)
                screen.radios()[2].click()
                delay(30)
                assertEquals(listOf("c"), chosen, "exactly one call per real change")
                assertFalse(screen.press(" ", screen.checked()).defaultPrevented, "Space is the button's own click")
                assertFalse(screen.press("Enter", screen.checked()).defaultPrevented, "Enter is the button's own click")
                delay(30)
                assertEquals(listOf("c"), chosen, "keydown of Space/Enter alone selects nothing twice")
            }
        }

    @Test
    fun focusSelectedSegment_putsTheFocusOnTheChosenSegment(): Promise<Unit> =
        formTest {
            withMountedRoot("segmented-radio-group-focus") { root, element ->
                val group = root.segmentedRadioGroup(options = options, selected = "c", ariaLabel = tr("Buchstabe")) {}
                group.focusSelectedSegment()
                awaitUntil("the chosen segment has the focus") { document.activeElement == element().checked() }
                assertEquals("Gamma", element().checked().textContent?.trim())
            }
        }
}
