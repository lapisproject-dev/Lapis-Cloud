package network.lapis.cloud.client

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- covers only the pure, DOM-independent [parseTimeInputValue]
 * factored out of `CarpoolScreen.kt`'s form-submit handler. Same scope posture as
 * `CommunicationScreenTest.kt` (no DOM/rendering test harness exists in this module).
 */
class CarpoolScreenTest {
    @Test
    fun parseTimeInputValue_blankOrWhitespace_isNull() {
        assertNull(parseTimeInputValue(""))
        assertNull(parseTimeInputValue("   "))
    }

    @Test
    fun parseTimeInputValue_hhMm_normalizesToHhMmSs() {
        assertEquals(LocalTime(14, 30, 0), parseTimeInputValue("14:30"))
    }

    @Test
    fun parseTimeInputValue_hhMmSs_isPassedThroughUnchanged() {
        assertEquals(LocalTime(14, 30, 15), parseTimeInputValue("14:30:15"))
    }

    @Test
    fun parseTimeInputValue_garbage_isNull() {
        assertNull(parseTimeInputValue("not a time"))
    }

    @Test
    fun departureTimeSuffix_flexible_isResolvedText_notAnI18nMarker() {
        val suffix = carpoolDepartureTimeSuffix(null)
        assertFalse(suffix.contains(KV_I18N_MARKER), "marker leaked into the date line: $suffix")
        assertEquals(", Uhrzeit flexibel", suffix)
    }

    @Test
    fun departureTimeSuffix_withTime_isResolvedText() {
        val suffix = carpoolDepartureTimeSuffix(LocalTime(14, 30, 0))
        assertFalse(suffix.contains(KV_I18N_MARKER), suffix)
        assertEquals(", ab 14:30 Uhr", suffix)
    }
}
