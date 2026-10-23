package network.lapis.cloud.shared.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val strict = Json { ignoreUnknownKeys = false }

private const val VALID_NOTICE = "Titel\n\nErster Punkt.\n\nZweiter Punkt.\n\nDritter Punkt.\n\nVolltext."

/** V1.9.196 -- the format of an agreement text, the wire compatibility of the extended DTOs and the block-markup rule of the notice summary. */
class AgreementTextFormatTest {
    @Test
    fun fromDb_mapsMarkdown_andEverythingElseToPlain() {
        assertEquals(AgreementTextFormat.MARKDOWN, AgreementTextFormat.fromDb("markdown"))
        assertEquals(AgreementTextFormat.PLAIN, AgreementTextFormat.fromDb("plain"))
        assertEquals(AgreementTextFormat.PLAIN, AgreementTextFormat.fromDb("html"))
        assertEquals(AgreementTextFormat.PLAIN, AgreementTextFormat.fromDb(""))
        assertEquals("markdown", AgreementTextFormat.MARKDOWN.dbValue)
        assertEquals("plain", AgreementTextFormat.PLAIN.dbValue)
    }

    @Test
    fun dtosWithoutTheNewFields_deserializeWithDefaults() {
        val agreement = strict.decodeFromString(MembershipAgreementDto.serializer(), """{"version":"v1","text":"t","sha256":"h"}""")
        assertEquals(AgreementTextFormat.PLAIN, agreement.format)
        assertNull(agreement.renderedHtml)
        val terms = strict.decodeFromString(FriendTermsDto.serializer(), """{"version":"v1","text":"t","sha256":"h"}""")
        assertEquals(AgreementTextFormat.PLAIN, terms.format)
        val finder = strict.decodeFromString(MemberFinderConsentTextDto.serializer(), """{"versionLabel":"v1","body":"b","sha256":"h"}""")
        assertNull(finder.renderedHtml)
        val encounter =
            strict.decodeFromString(
                EncounterConsentDisclaimerDto.serializer(),
                """{"version":"v1","headline":"h","keyPoints":["a"],"text":"t","sha256":"s"}""",
            )
        assertEquals(AgreementTextFormat.PLAIN, encounter.format)
        val event =
            strict.decodeFromString(
                EventCaptureNoticeDto.serializer(),
                """{"versionLabel":"v","sha256":"s","headline":"h","keyPoints":[],"fullText":"t"}""",
            )
        assertNull(event.renderedHtml)
        val pulpit =
            strict.decodeFromString(
                PulpitRecordingTextsDto.serializer(),
                """{"pulpitVersionLabel":"v","pulpitSha256":"s","pulpitText":"p","roomText":"r"}""",
            )
        assertEquals(AgreementTextFormat.PLAIN, pulpit.pulpitFormat)
        assertEquals(AgreementTextFormat.PLAIN, pulpit.roomFormat)
        assertNull(pulpit.pulpitHtml)
        assertNull(pulpit.roomHtml)
    }

    @Test
    fun anOldClientIgnoringUnknownKeys_readsTheNewShape() {
        val lenient = Json { ignoreUnknownKeys = true }
        val dto =
            MembershipAgreementDto(
                version = "v",
                text = "t",
                sha256 = "h",
                format = AgreementTextFormat.MARKDOWN,
                renderedHtml = "<p>t</p>",
            )
        val wire = strict.encodeToString(MembershipAgreementDto.serializer(), dto)
        assertEquals(dto, lenient.decodeFromString(MembershipAgreementDto.serializer(), wire))
    }

    @Test
    fun draftInput_hasNoFormatField() {
        val d = AgreementTextDraftInput.serializer().descriptor
        assertEquals(listOf("kind", "versionLabel", "validFrom", "body"), (0 until d.elementsCount).map { d.getElementName(it) })
    }

    @Test
    fun blockMarkupInTheSummary_isRefusedForMarkdownOnly() {
        assertNull(EncounterConsentLayout.violation(label = "", body = VALID_NOTICE, format = AgreementTextFormat.MARKDOWN))
        val forbidden =
            listOf(
                "# Titel\n\nA.\n\nB.\n\nC.\n\nRest",
                "Titel\n\n- A\n\nB.\n\nC.\n\nRest",
                "Titel\n\nA.\n\n* B\n\nC.\n\nRest",
                "Titel\n\nA.\n\nB.\n\n1. C\n\nRest",
                "Titel\n\nA.\n\nB.\n\n2) C\n\nRest",
                "Titel\n\n> Zitat\n\nB.\n\nC.\n\nRest",
                "Titel\n\n```\ncode\n```\n\nB.\n\nC.\n\nRest",
                "Titel\n\n~~~\ncode\n~~~\n\nB.\n\nC.\n\nRest",
                "Titel\n\nZeile\n- Liste in Zeile zwei\n\nB.\n\nC.\n\nRest",
                "   ## eingerückt\n\nA.\n\nB.\n\nC.\n\nRest",
            )
        forbidden.forEach { body ->
            assertEquals(
                EncounterConsentLayout.Violation.BLOCK_MARKUP_IN_SUMMARY,
                EncounterConsentLayout.violation(label = "", body = body, format = AgreementTextFormat.MARKDOWN),
                body,
            )
            // the same text is fine as plain text (the old behaviour is unchanged)
            assertNull(EncounterConsentLayout.violation(label = "", body = body, format = AgreementTextFormat.PLAIN), body)
            assertNull(EncounterConsentLayout.violation(label = "", body = body), body)
        }
    }

    @Test
    fun blockMarkupAfterTheFourthParagraph_andOrdinaryText_areFine() {
        assertNull(
            EncounterConsentLayout.violation(
                label = "",
                body = "$VALID_NOTICE\n\n## Mehr\n\n- a\n- b\n\n> x",
                format = AgreementTextFormat.MARKDOWN,
            ),
        )
        // a hash tag or a number inside a sentence is not block markup
        assertNull(
            EncounterConsentLayout.violation(
                label = "",
                body = "#Hashtag Titel\n\n2026 war gut.\n\nA - B.\n\nC > D.\n\nRest",
                format = AgreementTextFormat.MARKDOWN,
            ),
        )
    }

    @Test
    fun hasBlockMarkup_checksEveryLine() {
        assertTrue(EncounterConsentLayout.hasBlockMarkup("Text\n\n- Liste"))
        assertTrue(EncounterConsentLayout.hasBlockMarkup("## Titel"))
        assertEquals(false, EncounterConsentLayout.hasBlockMarkup("Ein **fetter** Satz.\nZeile zwei."))
    }
}
