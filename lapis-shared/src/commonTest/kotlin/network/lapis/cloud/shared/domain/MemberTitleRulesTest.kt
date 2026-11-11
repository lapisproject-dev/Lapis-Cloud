package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberTitleRulesTest {
    @Test
    fun normalizeTrimsCollapsesAndClears() {
        assertEquals("Prof. Dr.", MemberTitleRules.normalize("  Prof.   Dr.  "))
        assertEquals("Dr.", MemberTitleRules.normalize("Dr.\t"))
        assertNull(MemberTitleRules.normalize("   "))
        assertNull(MemberTitleRules.normalize(""))
        assertNull(MemberTitleRules.normalize(null))
        assertEquals("dr", MemberTitleRules.normalize("dr"))
    }

    @Test
    fun nullAndTypicalTitlesAreValid() {
        assertNull(MemberTitleRules.violation(null))
        MemberTitleRules.SUGGESTIONS.forEach { assertNull(MemberTitleRules.violation(it), it) }
    }

    @Test
    fun lengthBoundaryIsFiftyCodePoints() {
        assertNull(MemberTitleRules.violation("x".repeat(50)))
        assertEquals(MemberTitleViolation.TOO_LONG, MemberTitleRules.violation("x".repeat(51)))
        // 50 emoji = 100 UTF-16 units but 50 code points: still valid; 51 is too long
        val emoji = "😀"
        assertNull(MemberTitleRules.violation(emoji.repeat(50)))
        assertEquals(MemberTitleViolation.TOO_LONG, MemberTitleRules.violation(emoji.repeat(51)))
    }

    @Test
    fun controlAndMarkupCharactersAreRejected() {
        listOf("a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007Fb", "<b>", "a>b", "a‮b", "a⁦b", "a​b").forEach {
            assertEquals(MemberTitleViolation.INVALID_CHARACTERS, MemberTitleRules.violation(it), it)
        }
    }
}
