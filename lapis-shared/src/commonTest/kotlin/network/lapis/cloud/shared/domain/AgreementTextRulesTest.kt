package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.134 -- [AgreementTextRules]: the one rule set shared by the counter on the client and the validation on the server. */
class AgreementTextRulesTest {
    @Test
    fun normalizeBody_turnsCrlfAndCrIntoLf_andDropsTrailingWhitespaceOfTheDocument() {
        assertEquals("a\nb\nc", AgreementTextRules.normalizeBody("a\r\nb\rc  \r\n\r\n"))
        assertEquals("  eingerückt bleibt", AgreementTextRules.normalizeBody("  eingerückt bleibt\n"))
        assertEquals("", AgreementTextRules.normalizeBody(" \n\t "))
    }

    @Test
    fun normalizeBody_isIdempotent() {
        val once = AgreementTextRules.normalizeBody("x\r\ny  \n")
        assertEquals(once, AgreementTextRules.normalizeBody(once))
    }

    @Test
    fun suggestLabel_usesTheDateAndTheNumber() {
        assertEquals("2026-10-15.v3", AgreementTextRules.suggestLabel(today = LocalDate(2026, 10, 15), n = 3))
        assertTrue(AgreementTextRules.isValidLabel(AgreementTextRules.suggestLabel(today = LocalDate(2026, 1, 2), n = 12)))
    }

    @Test
    fun label_acceptsTheBuiltinLabels_andRefusesEverythingElse() {
        assertTrue(AgreementTextRules.isValidLabel("2026-07-23.v1"))
        assertTrue(AgreementTextRules.isValidLabel("A"))
        assertTrue(AgreementTextRules.isValidLabel("x".repeat(50)))
        assertFalse(AgreementTextRules.isValidLabel("x".repeat(51)))
        assertFalse(AgreementTextRules.isValidLabel(""))
        assertFalse(AgreementTextRules.isValidLabel("-start"))
        assertFalse(AgreementTextRules.isValidLabel("mit Leerzeichen"))
        assertFalse(AgreementTextRules.isValidLabel("zeile\n"))
        assertFalse(AgreementTextRules.isValidLabel("ümlaut"))
    }

    @Test
    fun invalidCharacter_flagsControlAndBidiCharacters_butNotLineFeedTabOrNormalText() {
        assertFalse(AgreementTextRules.invalidCharacter("Zeile eins\nZeile\tzwei äöüß € ✓"))
        assertTrue(AgreementTextRules.invalidCharacter("nul \u0000"))
        assertTrue(AgreementTextRules.invalidCharacter("bell \u0007"))
        assertTrue(AgreementTextRules.invalidCharacter("cr \r"))
        assertTrue(AgreementTextRules.invalidCharacter("del \u007F"))
        for (code in 0x202A..0x202E) assertTrue(AgreementTextRules.invalidCharacter("x${code.toChar()}y"), "U+${code.toString(16)}")
        for (code in 0x2066..0x2069) assertTrue(AgreementTextRules.invalidCharacter("x${code.toChar()}y"), "U+${code.toString(16)}")
    }

    @Test
    fun maxChars_is60000() {
        assertEquals(60_000, AgreementTextRules.MAX_CHARS)
        assertEquals(50, AgreementTextRules.VERSION_LABEL_MAX)
    }
}
