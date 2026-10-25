package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * V1.9.208: in an inline field row (Kontenplan: search field, "Inaktive Konten anzeigen", "Aktualisieren") the field, the checkbox box and
 * the button share one centre line at the desktop AND the phone width. The phone width is measured in an iframe ([measureAtWidth]); the
 * checkbox bug was Bootstrap's `.form-check` margin/float inside a row that centres its items.
 */
class FilterRowGeometryDomTest {
    private companion object {
        const val TOLERANCE = 2.0
    }

    private fun HTMLElement.mid(): Double = getBoundingClientRect().let { (it.top + it.bottom) / 2 }

    private fun check(
        host: HTMLElement,
        width: Int,
    ) {
        val field = host.queryAll("input[type=text], input.form-control").first()
        val box = host.queryAll(".form-check").first()
        val input = host.queryAll(".form-check-input").first()
        val button = host.queryAll("button").first()
        // On a phone the row wraps (field, checkbox and button each on a line of their own), so one shared centre line is a desktop rule.
        if (width >= 1000) {
            val mids = listOf(field.mid(), box.mid(), button.mid())
            assertTrue(mids.max() - mids.min() <= TOLERANCE, "$width px: field/checkbox/button centres $mids")
        }
        assertTrue(field.getBoundingClientRect().height >= if (width <= 400) 44.0 else 30.0, "$width px: field height")
        assertTrue(
            abs(input.mid() - box.mid()) <= TOLERANCE,
            "$width px: the tick box is centred in its box (${input.mid()} vs ${box.mid()})",
        )
        if (width <= 400) {
            assertTrue(button.getBoundingClientRect().height >= 44.0, "$width px: touch target ${button.getBoundingClientRect().height}")
        }
    }

    @Test
    fun inlineFieldRow_fieldCheckboxAndButtonShareACentreLine_atDesktopAndPhoneWidth() {
        withMountedRoot("filter-row-geometry-inline") { root, element ->
            val row = root.inlineFieldRow {}
            row.text(label = "Konto suchen")
            row.checkBox(value = false, label = "Inaktive Konten anzeigen")
            row.actionButton(ActionIcon.REFRESH, "Aktualisieren")
            measureAtWidth(element(), 1200) { _, host -> check(host, 1200) }
            measureAtWidth(element(), 360) { _, host -> check(host, 360) }
        }
    }

    @Test
    fun filterRow_checkboxAndButtonShareACentreLine_atPhoneWidth() {
        withMountedRoot("filter-row-geometry-filter") { root, element ->
            val row = root.filterRow()
            row.text(label = "Konto suchen")
            row.checkBox(value = false, label = "Inaktive Konten anzeigen")
            row.actionButton(ActionIcon.REFRESH, "Aktualisieren")
            measureAtWidth(element(), 360) { _, host ->
                val box = host.queryAll(".form-check").first()
                val button = host.queryAll("button").first()
                assertTrue(box.getBoundingClientRect().height >= 44.0, "checkbox box ${box.getBoundingClientRect().height}")
                assertTrue(button.getBoundingClientRect().height >= 44.0, "button ${button.getBoundingClientRect().height}")
            }
        }
    }
}
