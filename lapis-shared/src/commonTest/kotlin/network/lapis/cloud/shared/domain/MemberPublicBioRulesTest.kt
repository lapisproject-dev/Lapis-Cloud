package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- [PublicTextRules]/[MemberPublicBioRules]: the ONE place that
 * decides what public free text is valid. Server (authoritative) and client (live counter) call the
 * same functions, so these tests pin the counting (code points, not UTF-16 units), the
 * normalization and every rejection.
 */
class MemberPublicBioRulesTest {
    private fun normalize(raw: String) = MemberPublicBioRules.normalize(raw)

    @Test
    fun codePointCount_countsASurrogatePairOnce() {
        assertEquals(1, PublicTextRules.codePointCount("😀"))
        assertEquals(3, PublicTextRules.codePointCount("a😀b"))
        assertEquals(0, PublicTextRules.codePointCount(""))
        // A lone high surrogate still counts as ONE unit (it is rejected as invalid elsewhere).
        assertEquals(1, PublicTextRules.codePointCount("\uD83D"))
    }

    @Test
    fun exactlyFiveHundredEmojis_areOk_fiveHundredAndOneAreTooLong() {
        val emoji = "😀"
        assertIs<PublicTextNormalization.Ok>(normalize(emoji.repeat(MemberPublicBioRules.MAX_CODEPOINTS)))
        assertEquals(PublicTextNormalization.TooLong, normalize(emoji.repeat(MemberPublicBioRules.MAX_CODEPOINTS + 1)))
    }

    @Test
    fun fiveHundredAsciiCharacters_areOk_fiveHundredAndOneAreTooLong() {
        assertIs<PublicTextNormalization.Ok>(normalize("a".repeat(500)))
        assertEquals(PublicTextNormalization.TooLong, normalize("a".repeat(501)))
    }

    @Test
    fun blankTextMeansEmpty() {
        assertEquals(PublicTextNormalization.Empty, normalize(""))
        assertEquals(PublicTextNormalization.Empty, normalize("   \n \t "))
    }

    @Test
    fun trimsAndUnifiesLineEndings() {
        val ok = assertIs<PublicTextNormalization.Ok>(normalize("  Hallo\r\nWelt\rEnde \n"))
        assertEquals("Hallo\nWelt\nEnde", ok.text)
    }

    @Test
    fun aTabBecomesASpace() {
        val ok = assertIs<PublicTextNormalization.Ok>(normalize("a\tb"))
        assertEquals("a b", ok.text)
    }

    @Test
    fun runsOfMoreThanTwoLineBreaksCollapseToTwo() {
        val ok = assertIs<PublicTextNormalization.Ok>(normalize("a\n\n\n\n\nb"))
        assertEquals("a\n\nb", ok.text)
    }

    @Test
    fun moreThanEightLineBreaksAreRejected_exactlyEightAreOk() {
        assertIs<PublicTextNormalization.Ok>(normalize((1..9).joinToString("\n") { "x$it" })) // 8 breaks
        assertEquals(PublicTextNormalization.TooManyLineBreaks, normalize((1..10).joinToString("\n") { "x$it" })) // 9 breaks
    }

    @Test
    fun controlCharactersAreRejected() {
        assertEquals(PublicTextNormalization.ControlChars, normalize("a\u0000b"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a\u0007b"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a\u007Fb"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a\u0085b"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a b"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a b"))
    }

    @Test
    fun bidiOverrideCharactersAreRejected() {
        assertEquals(PublicTextNormalization.ControlChars, normalize("a‮b"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a⁦b"))
    }

    @Test
    fun aLoneSurrogateIsRejected() {
        assertEquals(PublicTextNormalization.ControlChars, normalize("a\uD83Db"))
        assertEquals(PublicTextNormalization.ControlChars, normalize("a\uDE00b"))
    }

    @Test
    fun aZeroWidthJoinerInsideAnEmojiSequenceIsKept() {
        // ZWJ (U+200D) is part of legitimate emoji sequences -- not a control character here.
        val family = "👨‍👩‍👧"
        val ok = assertIs<PublicTextNormalization.Ok>(normalize(family))
        assertEquals(family, ok.text)
    }

    @Test
    fun consentVersionIsPinnedToTheWordingVersionTag() {
        assertEquals("member-bio-public-v1", MemberPublicBioRules.CONSENT_TEXT_VERSION)
        assertTrue(MemberPublicBioRules.MAX_CODEPOINTS == 500 && MemberPublicBioRules.MAX_LINE_BREAKS == 8)
    }

    @Test
    fun chapterDescriptionLimitsAreEnforcedByTheSameFunction() {
        val limit = RegionalChapterPublicRules.DESCRIPTION_MAX_CODEPOINTS
        val lines = RegionalChapterPublicRules.DESCRIPTION_MAX_LINE_BREAKS
        assertIs<PublicTextNormalization.Ok>(
            PublicTextRules.normalize(raw = "a".repeat(limit), maxCodePoints = limit, maxLineBreaks = lines),
        )
        assertEquals(
            PublicTextNormalization.TooLong,
            PublicTextRules.normalize(raw = "a".repeat(limit + 1), maxCodePoints = limit, maxLineBreaks = lines),
        )
        assertEquals(
            PublicTextNormalization.TooManyLineBreaks,
            PublicTextRules.normalize(
                raw = (1..(lines + 2)).joinToString("\n") { "x" },
                maxCodePoints = limit,
                maxLineBreaks = lines,
            ),
        )
    }

    @Test
    fun politicianListingIsNotALeaderboardButTheTwoRankingsAre() {
        assertTrue(PublicRankingKind.LTR_HOLDINGS.isLeaderboard)
        assertTrue(PublicRankingKind.DONATIONS.isLeaderboard)
        assertTrue(!PublicRankingKind.POLITICIAN_LISTING.isLeaderboard)
        // Append-only: the two original literals keep their ordinals.
        assertEquals(listOf("LTR_HOLDINGS", "DONATIONS", "POLITICIAN_LISTING"), PublicRankingKind.entries.map { it.name })
    }
}
