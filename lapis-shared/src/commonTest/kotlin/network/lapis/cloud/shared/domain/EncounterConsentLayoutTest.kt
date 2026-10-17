package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.148 -- the paragraph rule of an encounter entry notice, shared by server validation and the back-office preview. */
class EncounterConsentLayoutTest {
    private val valid = "Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetail eins\n\nDetail zwei"

    @Test
    fun split_takesHeadlineThreeKeyPointsAndKeepsTheBodyVerbatim() {
        val split = EncounterConsentLayout.split(body = valid)
        assertEquals("Überschrift", split.headline)
        assertEquals(listOf("Punkt eins", "Punkt zwei", "Punkt drei"), split.keyPoints)
        assertEquals(valid, split.text)
    }

    @Test
    fun split_appendsTheExtraKeyPointLast() {
        val split = EncounterConsentLayout.split(body = valid, extraKeyPoint = "Aufzeichnung")
        assertEquals(listOf("Punkt eins", "Punkt zwei", "Punkt drei", "Aufzeichnung"), split.keyPoints)
    }

    @Test
    fun separator_acceptsBlankLinesWithSpacesTabsAndSeveralLineFeeds() {
        assertEquals(listOf("a", "b", "c", "d"), EncounterConsentLayout.paragraphs(body = "a\n \nb\n\n\n\nc\n\t\nd"))
        assertEquals(listOf("a", "b"), EncounterConsentLayout.paragraphs(body = "a\r\n\r\nb"))
        // a single line feed is NOT a paragraph break
        assertEquals(listOf("a\nb"), EncounterConsentLayout.paragraphs(body = "a\nb"))
    }

    @Test
    fun threeParagraphs_areRejected() {
        assertEquals(
            EncounterConsentLayout.Violation.TOO_FEW_PARAGRAPHS,
            EncounterConsentLayout.violation(label = "l", body = "a\n\nb\n\nc"),
        )
        assertNull(EncounterConsentLayout.splitOrNull(body = "a\n\nb\n\nc"))
        assertFailsWith<IllegalArgumentException> { EncounterConsentLayout.split(body = "a\n\nb\n\nc") }
    }

    @Test
    fun exactlyFourParagraphs_areEnough() {
        assertNull(EncounterConsentLayout.violation(label = "l", body = "a\n\nb\n\nc\n\nd"))
    }

    @Test
    fun headlineLimit_is160() {
        val ok = "x".repeat(EncounterConsentLayout.HEADLINE_MAX) + "\n\nb\n\nc\n\nd"
        val tooLong = "x".repeat(EncounterConsentLayout.HEADLINE_MAX + 1) + "\n\nb\n\nc\n\nd"
        assertNull(EncounterConsentLayout.violation(label = "l", body = ok))
        assertEquals(EncounterConsentLayout.Violation.HEADLINE_TOO_LONG, EncounterConsentLayout.violation(label = "l", body = tooLong))
    }

    @Test
    fun keyPointLimit_is600_andOnlyAppliesToTheFirstThree() {
        val k = "k".repeat(EncounterConsentLayout.KEY_POINT_MAX)
        assertNull(EncounterConsentLayout.violation(label = "l", body = "a\n\n$k\n\n$k\n\n$k\n\n${"d".repeat(5000)}"))
        assertEquals(
            EncounterConsentLayout.Violation.KEY_POINT_TOO_LONG,
            EncounterConsentLayout.violation(label = "l", body = "a\n\n${k}x\n\nc\n\nd"),
        )
        assertEquals(
            EncounterConsentLayout.Violation.KEY_POINT_TOO_LONG,
            EncounterConsentLayout.violation(label = "l", body = "a\n\nb\n\nc\n\nd".replace("c", "${k}x")),
        )
    }

    @Test
    fun label_is46CharactersAtMost_soThatPlusRecFitsIntoVarchar50() {
        assertEquals(46, EncounterConsentLayout.ENC_LABEL_MAX)
        assertEquals(50, AgreementTextRules.VERSION_LABEL_MAX)
        assertNull(EncounterConsentLayout.violation(label = "a".repeat(46), body = valid))
        assertEquals(
            EncounterConsentLayout.Violation.LABEL_TOO_LONG,
            EncounterConsentLayout.violation(label = "a".repeat(47), body = valid),
        )
        assertTrue((("a".repeat(46)) + EncounterConsentLayout.RECORDING_SUFFIX).length <= AgreementTextRules.VERSION_LABEL_MAX)
    }

    @Test
    fun plusIsNotAValidLabelCharacter_soTheSuffixNamespaceBelongsToTheSystem() {
        assertFalse(AgreementTextRules.isValidLabel("2026-10-17.v2+rec"))
    }

    @Test
    fun recordingParagraph_isFixedCodeText_perKind() {
        assertTrue(EncounterConsentLayout.recordingParagraph(AgreementTextKind.ENC_CHURCH).contains("die Kanzel aufgezeichnet"))
        assertTrue(EncounterConsentLayout.recordingParagraph(AgreementTextKind.ENC_ASSEM).contains("das Podium aufgezeichnet"))
        assertNotNull(EncounterConsentLayout.recordingParagraph(AgreementTextKind.ENC_CHURCH).takeIf { it.contains("nie aufgezeichnet") })
    }

    @Test
    fun isEncounterConsent_isTrueForExactlyTheTwoEncKinds() {
        assertEquals(
            setOf(AgreementTextKind.ENC_CHURCH, AgreementTextKind.ENC_ASSEM),
            AgreementTextKind.entries.filter { it.isEncounterConsent() }.toSet(),
        )
    }
}
