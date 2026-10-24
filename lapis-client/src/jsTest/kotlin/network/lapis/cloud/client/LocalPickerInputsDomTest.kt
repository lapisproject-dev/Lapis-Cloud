package network.lapis.cloud.client

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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.171: the opt-in native pickers (`picker = true`) of `LocalInputs.kt`. ChromeHeadless supports `type=date`/`type=time`. A half-typed
 * native entry (`validity.badInput`) cannot be typed in a test; it is SIMULATED by overriding the element's `validity` property, so the
 * real keyboard path is not covered here.
 */
class LocalPickerInputsDomTest {
    @AfterTest
    fun restore() {
        forceTextTemporalInputsForTest = false
    }

    private fun HTMLElement.inputOf(index: Int): HTMLInputElement = querySelectorAll("input").item(index) as HTMLInputElement

    private fun simulateBadInput(input: HTMLInputElement) {
        js("Object.defineProperty(input, 'validity', { value: { badInput: true }, configurable: true })")
    }

    @Test
    fun picker_usesNativeTypes_stepOnlyOnTime_noHintNoPlaceholder() {
        withMountedRoot("picker-native") { root, element ->
            val pair = root.localDateTimeInput(label = "Beginn", stepMinutes = 5)
            val date = element().inputOf(0)
            val time = element().inputOf(1)
            assertEquals("date", date.type)
            assertEquals("time", time.type)
            assertEquals("300", time.getAttribute("step"))
            assertTrue(date.classList.contains("lapis-date-input"))
            assertTrue(time.classList.contains("lapis-time-input"))
            assertNull(date.getAttribute("step"))
            assertNull(date.getAttribute("aria-describedby"))
            assertNull(time.getAttribute("aria-describedby"))
            assertEquals("", date.placeholder)
            assertEquals(0, element().querySelectorAll(".text-muted.small").length)
            assertNull(pair.date.hint)
            val row = element().querySelectorAll(".lapis-datetime-row")
            assertEquals(1, row.length)
            assertEquals(2, (row.item(0) as HTMLElement).children.length)
        }
    }

    @Test
    fun picker_isoRoundtrip_andPrefill() {
        withMountedRoot("picker-roundtrip") { root, element ->
            val pair = root.localDateTimeInput(label = "Beginn", value = LocalDateTime(2026, 3, 5, 9, 5), picker = true)
            assertEquals("2026-03-05", element().inputOf(0).value)
            assertEquals("09:05", element().inputOf(1).value)
            assertEquals(LocalDateTime(2026, 3, 5, 9, 5), pair.dateTimeOrNull())
            pair.setDateTime(LocalDateTime(2027, 12, 31, 23, 0))
            assertEquals("2027-12-31", element().inputOf(0).value)
            assertEquals("23:00", element().inputOf(1).value)
            assertEquals("2027-12-31T23:00", pair.value)
        }
    }

    @Test
    fun picker_blankIsBlank_andOffStepTimeIsKept() {
        withMountedRoot("picker-blank") { root, element ->
            val pair = root.localDateTimeInput(label = "Schluss", picker = true)
            assertTrue(pair.isBlank)
            assertEquals("", pair.value)
            assertNull(pair.dateTimeOrNull())
            val time = element().inputOf(1)
            time.value = "18:07"
            time.dispatchEvent(Event("input"))
            assertEquals("18:07", time.value)
            assertEquals(LocalTime(18, 7), pair.time.timeOrNull())
        }
    }

    @Test
    fun picker_badInput_isNotBlank_andNeverParses() {
        withMountedRoot("picker-bad") { root, element ->
            val input = root.localDateInput(label = "Von", picker = true)
            simulateBadInput(element().inputOf(0))
            assertFalse(input.text.isBlank())
            assertIs<DateParse.BadFormat>(input.parse())
            assertNull(input.dateOrNull())
            val pair = root.localDateTimeInput(label = "Ende", picker = true)
            simulateBadInput(element().inputOf(1))
            assertFalse(pair.isBlank)
            assertNull(pair.dateTimeOrNull())
        }
    }

    @Test
    fun picker_labelIsLinkedToTheControl() {
        withMountedRoot("picker-label") { root, element ->
            root.localDateTimeInput(label = "Beginn", picker = true)
            assertEquals("date", (element().controlOf("Beginn, Datum") as HTMLInputElement).type)
            assertEquals("time", (element().controlOf("Beginn, Uhrzeit") as HTMLInputElement).type)
        }
    }

    @Test
    fun forcedTextFallback_keepsTextFieldsHintsAndNoWrapper() {
        forceTextTemporalInputsForTest = true
        withMountedRoot("picker-fallback") { root, element ->
            val pair = root.localDateTimeInput(label = "Beginn", value = LocalDateTime(2026, 3, 5, 9, 5), picker = true)
            assertEquals("text", element().inputOf(0).type)
            assertEquals("05.03.2026", element().inputOf(0).value)
            assertEquals(2, element().querySelectorAll(".text-muted.small").length)
            assertNotNull(element().inputOf(0).getAttribute("aria-describedby"))
            assertEquals(0, element().querySelectorAll(".lapis-datetime-row").length)
            assertEquals(LocalDateTime(2026, 3, 5, 9, 5), pair.dateTimeOrNull())
        }
    }

    @Test
    fun defaultCallers_arePickers_withOneMinuteStep() {
        withMountedRoot("picker-default") { root, element ->
            val input = root.localDateInput(label = "Von", value = LocalDate(2026, 3, 5))
            val time = root.localTimeInput(label = "Um", value = LocalTime(9, 5))
            assertEquals("date", element().inputOf(0).type)
            assertEquals("2026-03-05", element().inputOf(0).value)
            assertEquals("time", element().inputOf(1).type)
            assertEquals("60", element().inputOf(1).getAttribute("step"))
            assertEquals(null, input.hint)
            assertEquals(LocalTime(9, 5), time.timeOrNull())
        }
    }

    @Test
    fun textFallback_keepsTheHintsAndTheLocaleText() {
        useTextTemporalInputs()
        withMountedRoot("picker-fallback") { root, element ->
            val input = root.localDateInput(label = "Von", value = LocalDate(2026, 3, 5))
            assertEquals("text", element().inputOf(0).type)
            assertEquals("05.03.2026", element().inputOf(0).value)
            assertNotNull(input.hint)
            assertTrue(element().inputOf(0).classList.contains("lapis-date-input"))
            assertEquals(0, element().querySelectorAll(".lapis-datetime-row").length)
        }
    }

    @Test
    fun setMinAndMax_areAttributesOnly_andLinkDateRangeFollowsTheOtherField() {
        withMountedRoot("picker-range") { root, element ->
            val from = root.localDateInput(label = "Von")
            val to = root.localDateInput(label = "Bis")
            linkDateRange(from, to)
            from.setDate(LocalDate(2026, 3, 5))
            assertEquals("2026-03-05", element().inputOf(1).getAttribute("min"))
            to.setDate(LocalDate(2026, 3, 9))
            assertEquals("2026-03-09", element().inputOf(0).getAttribute("max"))
            from.setDate(null)
            assertNull(element().inputOf(1).getAttribute("min"))
        }
    }
}
