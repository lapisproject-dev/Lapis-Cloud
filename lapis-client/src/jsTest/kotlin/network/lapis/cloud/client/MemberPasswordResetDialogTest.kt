package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.9 "Admin-Passwort-Reset" -- covers [MemberPasswordResetDialog.kt]'s pure, DOM-free
 * predicates/formatters, same DOM-free posture as [MemberAdministrationScreenTest].
 */
class MemberPasswordResetDialogTest {
    private val callerMemberId = "00000000-0000-0000-0000-000000000001"
    private val otherMemberId = "00000000-0000-0000-0000-000000000099"

    private fun row(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        anonymized: Boolean = false,
        id: String = otherMemberId,
    ) = MemberAdminRowDto(
        id = id,
        displayName = "Test Mitglied",
        email = "test@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = anonymized,
    )

    // ── canResetPasswordOf / passwordResetBlockReason ──

    @Test
    fun canResetPasswordOf_admin_ordinaryForeignRow_isAllowed() {
        assertTrue(canResetPasswordOf(AccountRole.ADMIN, callerMemberId, row()))
        assertNull(passwordResetBlockReason(AccountRole.ADMIN, callerMemberId, row()))
    }

    @Test
    fun passwordResetBlockReason_nonAdminCallers_areBlocked() {
        listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER, null).forEach { callerRole ->
            assertFalse(canResetPasswordOf(callerRole, callerMemberId, row()))
            assertNotNull(passwordResetBlockReason(callerRole, callerMemberId, row()))
        }
    }

    @Test
    fun passwordResetBlockReason_anonymizedRow_isBlocked() {
        val blocked = row(anonymized = true)
        assertFalse(canResetPasswordOf(AccountRole.ADMIN, callerMemberId, blocked))
        assertNotNull(passwordResetBlockReason(AccountRole.ADMIN, callerMemberId, blocked))
    }

    @Test
    fun passwordResetBlockReason_selfTarget_isBlocked() {
        val ownRow = row(id = callerMemberId)
        assertFalse(canResetPasswordOf(AccountRole.ADMIN, callerMemberId, ownRow))
    }

    @Test
    fun passwordResetBlockReason_accountlessRow_isBlocked() {
        val accountless = row(role = null)
        assertFalse(canResetPasswordOf(AccountRole.ADMIN, callerMemberId, accountless))
    }

    @Test
    fun passwordResetBlockReason_deceasedRow_isBlocked() {
        val deceased = row(status = MemberStatus.DECEASED)
        assertFalse(canResetPasswordOf(AccountRole.ADMIN, callerMemberId, deceased))
    }

    @Test
    fun passwordResetBlockReason_everyNonNullReason_isDistinctText() {
        val reasons =
            listOf(
                passwordResetBlockReason(AccountRole.ADMIN, callerMemberId, row(anonymized = true)),
                passwordResetBlockReason(AccountRole.ADMIN, callerMemberId, row(id = callerMemberId)),
                passwordResetBlockReason(AccountRole.ADMIN, callerMemberId, row(role = null)),
                passwordResetBlockReason(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.DECEASED)),
                passwordResetBlockReason(AccountRole.BOARD, callerMemberId, row()),
            )
        reasons.forEach { assertNotNull(it) }
        assertEquals(reasons.size, reasons.toSet().size)
    }

    // ── temporaryPasswordConsequence ──

    @Test
    fun temporaryPasswordConsequence_singularPluralZero_areGrammaticallyDistinct() {
        val zero = temporaryPasswordConsequence(0)
        val one = temporaryPasswordConsequence(1)
        val three = temporaryPasswordConsequence(3)
        assertTrue(zero != one && one != three && zero != three)
        assertTrue(three.contains("3"))
    }

    // ── resetMailBlockReason ──

    @Test
    fun resetMailBlockReason_notConfigured_mentionsSmtpEnvPrefix() {
        val reason = resetMailBlockReason(MailDeliveryState.NOT_CONFIGURED, MemberStatus.ACTIVE)
        assertNotNull(reason)
        assertTrue(reason.contains("LAPIS_SMTP_"))
    }

    @Test
    fun resetMailBlockReason_loginBlockedStatuses_areAllBlocked() {
        listOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR).forEach { status ->
            assertNotNull(resetMailBlockReason(MailDeliveryState.HANDED_TO_SMTP, status))
        }
    }

    @Test
    fun resetMailBlockReason_activeStatus_handedToSmtp_isAllowed() {
        assertNull(resetMailBlockReason(MailDeliveryState.HANDED_TO_SMTP, MemberStatus.ACTIVE))
    }

    // ── formatDictatablePassword ──

    @Test
    fun formatDictatablePassword_fourGroupsOfFour_hyphenSeparated() {
        val indices = IntArray(16) { it % PASSWORD_ALPHABET.length }
        val formatted = formatDictatablePassword(indices)
        assertEquals(19, formatted.length)
        assertEquals(3, formatted.count { it == '-' })
        val groups = formatted.split("-")
        assertEquals(4, groups.size)
        groups.forEach { assertEquals(4, it.length) }
    }

    // ── generateDictatablePasswordOrNull ── (Review-Fund: bisher ungetestet; Karma+ChromeHeadless
    // stellt `window.crypto.getRandomValues` bereit, ein direkter Test ist also möglich)

    @Test
    fun generateDictatablePasswordOrNull_underChromeHeadless_neverNull_correctShape() {
        repeat(50) {
            val password = generateDictatablePasswordOrNull()
            assertNotNull(password)
            assertEquals(19, password.length)
            assertEquals(3, password.count { it == '-' })
            password.filter { it != '-' }.forEach { ch -> assertTrue(ch in PASSWORD_ALPHABET) }
        }
    }

    @Test
    fun generateDictatablePasswordOrNull_manyDraws_areNotAllIdentical() {
        val draws = (1..50).map { generateDictatablePasswordOrNull() }
        assertTrue(draws.toSet().size > 1)
    }

    /**
     * Spiegelt `TemporaryPasswordGeneratorTest`s eigenen "rejection sampling avoids the 256 % 31
     * modulo bias"-Test serverseitig: pinnt das DOKUMENTIERTE Invariant direkt über eine große
     * Stichprobe, statt sich auf `window.crypto`s interne Verteilung zu verlassen. Ersetzte ein
     * späterer Edit die Rejection-Sampling-Schleife (Zeile 122-123 der KDoc-Warnung) durch ein
     * nacktes `raw % 31`, würden die ersten `256 % 31 == 8` Buchstaben des Alphabets ~26% häufiger
     * gezogen als die übrigen 23 -- dieser Test schlägt dann fehl.
     */
    @Test
    fun generateDictatablePasswordOrNull_largeSample_noModuloBiasAcrossAlphabetPositions() {
        val alphabetSize = PASSWORD_ALPHABET.length
        val tally = IntArray(alphabetSize)
        repeat(300) {
            val password = generateDictatablePasswordOrNull()
            assertNotNull(password)
            password.filter { it != '-' }.forEach { ch ->
                tally[PASSWORD_ALPHABET.indexOf(ch)]++
            }
        }
        val total = tally.sum()
        val expectedPerBucket = total.toDouble() / alphabetSize
        // Große Toleranz (60%) -- dies ist ein Bias-Rauchtest, kein statistisch scharfer Test:
        // ein echter 256%31-Modulo-Bias würde die ersten 8 Buckets um ~26% ÜBER den Durchschnitt
        // heben, weit außerhalb dieser Toleranz.
        tally.forEach { count ->
            assertTrue(count > expectedPerBucket * 0.4)
            assertTrue(count < expectedPerBucket * 1.6)
        }
    }
}
