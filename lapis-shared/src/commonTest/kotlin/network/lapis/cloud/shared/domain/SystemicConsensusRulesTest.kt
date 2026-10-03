package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SystemicConsensusRulesTest {
    @Test
    fun threshold_isCeilOfNinetyPercentWithoutFloatingPoint() {
        assertEquals(1, SystemicConsensusRules.strongObjectionThreshold(1))
        assertEquals(5, SystemicConsensusRules.strongObjectionThreshold(5))
        assertEquals(7, SystemicConsensusRules.strongObjectionThreshold(7))
        assertEquals(9, SystemicConsensusRules.strongObjectionThreshold(10))
        assertEquals(10, SystemicConsensusRules.strongObjectionThreshold(11))
        assertEquals(18, SystemicConsensusRules.strongObjectionThreshold(20))
        assertEquals(1932735283, SystemicConsensusRules.strongObjectionThreshold(Int.MAX_VALUE))
    }

    @Test
    fun threshold_rejectsNonPositiveScale() {
        assertFailsWith<IllegalArgumentException> { SystemicConsensusRules.strongObjectionThreshold(0) }
    }

    private fun n(raw: String?) = SystemicConsensusRules.normalizeRationale(raw)

    @Test
    fun rationale_nullAndBlankMeanRemove() {
        assertIs<PublicTextNormalization.Empty>(n(null))
        assertIs<PublicTextNormalization.Empty>(n("   \n\t "))
    }

    @Test
    fun rationale_normalisesLineBreaksAndTabs() {
        assertEquals(PublicTextNormalization.Ok("a\nb c"), n(" a\r\nb\tc "))
    }

    @Test
    fun rationale_lengthBoundary() {
        assertIs<PublicTextNormalization.Ok>(n("x".repeat(1000)))
        assertIs<PublicTextNormalization.TooLong>(n("x".repeat(1001)))
    }

    @Test
    fun rationale_emojiCountedInUtf16Too() {
        // 600 code points but 1200 UTF-16 units -> must be rejected for the VARCHAR(1000) column.
        assertIs<PublicTextNormalization.TooLong>(n("😀".repeat(600)))
    }

    @Test
    fun rationale_rejectsControlAndBidiCharacters() {
        for (c in listOf("‮", "⁦", "\u0007", " ", "\uD83D")) {
            assertIs<PublicTextNormalization.ControlChars>(n("a${c}b"), "char ${c.first().code}")
        }
    }

    @Test
    fun rationale_limitsLineBreaks() {
        assertIs<PublicTextNormalization.Ok>(n("x" + "\ny".repeat(20)))
        assertIs<PublicTextNormalization.TooManyLineBreaks>(n("x" + "\ny".repeat(21)))
    }
}
