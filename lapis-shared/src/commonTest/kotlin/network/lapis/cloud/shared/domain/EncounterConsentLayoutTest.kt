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

    @Test
    fun mapParagraph_isFixedAssemblyCodeText_andChurchFailsClosed() {
        val paragraph = EncounterConsentLayout.mapParagraph(AgreementTextKind.ENC_ASSEM)
        assertTrue(paragraph.startsWith("Karte und Position. In diesem Raum gibt es eine Karte."))
        assertTrue(paragraph.contains("nur im Arbeitsspeicher des Servers"))
        assertTrue(paragraph.contains("nicht in der Datenbank, nicht in Protokollen und nicht in Exporten"))
        assertTrue(paragraph.contains("keine Liste, wer wann wo war"))
        assertTrue(paragraph.length <= EncounterConsentLayout.KEY_POINT_MAX, "the paragraph is a key point of the notice")
        assertFailsWith<IllegalArgumentException> { EncounterConsentLayout.mapParagraph(AgreementTextKind.ENC_CHURCH) }
        assertFailsWith<IllegalArgumentException> { EncounterConsentLayout.mapParagraph(AgreementTextKind.REC_ROOM) }
    }

    @Test
    fun splitOrNull_withSeveralExtraKeyPoints_appendsThemInOrder_andTheOldSignatureDelegates() {
        val both = EncounterConsentLayout.splitOrNull(body = valid, extraKeyPoints = listOf("Aufzeichnung", "Karte"))
        assertEquals(listOf("Punkt eins", "Punkt zwei", "Punkt drei", "Aufzeichnung", "Karte"), both?.keyPoints)
        assertEquals(valid, both?.text)
        assertEquals(
            EncounterConsentLayout.splitOrNull(body = valid, extraKeyPoint = "Aufzeichnung"),
            EncounterConsentLayout.splitOrNull(body = valid, extraKeyPoints = listOf("Aufzeichnung")),
        )
        assertEquals(
            EncounterConsentLayout.splitOrNull(body = valid),
            EncounterConsentLayout.splitOrNull(body = valid, extraKeyPoints = emptyList()),
        )
        assertNull(EncounterConsentLayout.splitOrNull(body = "zu\n\nkurz", extraKeyPoints = listOf("x")))
    }

    @Test
    fun systemSuffixes_fitTheAcknowledgmentColumn() {
        assertEquals("+map", EncounterConsentLayout.MAP_SUFFIX)
        assertEquals(8, EncounterConsentLayout.ENC_SYSTEM_SUFFIX_MAX)
        assertEquals(
            (EncounterConsentLayout.RECORDING_SUFFIX + EncounterConsentLayout.MAP_SUFFIX).length,
            EncounterConsentLayout.ENC_SYSTEM_SUFFIX_MAX,
        )
        assertEquals(54, EncounterConsentLayout.ENC_ACK_LABEL_MAX)
        assertTrue(EncounterConsentLayout.ENC_ACK_LABEL_MAX <= 58, "consent_version is VARCHAR(58) since V111")
        assertEquals(46, EncounterConsentLayout.ENC_LABEL_MAX, "published labels stay valid")
        assertFalse(AgreementTextRules.isValidLabel("2026-10-17.v2+map"))
    }
}
