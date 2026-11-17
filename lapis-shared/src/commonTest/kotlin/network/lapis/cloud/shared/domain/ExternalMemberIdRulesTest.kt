package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalMemberIdRulesTest {
    @Test
    fun lengthBoundaries() {
        assertEquals("a", ExternalMemberIdRules.normalizeId("a"))
        assertEquals("a".repeat(64), ExternalMemberIdRules.normalizeId("a".repeat(64)))
        assertNull(ExternalMemberIdRules.normalizeId("a".repeat(65)))
        assertNull(ExternalMemberIdRules.normalizeId(""))
        assertNull(ExternalMemberIdRules.normalizeId("   "))
    }

    @Test
    fun trimsButKeepsCase() {
        assertEquals("AbC-12", ExternalMemberIdRules.normalizeId("  AbC-12 "))
        assertEquals("abc", ExternalMemberIdRules.normalizeId("abc"))
    }

    @Test
    fun allowedAndRejectedCharacters() {
        assertEquals("W.1_2:3/4-5", ExternalMemberIdRules.normalizeId("W.1_2:3/4-5"))
        assertNull(ExternalMemberIdRules.normalizeId("a b"))
        assertNull(ExternalMemberIdRules.normalizeId("a;b"))
        assertNull(ExternalMemberIdRules.normalizeId("a\nb"))
        assertNull(ExternalMemberIdRules.normalizeId("a<b"))
    }

    @Test
    fun leadingSpecialCharactersAreRejected() {
        listOf("-a", ".a", "_a", ":a", "/a").forEach { assertNull(ExternalMemberIdRules.normalizeId(it), it) }
    }

    @Test
    fun sourceValidation() {
        assertTrue(ExternalMemberIdRules.isValidSource("Wabeo"))
        assertTrue(ExternalMemberIdRules.isValidSource("Lupa BB"))
        assertTrue(ExternalMemberIdRules.isValidSource("a".repeat(32)))
        assertFalse(ExternalMemberIdRules.isValidSource("a".repeat(33)))
        assertFalse(ExternalMemberIdRules.isValidSource(""))
        assertFalse(ExternalMemberIdRules.isValidSource(" Wabeo"))
        assertFalse(ExternalMemberIdRules.isValidSource("Wa;beo"))
    }
}
