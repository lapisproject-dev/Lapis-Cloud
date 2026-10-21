package network.lapis.cloud.client

import io.kvision.html.Button
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.173: the [LapisForm] date and time fields in picker mode (the default). ChromeHeadless supports `type=date`/`type=time`. A half-typed
 * native entry (`validity.badInput`) cannot be typed in a test; it is SIMULATED by overriding the element's `validity` property, so the real
 * keyboard path is not covered here.
 */
class LocalPickerFieldsDomTest {
    @AfterTest
    fun restore() {
        forceTextTemporalInputsForTest = false
    }

    private fun HTMLElement.inputOf(index: Int): HTMLInputElement = querySelectorAll("input").item(index) as HTMLInputElement

    private fun simulateBadInput(input: HTMLInputElement) {
        js("Object.defineProperty(input, 'validity', { value: { badInput: true, stepMismatch: false }, configurable: true })")
    }

    @Test
    fun dateField_isNative_withoutPlaceholderOrFormatHint_andExtraHintAlone() {
        withMountedRoot("pf-date") { root, element ->
            val form = root.lapisForm()
            val field = form.localDateField(label = "Beginn", value = LocalDate(2026, 3, 5), extraHint = "Nicht in der Zukunft.")
            form.buttons(primary = Button("Weiter"))
            val input = element().inputOf(0)
            assertEquals("date", input.type)
            assertEquals("2026-03-05", input.value)
            assertEquals("", input.placeholder)
            assertTrue(input.classList.contains("lapis-date-input"))
            val describedBy = input.getAttribute("aria-describedby").orEmpty().split(' ')
            val hint = describedBy.mapNotNull { element().querySelector("#$it") }.joinToString(" ") { it.textContent.orEmpty() }
            assertTrue(hint.contains("Nicht in der Zukunft."))
            assertFalse(hint.contains("Format:"), "no format hint in picker mode: $hint")
            assertEquals(LocalDate(2026, 3, 5), field.localDateOrNull())
            val label = element().querySelector("label[for='${input.id}']")
            assertNotNull(label, "label[for] points at the input")
        }
    }

    @Test
    fun setDate_writesIso_leavesDirtyAlone_andReadsBack() {
        withMountedRoot("pf-setdate") { root, element ->
            val form = root.lapisForm()
            val field = form.localDateField(label = "Tag")
            form.buttons(primary = Button("Weiter"))
            field.setDate(LocalDate(2027, 12, 31))
            assertEquals("2027-12-31", element().inputOf(0).value)
            assertEquals("2027-12-31", field.value)
            assertFalse(field.dirty)
            assertEquals(LocalDate(2027, 12, 31), field.localDateOrNull())
            field.setDate(null)
            assertNull(field.localDateOrNull())
        }
    }

    @Test
    fun badInput_isTheGermanFormatError_evenForARequiredField_andEmptyOptionalStaysValid() {
        withMountedRoot("pf-bad") { root, element ->
            val form = root.lapisForm()
            val required = form.localDateField(label = "Pflicht", required = true)
            val optional = form.localDateField(label = "Optional")
            form.buttons(primary = Button("Weiter"))
            assertTrue(optional.isValid())
            assertFalse(required.isValid())
            required.validate(force = true)
            assertTrue(element().textContent.orEmpty().contains("muss ausgefüllt werden"), "empty + required: the required message")
            simulateBadInput(element().inputOf(0))
            simulateBadInput(element().inputOf(1))
            assertFalse(required.isValid())
            assertFalse(optional.isValid(), "a half-typed optional date is an error, never a silent blank")
            required.validate(force = true)
            assertTrue(element().textContent.orEmpty().contains("Bitte als TT.MM.JJJJ eingeben."))
        }
    }

    @Test
    fun timeField_stepMismatchIsAnErrorOnlyOnAFiveMinuteGrid() {
        withMountedRoot("pf-step") { root, element ->
            val form = root.lapisForm()
            val grid = form.localTimeField(label = "Raster", value = null, stepMinutes = 5)
            val free = form.localTimeField(label = "Frei", value = null)
            form.buttons(primary = Button("Weiter"))
            assertEquals("300", element().inputOf(0).getAttribute("step"))
            assertEquals("60", element().inputOf(1).getAttribute("step"))
            fillTime(element().inputOf(0), LocalTime(9, 3))
            fillTime(element().inputOf(1), LocalTime(9, 3))
            assertFalse(grid.isValid(), "09:03 is off the 5-minute grid")
            grid.validate(force = true)
            assertTrue(element().textContent.orEmpty().contains("5-Minuten-Schritten"))
            assertTrue(free.isValid())
            assertEquals(LocalTime(9, 3), free.localTimeOrNull())
            fillTime(element().inputOf(0), LocalTime(9, 5))
            assertTrue(grid.isValid())
        }
    }

