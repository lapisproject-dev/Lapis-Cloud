package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole

/**
 * Pure, DOM-independent, client-side-only UX validators -- a convenience for the user, NEVER the
 * security boundary. The server re-validates everything authoritatively (see
 * `network.lapis.cloud.server.security.PasswordPolicy`, `RegistrationService`, `AuthService`) --
 * these functions exist only to give immediate feedback before a round-trip, and their bounds are
 * a deliberately loose mirror of the server policy, not a duplicate of its security logic. Kept
 * DOM-free so they are directly unit-testable under the Karma+ChromeHeadless jsTest task without
 * any KVision component instantiation -- see `ValidationTest.kt`.
 */
object Validation {
    /** Mirrors `network.lapis.cloud.server.security.PasswordPolicy.MIN_LENGTH`. */
    const val PASSWORD_MIN_LENGTH: Int = 12

    /** Mirrors `network.lapis.cloud.server.security.PasswordPolicy.MAX_LENGTH`. */
    const val PASSWORD_MAX_LENGTH: Int = 128

    /** A deliberately loose "looks like an email" check -- the server is the real validator. */
    fun looksLikeEmail(value: String): Boolean {
        val trimmed = value.trim()
        val at = trimmed.indexOf('@')
        return at > 0 && at < trimmed.length - 1 && trimmed.indexOf('.', at) > at
    }

    fun isNonBlank(value: String): Boolean = value.isNotBlank()

    /** UX mirror of `PasswordPolicy.validate`'s length/self-email checks -- returns a
     * human-readable hint, or `null` if the password looks acceptable client-side. */
    fun passwordHint(
        password: String,
        email: String,
    ): String? =
        when {
            password.length < PASSWORD_MIN_LENGTH -> "Mindestens $PASSWORD_MIN_LENGTH Zeichen."
            password.length > PASSWORD_MAX_LENGTH -> "Höchstens $PASSWORD_MAX_LENGTH Zeichen."
            email.isNotBlank() && password.equals(email, ignoreCase = true) -> "Darf nicht die E-Mail-Adresse sein."
            else -> null
        }

    fun passwordsMatch(
        password: String,
        confirmation: String,
    ): Boolean = password == confirmation

    /**
     * Governance UI wave (Motions & Voting screen): UX pre-check for `VoteBallotInput.stakeLtr`'s
     * plain-text input -- see the design review's implementation note ("no numeric/Decimal input
     * widget has a precedent in this client yet ... a plain `text` input with a client-side
     * numeric-and-non-negative check ... mirroring this file's existing pure-function style").
     * The server's own floor (currently 0.01 LTR, `GovernanceService.MIN_STAKE_LTR`) is
     * deliberately NOT duplicated here -- same "loose mirror, not the security boundary" posture
     * every other function in this object already documents; a stake that passes this check but
     * misses the server's exact floor still gets a clear `guarded()` conflict toast.
     */
    fun isPositiveDecimal(value: String): Boolean = value.trim().toDoubleOrNull()?.let { it > 0.0 } ?: false
}

/**
 * BOARD/ADMIN-creation role gating for the "Mitglied direkt anlegen" form
 * ([network.lapis.cloud.shared.rpc.IRegistrationService.createMemberDirect]) -- a BOARD caller may
 * only create plain MEMBER accounts, only ADMIN may create an escalated role (BOARD/TREASURER/
 * ADMIN), see that method's own KDoc. Pure function so the UI-disabling logic (rather than letting
 * a BOARD caller submit an escalated role and have the server reject it) is directly testable.
 */
fun selectableRolesFor(callerRole: AccountRole): List<AccountRole> =
    when (callerRole) {
        AccountRole.ADMIN -> AccountRole.entries
        else -> listOf(AccountRole.MEMBER)
    }
