package network.lapis.cloud.client

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- covers only the pure, DOM-independent helpers of
 * `CarpoolScreen.kt` (V1.9.137: the time field is a locale time field now, see `LocaleDateTest`). Same scope posture as
 * `CommunicationScreenTest.kt` (no DOM/rendering test harness exists in this module).
 */
class CarpoolScreenTest {
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
