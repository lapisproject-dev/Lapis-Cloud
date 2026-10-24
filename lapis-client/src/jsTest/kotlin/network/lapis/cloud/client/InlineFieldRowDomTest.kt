package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.200: the real cascade (Bootstrap + KVision + `theme.css`) in Karma's Chrome. Geometry is measured on the control elements. The phone
 * width (360 px) cannot be set from inside a Karma page, so the narrow-screen rule is pinned as CSS text, not as a measurement.
 */
class InlineFieldRowDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
        const val TOLERANCE = 1.0
    }

    private fun HTMLElement.q(selector: String) = querySelector(selector) as HTMLElement

    private fun HTMLElement.qa(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.mid(): Double = getBoundingClientRect().let { (it.top + it.bottom) / 2 }

    private fun HTMLElement.h(): Double = getBoundingClientRect().height

    @Test
    fun pickerMode_dateAndTimeAreOneFieldsetWithOneLegend_sameHeightSameCentreLine() {
        assertTrue(stylesLoaded)
        withMountedRoot("inline-datetime-picker") { root, element ->
            val form = root.lapisForm()
            form.localDateTimeFields(label = "Anmeldeschluss (optional)", value = LocalDateTime(2026, 10, 15, 18, 0))
            val group = element().q("fieldset.lapis-datetime-group")
            assertEquals(
                "Anmeldeschluss (optional)",
                group
                    .q("legend")
                    .textContent
                    .orEmpty()
                    .trim(),
            )
            val inputs = group.qa("input")
            assertEquals(listOf("date", "time"), inputs.map { (it as HTMLInputElement).type })
            assertEquals("Anmeldeschluss (optional), Datum", inputs[0].getAttribute("aria-label"))
            assertEquals("Anmeldeschluss (optional), Uhrzeit", inputs[1].getAttribute("aria-label"))
            assertTrue(abs(inputs[0].h() - inputs[1].h()) <= TOLERANCE, "heights ${inputs[0].h()} vs ${inputs[1].h()}")
            assertTrue(abs(inputs[0].mid() - inputs[1].mid()) <= TOLERANCE, "centres ${inputs[0].mid()} vs ${inputs[1].mid()}")
            val legend = group.q("legend")
            assertTrue(legend.getBoundingClientRect().height < 2 * 24.0, "the legend is one line: ${legend.getBoundingClientRect().height}")
            // the two inner labels exist for assistive technology but are not seen
            group.qa("label").forEach { assertTrue(it.className.contains("visually-hidden"), "label '${it.textContent}' must be hidden") }
        }
    }

    @Test
    fun pickerMode_timeIsRightOfDate_onADesktopWidth() {
        assertTrue(stylesLoaded)
        if (window.innerWidth <= 600) return
        withMountedRoot("inline-datetime-sidebyside") { root, element ->
            root.lapisForm().localDateTimeFields(label = "Beginn")
            val inputs = element().qa("fieldset.lapis-datetime-group input")
            assertTrue(inputs[1].getBoundingClientRect().left >= inputs[0].getBoundingClientRect().right - TOLERANCE)
        }
    }

    @Test
    fun theNarrowRule_stacksTheTwoFields_asCss() {
        assertTrue(stylesLoaded)
        // The rule itself is in the shipped stylesheet source: below 30rem the row becomes a column and both fields get the full width.
        val sheetText =
            (0 until js("document.styleSheets.length") as Int)
                .joinToString("\n") { i ->
                    runCatching {
                        val rules = js("document.styleSheets[i].cssRules")
                        (0 until rules.length as Int).joinToString("\n") { r -> rules[r].cssText as String }
                    }.getOrDefault("")
                }
        assertTrue(sheetText.contains("max-width: 30rem"), "the narrow media query for date and time must exist")
        assertTrue(sheetText.contains("flex-direction: column"))
    }

    @Test
    fun textMode_keepsTheGroup_andTheOldStackedLayout() {
        assertTrue(stylesLoaded)
        useTextTemporalInputs()
        withMountedRoot("inline-datetime-text") { root, element ->
            root.lapisForm().localDateTimeFields(label = "Termin", value = LocalDateTime(2026, 10, 15, 18, 0))
            val group = element().q("fieldset.lapis-datetime-group")
            assertEquals(0, group.qa(".lapis-datetime-row").size, "the text fallback has no side-by-side row")
            val inputs = group.qa("input")
            assertEquals(listOf("text", "text"), inputs.map { (it as HTMLInputElement).type })
            assertEquals("15.10.2026", (inputs[0] as HTMLInputElement).value)
            assertEquals("Termin, Datum", inputs[0].getAttribute("aria-label"))
            assertTrue(inputs[1].getBoundingClientRect().top >= inputs[0].getBoundingClientRect().bottom - TOLERANCE, "stacked")
        }
    }

    @Test
    fun requiredStar_standsOnTheLegendOnce_notOnTheHiddenLabels() {
        assertTrue(stylesLoaded)
        withMountedRoot("inline-datetime-required") { root, element ->
            val form = root.lapisForm()
            form.localDateTimeFields(label = "Termin", required = true)
            form.textField(label = "Ort", required = false)
            form.buttons(primary = Button("Weiter"))
            val group = element().q("fieldset.lapis-datetime-group")
            assertEquals(1, group.qa("legend .lapis-required-mark").size, "exactly one star, on the legend")
            assertEquals(0, group.qa("label .lapis-required-mark").size, "none on the hidden labels")
        }
    }

    @Test
    fun rawInputs_haveTheSameStructureAsTheFormFields() {
        assertTrue(stylesLoaded)
        withMountedRoot("inline-datetime-raw") { root, element ->
            root.localDateTimeInput(label = "Beginn")
            val group = element().q("fieldset.lapis-datetime-group")
            assertEquals(
                "Beginn",
                group
                    .q("legend")
                    .textContent
                    .orEmpty()
                    .trim(),
            )
            assertEquals(1, group.qa(".lapis-datetime-row").size)
            val inputs = group.qa("input")
            assertEquals(listOf("Beginn, Datum", "Beginn, Uhrzeit"), inputs.map { it.getAttribute("aria-label") })
            assertTrue(abs(inputs[0].h() - inputs[1].h()) <= TOLERANCE)
            assertTrue(abs(inputs[0].mid() - inputs[1].mid()) <= TOLERANCE)
        }
    }

    @Test
    fun inlineFieldGroup_fieldAndButtonShareHeightAndCentreLine_andLabelStandsBesideTheField() {
        assertTrue(stylesLoaded)
        withMountedRoot("inline-group") { root, element ->
            val row = root.inlineFieldRow {}
            lateinit var boost: Button
            row.inlineFieldGroup {
                text(label = "Boost-Betrag (LTR)")
                boost = button("Boosten", style = ButtonStyle.OUTLINEPRIMARY)
            }
            row.button("Thread öffnen", style = ButtonStyle.OUTLINESECONDARY)
            val input = element().q("input.form-control")
            val label = element().q("label")
            assertTrue(abs(input.h() - boost.getElement()!!.unsafeCast<HTMLElement>().h()) <= TOLERANCE, "field ${input.h()}")
            assertTrue(abs(input.mid() - boost.getElement()!!.unsafeCast<HTMLElement>().mid()) <= TOLERANCE)
            assertTrue(
                label.getBoundingClientRect().right <= input.getBoundingClientRect().left + TOLERANCE,
                "the label stands BESIDE the field",
            )
            assertTrue(abs(label.mid() - input.mid()) <= TOLERANCE + 2, "label and field on one centre line")
            element()
                .qa(
                    ".btn",
                ).forEach { assertTrue(abs(it.h() - input.h()) <= TOLERANCE, "button ${it.textContent} ${it.h()} vs ${input.h()}") }
        }
    }

    @Test
    fun filterRow_hintStandsOnce_andTheButtonSharesTheFieldBottom() {
        assertTrue(stylesLoaded)
        withMountedRoot("filter-row") { root, element ->
            val first = root.filterRow(hint = "Alle Filter sind optional.")
            first.text(label = "Entitäts-ID")
            val second = root.filterRow(hint = null)
            second.localDateTimeInput(label = "Von", hintLine = false)
            second.actionButton(ActionIcon.FILTER, "Filtern")
            assertEquals(1, element().qa(".text-muted").count { it.textContent.orEmpty().contains("Alle Filter sind optional.") })
            val button = element().q(".btn")
            val timeInput = element().qa("fieldset.lapis-datetime-group input").last()
            assertTrue(
                abs(button.getBoundingClientRect().bottom - timeInput.getBoundingClientRect().bottom) <= TOLERANCE,
                "button bottom ${button.getBoundingClientRect().bottom} vs field ${timeInput.getBoundingClientRect().bottom}",
            )
            assertEquals(0, element().qa("label").count { it.textContent.orEmpty().contains("(optional)") })
        }
    }

    @Test
    fun stakeHint_offersAFigure_thatTheButtonWritesOnRequest_andNeverPrefills() {
        assertTrue(stylesLoaded)
        withMountedRoot("stake-hint") { root, element ->
            var written: String? = null
            val hint = StakeSuggestionHint(root) { written = it }
            hint.updateWeights(listOf("3.5".toDouble().toDecimal(), "12.00".toDouble().toDecimal()))
            assertEquals("", element().textContent.orEmpty().trim(), "nothing before the balance is known")
            hint.updateBalance("50".toDouble().toDecimal())
            assertTrue(element().textContent.orEmpty().contains(stakeFigure("12.01")), element().textContent.orEmpty())
            assertEquals(null, written, "never written without a click")
            element().q("button").click()
            assertEquals(stakeFigure("12.01"), written)
            hint.updateBalance("5".toDouble().toDecimal())
            assertEquals(0, element().qa("button").size, "no button when the balance falls short")
            assertTrue(element().textContent.orEmpty().contains(stakeFigure("12.01")))
            assertTrue(element().textContent.orEmpty().contains(stakeFigure("5.00")))
            hint.updateBalance("0".toDouble().toDecimal())
            assertEquals("", element().textContent.orEmpty().trim(), "zero balance: the zero-balance notice speaks, not this line")
        }
    }
}