    @Test
    fun dateTimeFields_sitInOneRow_roundTripIso_andCombine() {
        withMountedRoot("pf-pair") { root, element ->
            val form = root.lapisForm()
            val pair = form.localDateTimeFields(label = "Termin", value = LocalDateTime(2026, 10, 15, 18, 0), stepMinutes = 5)
            form.buttons(primary = Button("Weiter"))
            val rows = element().querySelectorAll(".lapis-datetime-row")
            assertEquals(1, rows.length)
            assertEquals(2, (rows.item(0) as HTMLElement).children.length)
            assertEquals("2026-10-15", element().inputOf(0).value)
            assertEquals("18:00", element().inputOf(1).value)
            assertEquals(LocalDateTime(2026, 10, 15, 18, 0), pair.dateTimeOrNull())
            pair.date.setDate(LocalDate(2026, 11, 1))
            assertEquals(LocalDateTime(2026, 11, 1, 18, 0), pair.dateTimeOrNull())
        }
    }

    @Test
    fun linkedRange_setsMinAndMax_andTheCrossFieldRuleStaysAuthoritative() {
        withMountedRoot("pf-range") { root, element ->
            val form = root.lapisForm()
            val from = form.localDateField(label = "Von", value = LocalDate(2026, 3, 10))
            val to = form.localDateField(label = "Bis", value = LocalDate(2026, 3, 12))
            linkDateRange(from, to)
            form.crossFieldRule(field = to) {
                val a = from.localDateOrNull()
                val b = to.localDateOrNull()
                if (a != null && b != null && b < a) FieldCheck.Invalid("Bis vor Von") else FieldCheck.Ok
            }
            form.buttons(primary = Button("Weiter"))
            assertEquals("2026-03-10", element().inputOf(1).getAttribute("min"))
            assertEquals("2026-03-12", element().inputOf(0).getAttribute("max"))
            // a typed date can bypass the popup hint: the rule still refuses it
            fillDate(element().inputOf(1), LocalDate(2026, 3, 1))
            assertFalse(form.validateAll(force = true))
        }
    }

    @Test
    fun textFallback_isIdenticalToTheOldBehaviour() {
        useTextTemporalInputs()
        withMountedRoot("pf-fallback") { root, element ->
            val form = root.lapisForm()
            val field = form.localDateField(label = "Tag", value = LocalDate(2026, 3, 5), extraHint = "Zusatz.")
            form.buttons(primary = Button("Weiter"))
            val input = element().inputOf(0)
            assertEquals("text", input.type)
            assertEquals("05.03.2026", input.value)
            assertEquals("TT.MM.JJJJ", input.placeholder)
            assertEquals(LocalDate(2026, 3, 5), field.localDateOrNull())
            field.setDate(LocalDate(2026, 4, 6))
            assertEquals("06.04.2026", input.value)
        }
    }
}

/** V1.9.173: the readers that were moved from locale text to the typed accessors, in picker mode. */
class LocalPickerReadersDomTest {
    @AfterTest
    fun restore() {
        forceTextTemporalInputsForTest = false
    }

    private fun HTMLElement.inputOf(index: Int): HTMLInputElement = querySelectorAll("input").item(index) as HTMLInputElement

    @Test
    fun dateRangeFilter_readsIso_hasRangeCountsAHalfTypedEntry_andSetFromWritesIso() {
        withMountedRoot("pr-range") { root, element ->
            val filter = root.dateRangeFilter(fromLabel = "Von", toLabel = "Bis")
            assertFalse(filter.hasRange())
            assertNull(filter.parseFrom())
            filter.setFrom(LocalDate(2026, 1, 1))
            filter.setTo(LocalDate(2026, 3, 5))
            assertEquals("2026-01-01", element().inputOf(0).value)
            assertEquals(LocalDate(2026, 1, 1), filter.parseFrom())
            assertEquals(LocalDate(2026, 3, 5), filter.parseTo())
            assertTrue(filter.hasRange())
            assertEquals("2026-03-05", element().inputOf(0).getAttribute("max"))
            filter.setFrom(null)
            filter.setTo(null)
            assertFalse(filter.hasRange())
        }
    }

    @Test
    fun dateRangeFilter_halfTypedEntryIsIntentButNoDate() {
        withMountedRoot("pr-range-bad") { root, element ->
            val filter = root.dateRangeFilter(fromLabel = "Von", toLabel = "Bis")
            val input = element().inputOf(0)
            js("Object.defineProperty(input, 'validity', { value: { badInput: true }, configurable: true })")
            assertTrue(filter.hasRange(), "a half-typed date still counts as an intended range")
            assertNull(filter.parseFrom())
        }
    }

    @Test
    fun rawPair_dateTimeValueIsIso_andAHalfTypedPartIsNeverIso() {
        withMountedRoot("pr-pair") { root, element ->
            val pair = root.localDateTimeInput(label = "Zeitpunkt", stepMinutes = 5)
            fillDate(element().inputOf(0), LocalDate(2099, 11, 1))
            assertEquals("2099-11-01", pair.value, "a date without a time is not an ISO date-time")
            fillTime(element().inputOf(1), LocalTime(18, 0))
            assertEquals("2099-11-01T18:00", pair.value)
        }
    }
}
