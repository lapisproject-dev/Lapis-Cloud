package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V0.7.3 Basis-Mehrseiten-UI: this module had no `jsTest` source set at all before this wave (only
 * `build/tmp` artifacts existed) -- see the CHANGELOG's V0.7.3 entry "Testing approach" for the
 * full reasoning. In scope: pure, DOM-independent functions with real branching logic
 * ([Validation], [selectableRolesFor], [isRouteAllowed]) -- these run under the
 * Karma+ChromeHeadless `testTask` already configured in `lapis-client/build.gradle.kts`. Explicitly
 * NOT in scope: component-rendering/DOM tests or an E2E browser-automation framework -- see that
 * CHANGELOG entry for why (no existing UI-test harness in this repo, disproportionate scope for
 * this wave). Manual QA against the four seeded demo roles is the deliberate substitute for actual
 * on-screen behavior verification.
 */
class ValidationTest {
    @Test
    fun looksLikeEmail_acceptsAPlausibleAddress() {
        assertTrue(Validation.looksLikeEmail("amara.admin@example.org"))
    }

    @Test
    fun looksLikeEmail_rejectsMissingAtSign() {
        assertFalse(Validation.looksLikeEmail("not-an-email"))
    }

    @Test
    fun looksLikeEmail_rejectsMissingDotAfterAtSign() {
        assertFalse(Validation.looksLikeEmail("someone@example"))
    }

    @Test
    fun looksLikeEmail_rejectsLeadingAtSign() {
        assertFalse(Validation.looksLikeEmail("@example.org"))
    }

    // Review Runde 4: EMAIL_MAX_LENGTH clause -- mirrors passwordHint_flagsTooLong's coverage of
    // PASSWORD_MAX_LENGTH for the same reason (Review Runde 3 added the length clause to
    // looksLikeEmail without a pinning test; see Validation.EMAIL_MAX_LENGTH's own KDoc).
    @Test
    fun looksLikeEmail_acceptsAnAddressAtExactlyMaxLength() {
        val atMax = "a".repeat(308) + "@example.org"
        assertEquals(Validation.EMAIL_MAX_LENGTH, atMax.length)
        assertTrue(Validation.looksLikeEmail(atMax))
    }

    @Test
    fun looksLikeEmail_rejectsAnAddressOverMaxLength() {
        val overMax = "a".repeat(309) + "@example.org"
        assertEquals(Validation.EMAIL_MAX_LENGTH + 1, overMax.length)
        assertFalse(Validation.looksLikeEmail(overMax))
    }

    @Test
    fun isNonBlank_rejectsWhitespaceOnly() {
        assertFalse(Validation.isNonBlank("   "))
    }

    @Test
    fun passwordHint_flagsTooShort() {
        assertEquals("Mindestens 12 Zeichen.", Validation.passwordHint("short", "a@b.de"))
    }

    @Test
    fun passwordHint_flagsTooLong() {
        val tooLong = "a".repeat(Validation.PASSWORD_MAX_LENGTH + 1)
        assertEquals("Höchstens 128 Zeichen.", Validation.passwordHint(tooLong, "a@b.de"))
    }

    @Test
    fun passwordHint_flagsPasswordEqualToEmail_caseInsensitive() {
        val hint = Validation.passwordHint("Amara.Admin@Example.ORG", "amara.admin@example.org")
        assertEquals("Darf nicht die E-Mail-Adresse sein.", hint)
    }

    @Test
    fun passwordHint_acceptsAPlausiblePassword() {
        assertEquals(null, Validation.passwordHint("correct-horse-battery-staple", "amara.admin@example.org"))
    }

    @Test
    fun passwordsMatch_trueForIdenticalStrings() {
        assertTrue(Validation.passwordsMatch("secret-password-123", "secret-password-123"))
    }

    @Test
    fun passwordsMatch_falseForDifferentStrings() {
        assertFalse(Validation.passwordsMatch("secret-password-123", "different-password-456"))
    }

    @Test
    fun selectableRolesFor_adminMayChooseAnyRole() {
        assertEquals(AccountRole.entries.toList(), selectableRolesFor(AccountRole.ADMIN))
    }

