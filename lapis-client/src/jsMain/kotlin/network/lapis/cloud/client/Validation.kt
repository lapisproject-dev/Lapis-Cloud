package network.lapis.cloud.client

import io.kvision.i18n.gettext
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
            password.length < PASSWORD_MIN_LENGTH -> gettext("Mindestens %1 Zeichen.", PASSWORD_MIN_LENGTH)
            password.length > PASSWORD_MAX_LENGTH -> gettext("Höchstens %1 Zeichen.", PASSWORD_MAX_LENGTH)
            email.isNotBlank() && password.equals(email, ignoreCase = true) ->
                gettext("Darf nicht die E-Mail-Adresse sein.")
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
     *
     * Security-Audit-Fund S-C1 (2026-08-18, Welle V1.1.2): [String.toDoubleOrNull] (Kotlin/JS)
     * successfully parses the literal `"Infinity"` (and `"-Infinity"`/`"NaN"`), and
     * `Double.POSITIVE_INFINITY > 0.0` is `true` -- without the [Double.isFinite] check below, a
     * boost/reply confirmation dialog would show a nonsense amount before the server-side
     * `Decimal` serialization/`normalizeWeight` rejected it. Purely cosmetic/misleading, never a
     * server-side risk (same "loose mirror, not the security boundary" posture as every other
     * function in this object) -- but worth guarding since the whole POINT of this function is to
     * give an accurate preview before the round trip.
     */
    fun isPositiveDecimal(value: String): Boolean = value.trim().toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } ?: false

    /**
     * Soziales Netzwerk V1.1.2, Stolperfalle 15 (Review Runde 1, 2026-08-18): [isPositiveDecimal]
     * accepts a value like "1.005" (three decimal places) -- the server's own `normalizeWeight`
     * only rejects it AFTER the round trip, with a `ConflictException` ("must have at most 2
     * decimal places") that reads like a bug to whoever typed it, not a validation message. Rounds
     * to 2 places client-side BEFORE the value is sent as a `Decimal`, same "loose mirror, not the
     * security boundary" posture as every other function in this object -- the server still
     * re-normalizes authoritatively, this just avoids surfacing that specific confusing round trip
     * for the extremely common case of one stray extra digit.
     *
     * **Not HALF_UP** (Review Runde 2, 2026-08-18): [kotlin.math.round] ties-to-even (banker's
     * rounding), unlike [network.lapis.cloud.server.economy.WeightDecayClock.round2]'s
     * `HALF_UP`. A tie like `0.125` rounds here to `0.12`, not `0.13`. Harmless in practice --
     * at most 0.005 LTR different from the server's own authoritative re-normalization, never in
     * the caller's disfavor -- but real, so this KDoc no longer claims otherwise.
     */
    fun roundToTwoDecimalPlaces(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    /**
     * V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- design review D6: a malformed
     * room-id entered in the guest lobby's own field currently produces only the generic
     * "Ungültige Anfrage."/"Nicht gefunden." toast (the exact N1 confusion this whole wave exists
     * to avoid). Loose UUIDv4-shape check (`kotlin.uuid.Uuid.parse`'s own accepted grammar is wider
     * than this, but a plain 8-4-4-4-12 hex-with-dashes shape is what every room id this codebase
     * mints actually looks like -- see `ConferenceService.createRoom`'s `Uuid.random()`) -- same
     * "loose mirror, not the security boundary" posture every other function in this object
     * documents. The server is still the real validator.
     */
    fun looksLikeRoomId(value: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value.trim())
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
