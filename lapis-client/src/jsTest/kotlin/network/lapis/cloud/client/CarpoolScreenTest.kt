package network.lapis.cloud.client

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Welle V1.9.12 "Mitfahrzentrale" -- covers only the pure, DOM-independent helpers of
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

    // ── V1.9.186: formatCarpoolPlace ────────────────────────────────────────────────────────────────────────────

    @Test
    fun place_postalCodeUpperCaseAndCountryAreTidied() {
        assertEquals("Merseburg (Saale)", formatCarpoolPlace("06217 MERSEBURG (SAALE), DEUTSCHLAND"))
    }

    @Test
    fun place_withoutPostalCode_isTitleCased() {
        assertEquals("Braunschweig", formatCarpoolPlace("BRAUNSCHWEIG"))
    }

    @Test
    fun place_countrySegmentInAnyCaseAndWithDeSuffix_isDropped() {
        assertEquals("Berlin", formatCarpoolPlace("Berlin, GERMANY"))
        assertEquals("Berlin", formatCarpoolPlace("Berlin, Deutschland"))
        assertEquals("Berlin", formatCarpoolPlace("Berlin, deutschland (DE)"))
    }

    @Test
    fun place_hyphenatedNameIsTitleCasedPerPart() {
        assertEquals("Bad-Soden", formatCarpoolPlace("BAD-SODEN"))
    }

    @Test
    fun place_particlesAreLowerCaseExceptAsFirstToken() {
        assertEquals("Frankfurt am Main", formatCarpoolPlace("FRANKFURT AM MAIN"))
        // A lone upper-case particle is the first token and therefore stays a name ("Am"); documented behaviour, not a feature.
        assertEquals("Am", formatCarpoolPlace("AM"))
    }

    @Test
    fun place_mixedCaseIsLeftUnchanged() {
        assertEquals("Frankfurt (Oder)", formatCarpoolPlace("Frankfurt (Oder)"))
        assertEquals("Neustadt an der Weinstraße", formatCarpoolPlace("Neustadt an der Weinstraße"))
        assertEquals("Frankfurt Am Main", formatCarpoolPlace("Frankfurt Am Main"))
    }

    @Test
    fun place_emptyAndWhitespaceOnly_giveAnEmptyString() {
        assertEquals("", formatCarpoolPlace(""))
        assertEquals("", formatCarpoolPlace("   "))
    }

    @Test
    fun place_onlyPostalCodeAndCountry_fallsBackToTheTrimmedOriginal() {
        assertEquals("38100, Deutschland", formatCarpoolPlace("  38100, Deutschland "))
        assertEquals("Deutschland", formatCarpoolPlace("Deutschland"))
    }

    @Test
    fun place_wordWithDigitsIsUnchanged_andPrefixedPostalCodeIsStripped() {
        assertEquals("A2 Raststätte", formatCarpoolPlace("A2 Raststätte"))
        assertEquals("Braunschweig", formatCarpoolPlace("D-38100 BRAUNSCHWEIG"))
        assertEquals("Braunschweig", formatCarpoolPlace("DE-38100 Braunschweig"))
    }

    @Test
    fun place_umlautsSurvive() {
        assertEquals("Köln, Ülzen", formatCarpoolPlace("KÖLN, ÜLZEN"))
        assertEquals("Ärgersheim", formatCarpoolPlace("ÄRGERSHEIM"))
    }

    @Test
    fun place_commaInsideParenthesesDoesNotSplit() {
        assertEquals("Neustadt (Hessen, Kreis)", formatCarpoolPlace("NEUSTADT (Hessen, Kreis)"))
    }

    @Test
    fun place_aForgedMarkerStaysInert() {
        val formatted = formatCarpoolPlace("${KV_I18N_MARKER}Ort")
        assertEquals(
            "${KV_I18N_MARKER}Ort",
            formatted,
            "the formatter changes nothing about a marker string; sanitizing happens at the sink",
        )
    }
}
