package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.311 -- the postcode rule (first run of exactly five ASCII digits, 01001..99998), identical on the JVM and in Kotlin/JS. */
class ExtractPostcodeAreaTest {
    @Test
    fun findsThePostcodeInTypicalPlaces() {
        assertEquals("38", extractPostcodeArea("Hauptstr. 12, 38100 Braunschweig"))
        assertEquals("38", extractPostcodeArea("D-38100"))
        assertEquals("38", extractPostcodeArea("de-38100 X"))
        assertEquals("38", extractPostcodeArea("Braunschweig 38100"))
        assertEquals("38", extractPostcodeArea("Ä 38100"))
    }

    @Test
    fun theFirstPostcodeWins() {
        assertEquals("38", extractPostcodeArea("38100 Braunschweig, 10115 Berlin"))
        assertEquals("38", extractPostcodeArea("38100-38102"))
        // The first run is out of range: null, the next run is NOT tried.
        assertNull(extractPostcodeArea("00000 und 38100"))
    }

    @Test
    fun noPostcodeMeansNull() {
        assertNull(extractPostcodeArea("381000"))
        assertNull(extractPostcodeArea(""))
        assertNull(extractPostcodeArea("Braunschweig"))
        assertNull(extractPostcodeArea("3810"))
        assertNull(extractPostcodeArea("Wien 1010"))
        assertNull(extractPostcodeArea("Zürich 8001"))
        assertNull(extractPostcodeArea("A-1010 Wien"))
    }

    @Test
    fun nonAsciiDigitsNeverMatch() {
        assertNull(extractPostcodeArea("３８１００"))
        assertNull(extractPostcodeArea("٣٨١٠٠"))
    }

    @Test
    fun rangeBounds() {
        assertNull(extractPostcodeArea("00000"))
        assertNull(extractPostcodeArea("01000"))
        assertEquals("01", extractPostcodeArea("01001"))
        assertEquals("99", extractPostcodeArea("99998"))
        assertNull(extractPostcodeArea("99999"))
    }
}
