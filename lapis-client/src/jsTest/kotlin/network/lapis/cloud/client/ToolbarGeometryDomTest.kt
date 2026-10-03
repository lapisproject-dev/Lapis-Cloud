package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.43 (R56): a button next to a LABELLED field shares the field's bottom edge. Measured on the real cascade (Bootstrap + KVision
 * stylesheet + `theme.css` in Karma), on the control ELEMENT (`select.form-select`, `input.form-control`, `.btn`) -- never on a container
 * box, which would confirm the bug (the container is taller than the control by label + margin).
 *
 * Measured BEFORE the change (legacy `hPanel { align-items-center }` row, as in MotionsScreen): select bottom 70, button bottom 62,
 * label 24 px, `kv-mb-3` margin 16 px -> 8 px offset.
 */
class ToolbarGeometryDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }

        /** 1 px for sub-pixel rounding; 0 would be flaky in headless Chrome. */
        const val TOLERANCE = 1.0
    }

    private fun HTMLElement.q(selector: String) = querySelector(selector) as HTMLElement

    private fun HTMLElement.qa(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    @Test
    fun legacyRow_isOffset_whichDocumentsTheCause() {
        assertTrue(stylesLoaded)
        withMountedRoot("toolbar-geometry-legacy") { root, element ->
            val row = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            row.select(options = listOf("" to "Alle"), value = "", label = "Gremium")
            row.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
            val select = element().q("select.form-select")
            val offset = select.getBoundingClientRect().bottom - element().q(".btn").getBoundingClientRect().bottom
            val wrapper = window.getComputedStyle(select.parentElement as HTMLElement)
            assertEquals("16px", wrapper.marginBottom, "KVision's kv-mb-3 margin is the hidden cause")
            assertTrue(offset > 4, "legacy row offset was $offset px")
        }
    }

    @Test
    fun toolbar_selectsTextfieldAndButton_shareTheBottomEdge() {
        assertTrue(stylesLoaded)
        withMountedRoot("toolbar-geometry-new") { root, element ->
            val bar = root.lapisToolbar()
            bar.select(options = listOf("" to "Alle"), value = "", label = "Gremium")
            bar.text(label = "Entitäts-ID (optional)")
            bar.actionButton(ActionIcon.REFRESH, "Aktualisieren")
            bar.actionButton(ActionIcon.FILTER, "Filtern")
            val button = element().qa(".btn")
            val bottoms =
                listOf(element().q("select.form-select"), element().q("input.form-control")) + button
            val reference = bottoms.first().getBoundingClientRect().bottom
            bottoms.forEach {
                assertTrue(
                    abs(it.getBoundingClientRect().bottom - reference) <= TOLERANCE,
                    "${it.tagName}.${it.className} bottom ${it.getBoundingClientRect().bottom} vs $reference",
                )
            }
        }
    }

    @Test
    fun toolbar_textNextToButton_isCentredOnTheButton() {
        assertTrue(stylesLoaded)
        withMountedRoot("toolbar-geometry-text") { root, element ->
            val bar = root.lapisToolbar()
            bar.toolbarText("3 Treffer")
            bar.actionButton(ActionIcon.REFRESH, "Aktualisieren")
            val text = element().q(".lapis-toolbar-text").getBoundingClientRect()
            val button = element().q(".btn").getBoundingClientRect()
            assertTrue(abs((text.top + text.bottom) / 2 - (button.top + button.bottom) / 2) <= TOLERANCE, "text $text vs button $button")
        }
    }

    @Test
    fun toolbar_checkboxNextToButton_isCentredOnTheButton() {
        assertTrue(stylesLoaded)
        withMountedRoot("toolbar-geometry-checkbox") { root, element ->
            val bar = root.lapisToolbar()
            bar.checkBox(value = false, label = "Auch inaktive")
            bar.actionButton(ActionIcon.REFRESH, "Aktualisieren")
            val box = element().q("input[type=checkbox]").getBoundingClientRect()
            val button = element().q(".btn").getBoundingClientRect()
            val offset = abs((box.top + box.bottom) / 2 - (button.top + button.bottom) / 2)
            assertTrue(offset <= 2.0, "checkbox centre is $offset px off the button centre")
        }
    }

    @Test
    fun toolbar_wrappedRow_keepsBottomAlignmentPerRowAndButtonWidth() {
        assertTrue(stylesLoaded)
        withMountedRoot("toolbar-geometry-narrow") { root, element ->
            // A 340 px host (phone content width) cannot hold three fields and a button on one line: the toolbar wraps.
            val host = root.vPanel { width = 340.px }
            val bar = host.lapisToolbar()
            bar.text(label = "Von")
            bar.text(label = "Bis")
            bar.text(label = "Entität")
            bar.actionButton(ActionIcon.FILTER, "Filtern")
            val inputs = element().qa("input.form-control").map { it.getBoundingClientRect() }
            val button = element().q(".btn").getBoundingClientRect()
            assertTrue(inputs.any { it.top > inputs.first().top + TOLERANCE }, "the toolbar did not wrap in 340 px")
            val sameRow = inputs.filter { button.top < it.bottom && button.bottom > it.top }
            assertTrue(sameRow.isNotEmpty(), "the button shares a row with at least one field")
            sameRow.forEach {
                assertTrue(
                    abs(it.bottom - button.bottom) <= TOLERANCE,
                    "row-mate bottom ${it.bottom} vs button ${button.bottom}",
                )
            }
            assertTrue(button.width < 200, "buttons keep their own width, ${button.width}")
        }
    }
}
