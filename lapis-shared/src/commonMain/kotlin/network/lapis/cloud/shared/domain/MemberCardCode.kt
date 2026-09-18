package network.lapis.cloud.shared.domain

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the member-card bearer-code grammar, shared between
 * `lapis-server` (issuing, hashing, `/ausweis` lookup) and any future client-side pre-validation.
 * `commonMain` for the same reason [EventTicketCode] is: both platforms share exactly one
 * canonicalization implementation. No `SecureRandom` here (minting is JVM-only, see
 * `network.lapis.cloud.server.member.MemberCardPolicy`).
 *
 * **Deliberate duplicate of [EventTicketCode]'s grammar, not a shared base class.** Same alphabet
 * (Crockford-Base32), same length (16 characters / 80 bits), same display grouping -- but the two
 * domains (event tickets, member cards) may evolve independently, and refactoring [EventTicketCode]
 * itself is ticket-domain risk this wave does not want to take on.
 * `network.lapis.cloud.shared.domain.MemberCardCodeTest` pins today's identical shape (both
 * directions, against [EventTicketCode] directly) so a future drift is caught, not silently
 * accepted.
 */
object MemberCardCode {
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CANONICAL_LENGTH: Int = 16
    const val GROUP_SIZE: Int = 4

    /** "ABCD-EFGH-JKMN-PQRS" -- display-only grouping. NEVER feed this into a hash/lookup; always [canonicalize] first. */
    fun formatForDisplay(canonical: String): String = canonical.chunked(GROUP_SIZE).joinToString(separator = "-")

    /**
     * Canonicalizes free-form user input: trims, strips whitespace/hyphens, uppercases, repairs
     * `O`->`0` and `I`/`L`->`1`. Returns `null` unless the result is exactly [CANONICAL_LENGTH]
     * characters, all drawn from [ALPHABET] -- this check runs BEFORE any database access on the
     * server side (`/ausweis`'s first line of DoS defense, mirrors `EventCheckIn.byCode`'s own
     * posture for [EventTicketCode]).
     */
    fun canonicalize(raw: String?): String? {
        if (raw == null) return null
        val stripped =
            raw
                .trim()
                .uppercase()
                .filterNot { it.isWhitespace() || it == '-' }
                .map { c ->
                    when (c) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> c
                    }
                }.joinToString(separator = "")
        if (stripped.length != CANONICAL_LENGTH) return null
        if (stripped.any { it !in ALPHABET }) return null
        return stripped
    }

    /**
     * Accepts a bare code OR a full verification URL (`.../ausweis?code=XXXX`, with or without
     * further query parameters) -- extracts the `code` query parameter's value if present,
     * otherwise treats the whole string as a bare code, then [canonicalize]s the result. Pure
     * string handling (no platform URL parser), mirrors [EventTicketCode.extractAndCanonicalize]
     * exactly.
     */
    fun extractAndCanonicalize(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        val queryIndex = trimmed.indexOf('?')
        val candidate =
            if (queryIndex < 0) {
                trimmed
            } else {
                val query = trimmed.substring(queryIndex + 1)
                val codeParam =
                    query
                        .split('&')
                        .asSequence()
                        .map { it.split('=', limit = 2) }
                        .firstOrNull { it.isNotEmpty() && it[0] == "code" }
                        ?.getOrNull(1)
                codeParam
            }
        return canonicalize(candidate)
    }
}
