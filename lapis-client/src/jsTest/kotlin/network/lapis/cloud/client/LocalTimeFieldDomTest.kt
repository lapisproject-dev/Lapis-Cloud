package network.lapis.cloud.client

import io.kvision.html.Button
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.137: the time field, the date-plus-time pair of a [LapisForm] and the raw `Text` variants of `LocalInputs.kt` in a REAL mounted root --
 * the pattern as placeholder and hint, `aria-describedby`, the normalisation when the field is left, and the parsed ISO value.
 */
class LocalTimeFieldDomTest {
    private val savedBrowserLanguage = browserLanguageProvider

    @AfterTest
    fun restore() {
        browserLanguageProvider = savedBrowserLanguage
    }

    private fun HTMLElement.inputOf(index: Int): HTMLInputElement = querySelectorAll("input").item(index) as HTMLInputElement

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    private fun leave(input: HTMLInputElement) {
        input.dispatchEvent(Event("blur"))
    }

    @Test
    fun timeField_isAPlainTextFieldWithPatternPlaceholderHintAndDescribedBy() {
        useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
        withMountedRoot("local-time-field") { root, element ->
            val form = root.lapisForm()
            val field = form.localTimeField(label = "Abfahrt", value = LocalTime(8, 5))
            form.buttons(primary = Button("Weiter"))
            val input = element().inputOf(0)
            assertEquals("text", input.type, "no browser time picker")
            assertEquals("08:05", input.value)
            assertEquals("hh:mm", input.placeholder)
            assertEquals("numeric", input.getAttribute("inputmode"))
            val describedBy = input.getAttribute("aria-describedby").orEmpty()
            assertTrue(describedBy.isNotBlank())
            val hint = describedBy.split(' ').mapNotNull { element().querySelector("#$it") }.joinToString(" ") { it.textContent.orEmpty() }
            assertTrue(hint.contains("Format: hh:mm"), "the hint names the pattern: $hint")
            assertEquals(LocalTime(8, 5), field.localTimeOrNull())
        }
    }

    @Test
    fun timeField_normalisesAValidEntryWhenLeft_andLeavesAnInvalidOneAsTyped() {
        useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
        withMountedRoot("local-time-field-blur") { root, element ->
            val form = root.lapisForm()
            val field = form.localTimeField(label = "Abfahrt", value = null)
            form.buttons(primary = Button("Weiter"))
            val input = element().inputOf(0)
            type(input, "9.05")
            leave(input)
            assertEquals("09:05", input.value)
            assertEquals(LocalTime(9, 5), field.localTimeOrNull())
            type(input, "25:00")
            leave(input)
            assertEquals("25:00", input.value, "an invalid entry stays exactly as typed")
            assertNull(field.localTimeOrNull())
            assertTrue(element().shownErrors().contains("Diese Uhrzeit gibt es nicht."), "${element().shownErrors()}")
        }
    }

    @Test
    fun timeField_inAnEnUsBrowser_readsAmPm_andShowsTheFixedTwelveHourHint() {
        useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
        browserLanguageProvider = { "en-US" }
        val previous = io.kvision.i18n.I18n.language
        try {
            io.kvision.i18n.I18n.language = "en"
            withMountedRoot("local-time-field-us") { root, element ->
                val form = root.lapisForm()
                val field = form.localTimeField(label = "Departure", value = LocalTime(15, 5))
                form.buttons(primary = Button("Next"))
                val input = element().inputOf(0)
                assertEquals("3:05${NBSP}PM", input.value)
                assertEquals("h:mm AM/PM", input.placeholder)
                type(input, "3:05")
                assertNull(field.localTimeOrNull(), "a bare one-digit hour needs AM or PM")
                type(input, "11:30 pm")
                assertEquals(LocalTime(23, 30), field.localTimeOrNull())
            }
        } finally {
            io.kvision.i18n.I18n.language = previous
        }
    }

    @Test
    fun dateTimeFields_areTwoLabelledFields_andCombineToOneLocalDateTime() {
        useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
        withMountedRoot("local-datetime-fields") { root, element ->
            val form = root.lapisForm()
            val pair = form.localDateTimeFields(label = "Termin", value = LocalDateTime(2026, 10, 15, 18, 0), required = true)
            form.buttons(primary = Button("Weiter"))
            val labels = element().querySelectorAll("label")
            val texts =
                (0 until labels.length).map {
                    (labels.item(it) as HTMLElement)
                        .textContent
                        .orEmpty()
                        .replace(" *", "")
                        .trim()
                }
            assertTrue("Termin (Datum)" in texts && "Termin (Uhrzeit)" in texts, "$texts")
            assertEquals("15.10.2026", element().inputOf(0).value)
            assertEquals("18:00", element().inputOf(1).value)
            assertEquals(LocalDateTime(2026, 10, 15, 18, 0), pair.dateTimeOrNull())
            type(element().inputOf(1), "")
            assertNull(pair.dateTimeOrNull(), "a blank time makes the whole point in time unreadable")
        }
    }

    @Test
    fun rawDateTimeInput_valueIsIsoWhenValid_andNeverIsoWhenHalfTyped() {
        useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
        withMountedRoot("local-raw-datetime") { root, element ->
            val input = root.localDateTimeInput(label = "Beginn")
            assertEquals("", input.value)
            assertTrue(input.isBlank)
            type(element().inputOf(0), "01.11.2099")
            assertEquals("01.11.2099", input.value, "a date without a time is not an ISO date-time")
            assertNull(input.dateTimeOrNull())
            type(element().inputOf(1), "18:00")
            assertEquals("2099-11-01T18:00", input.value)
            assertEquals(LocalDateTime(2099, 11, 1, 18, 0), input.dateTimeOrNull())
            assertFalse(input.isBlank)
            val hints = element().querySelectorAll(".text-muted.small")
            assertEquals(2, hints.length, "one hint line per field")
            val described = element().inputOf(0).getAttribute("aria-describedby")
            assertNotNull(described)
            assertEquals("Format: TT.MM.JJJJ", assertNotNull(element().querySelector("#$described")).textContent)
        }
    }

    @Test
    fun rawDateInput_withoutAHintLine_hasOnlyAPlaceholder_andNormalisesOnBlur() {
        useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
        withMountedRoot("local-raw-date") { root, element ->
            val input = root.localDateInput(label = "Von", value = LocalDate(2026, 3, 5), hintLine = false)
            val control = element().inputOf(0)
            assertEquals("05.03.2026", control.value)
            assertEquals("TT.MM.JJJJ", control.placeholder)
            assertNull(control.getAttribute("aria-describedby"))
            assertNull(input.hint)
            type(control, "6.3.2026")
            leave(control)
            assertEquals("06.03.2026", control.value)
            assertEquals(LocalDate(2026, 3, 6), input.dateOrNull())
            type(control, "")
            assertNull(input.dateOrNull())
        }
    }
}