    @Test
    fun selectableRolesFor_boardMayOnlyCreatePlainMembers() {
        assertEquals(listOf(AccountRole.MEMBER), selectableRolesFor(AccountRole.BOARD))
    }

    @Test
    fun selectableRolesFor_treasurerMayOnlyCreatePlainMembers() {
        assertEquals(listOf(AccountRole.MEMBER), selectableRolesFor(AccountRole.TREASURER))
    }

    @Test
    fun selectableRolesFor_memberMayOnlyCreatePlainMembers() {
        assertEquals(listOf(AccountRole.MEMBER), selectableRolesFor(AccountRole.MEMBER))
    }

    @Test
    fun isRouteAllowed_deniesUnauthenticatedCallerRegardlessOfRole() {
        assertFalse(isRouteAllowed(authenticated = false, callerRole = AccountRole.ADMIN, requiredRoles = emptySet()))
    }

    @Test
    fun isRouteAllowed_allowsAnyAuthenticatedCallerWhenNoRoleRequired() {
        assertTrue(isRouteAllowed(authenticated = true, callerRole = AccountRole.MEMBER, requiredRoles = emptySet()))
    }

    @Test
    fun isRouteAllowed_deniesMemberOnARoleGuardedRoute() {
        val requiredRoles = setOf(AccountRole.BOARD, AccountRole.ADMIN)
        assertFalse(isRouteAllowed(authenticated = true, callerRole = AccountRole.MEMBER, requiredRoles = requiredRoles))
    }

    @Test
    fun isRouteAllowed_allowsBoardOnARouteRequiringBoardOrAdmin() {
        val requiredRoles = setOf(AccountRole.BOARD, AccountRole.ADMIN)
        assertTrue(isRouteAllowed(authenticated = true, callerRole = AccountRole.BOARD, requiredRoles = requiredRoles))
    }

    // Welle V1.1.5 -- Routes.SOCIAL_MODERATION uses the same {BOARD, ADMIN} guard shape as
    // Routes.MEMBERS/DSGVO_COMPLIANCE/BOARD_MEMBERSHIP; MEMBER is denied, ADMIN is allowed.
    @Test
    fun isRouteAllowed_deniesMemberOnSocialModerationsBoardOrAdminGuard() {
        val requiredRoles = setOf(AccountRole.BOARD, AccountRole.ADMIN)
        assertFalse(isRouteAllowed(authenticated = true, callerRole = AccountRole.MEMBER, requiredRoles = requiredRoles))
    }

    @Test
    fun isRouteAllowed_allowsAdminOnSocialModerationsBoardOrAdminGuard() {
        val requiredRoles = setOf(AccountRole.BOARD, AccountRole.ADMIN)
        assertTrue(isRouteAllowed(authenticated = true, callerRole = AccountRole.ADMIN, requiredRoles = requiredRoles))
    }

    @Test
    fun isPositiveDecimal_acceptsAPlausibleAmount() {
        assertTrue(Validation.isPositiveDecimal("1.50"))
    }

    @Test
    fun isPositiveDecimal_acceptsAnAmountWithSurroundingWhitespace() {
        assertTrue(Validation.isPositiveDecimal("  0.01  "))
    }

    @Test
    fun isPositiveDecimal_rejectsZero() {
        assertFalse(Validation.isPositiveDecimal("0.00"))
    }

    @Test
    fun isPositiveDecimal_rejectsNegativeAmounts() {
        assertFalse(Validation.isPositiveDecimal("-5.00"))
    }

    @Test
    fun isPositiveDecimal_rejectsNonNumericText() {
        assertFalse(Validation.isPositiveDecimal("not-a-number"))
    }

    @Test
    fun isPositiveDecimal_rejectsBlank() {
        assertFalse(Validation.isPositiveDecimal(""))
    }

    /**
     * Security-Audit-Fund S-C1 (2026-08-18): `"Infinity"` is a value [String.toDoubleOrNull]
     * (Kotlin/JS) happily parses, and `Double.POSITIVE_INFINITY > 0.0` is `true` -- without the
     * [Double.isFinite] guard this used to slip through as a "positive decimal".
     */
    @Test
    fun isPositiveDecimal_rejectsInfinityLiteral() {
        assertFalse(Validation.isPositiveDecimal("Infinity"))
    }

    @Test
    fun isPositiveDecimal_rejectsNegativeInfinityLiteral() {
        assertFalse(Validation.isPositiveDecimal("-Infinity"))
    }

    @Test
    fun isPositiveDecimal_rejectsNaNLiteral() {
        assertFalse(Validation.isPositiveDecimal("NaN"))
    }

    // ── V1.2.2 SEPA-Client-UI wave: looksLikeIban/looksLikeBic/formatIbanGroups ────────────────────

    @Test
    fun looksLikeIban_acceptsValidGermanIban() {
        assertTrue(Validation.looksLikeIban("DE89370400440532013000"))
    }

    @Test
    fun looksLikeIban_acceptsValidGermanIbanGroupedWithSpaces() {
        assertTrue(Validation.looksLikeIban("DE89 3704 0044 0532 0130 00"))
    }

    @Test
    fun looksLikeIban_acceptsValidGermanIbanInLowercase() {
        assertTrue(Validation.looksLikeIban("de89370400440532013000"))
    }

    @Test
    fun looksLikeIban_acceptsValidAustrianIban() {
        assertTrue(Validation.looksLikeIban("AT611904300234573201"))
    }

    @Test
    fun looksLikeIban_acceptsValidDutchIban() {
        assertTrue(Validation.looksLikeIban("NL91ABNA0417164300"))
    }

    @Test
    fun looksLikeIban_rejectsBrokenCheckDigit() {
        // Last digit of a valid IBAN flipped -- shape still matches, mod-97 check digit fails.
        assertFalse(Validation.looksLikeIban("DE89370400440532013001"))
    }

    @Test
    fun looksLikeIban_rejectsBlank() {
        assertFalse(Validation.looksLikeIban(""))
    }

    @Test
    fun looksLikeIban_rejectsTooShort() {
        assertFalse(Validation.looksLikeIban("DE89"))
    }

    @Test
    fun looksLikeIban_rejectsTooLong() {
        assertFalse(Validation.looksLikeIban("DE89370400440532013000123456789012"))
    }

    @Test
    fun looksLikeIban_rejectsSpecialCharacters() {
        assertFalse(Validation.looksLikeIban("DE89-3704-0044-0532-0130-00"))
    }

    @Test
    fun looksLikeIban_rejectsDigitPrefixInsteadOfCountryCode() {
        assertFalse(Validation.looksLikeIban("1234DE370400440532013000"))
    }

    @Test
    fun looksLikeBic_acceptsElevenCharacterBic() {
        assertTrue(Validation.looksLikeBic("MARKDEF1100"))
    }

    @Test
    fun looksLikeBic_acceptsEightCharacterBic() {
        assertTrue(Validation.looksLikeBic("MARKDEFF"))
    }

    @Test
    fun looksLikeBic_rejectsSevenCharacters() {
        assertFalse(Validation.looksLikeBic("MARKDEF"))
    }

    @Test
    fun looksLikeBic_acceptsLowercaseAfterUppercasing() {
        assertTrue(Validation.looksLikeBic("markdeff"))
    }

    @Test
    fun formatIbanGroups_groupsInFours() {
        assertEquals("DE89 3704 0044 0532 0130 00", Validation.formatIbanGroups("DE89370400440532013000"))
    }

    @Test
    fun formatIbanGroups_isIdempotentOnAlreadyGroupedInput() {
        val once = Validation.formatIbanGroups("DE89370400440532013000")
        val twice = Validation.formatIbanGroups(once)
        assertEquals(once, twice)
    }

    @Test
    fun formatIbanGroups_doesNotChangeTheUnderlyingCharacterSequence() {
        val raw = "DE89370400440532013000"
        assertEquals(raw, Validation.formatIbanGroups(raw).replace(" ", ""))
    }
}
