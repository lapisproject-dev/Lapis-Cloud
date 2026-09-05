package network.lapis.cloud.shared.domain

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- the ticket-code grammar, shared between
 * `lapis-server` (issuing, hashing, door-scan validation) and `lapis-client`'s
 * `EventCheckInScreen` (local pre-validation of a hand-typed code BEFORE the network round-trip,
 * same "reject garbage before it reaches the network" posture the server side applies before any
 * database access -- see `network.lapis.cloud.server.events.EventCheckIn` KDoc). `commonMain` so
 * both platforms share exactly one canonicalization implementation; no `SecureRandom` here (code
 * MINTING is JVM-only, see `network.lapis.cloud.server.events.EventTicketPolicy`).
 *
 * **Alphabet: Crockford-Base32** (`0-9`, `A-Z` minus `I`, `L`, `O`, `U`) -- no visually-confusable
 * letters, chosen because a door volunteer may need to type a code by hand off a phone screen held
 * up by an impatient guest. [canonicalize] additionally repairs the two single-character typos a
 * human is most likely to make against this alphabet (`O`->`0`, `I`/`L`->`1`) rather than reject
 * them outright.
 *
 * **Why 16 characters (80 bits), not a longer code.** The code is read off a printed/on-screen
 * ticket and typed into an authenticated, rate-limited endpoint by a door volunteer -- it is never
 * brute-forceable at any practical rate against [network.lapis.cloud.server.security.LoginRateLimiter]-
 * style failure throttling, and 80 bits is already far beyond what that threat model requires. Four
 * groups of four characters ([formatForDisplay]) is a length a person can read off a screen and
 * type without losing their place, which a longer code (this repo's OTHER bearer tokens, e.g.
 * `EventPolicy.randomToken`'s 256-bit hex, are only ever clicked as a link, never hand-typed) would
 * not be.
 */
object EventTicketCode {
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CANONICAL_LENGTH: Int = 16
    const val GROUP_SIZE: Int = 4

    /** "ABCD-EFGH-JKMN-PQRS" -- display-only grouping. NEVER feed this into a hash/lookup; always [canonicalize] first. */
    fun formatForDisplay(canonical: String): String = canonical.chunked(GROUP_SIZE).joinToString(separator = "-")

    /**
     * Canonicalizes free-form user input: trims, strips whitespace/hyphens, uppercases, repairs
     * `O`->`0` and `I`/`L`->`1`. Returns `null` unless the result is exactly [CANONICAL_LENGTH]
     * characters, all drawn from [ALPHABET] -- this check runs BEFORE any database access on the
     * server side (`EventCheckIn.byCode`'s first line of DoS defense), so a malformed/garbage scan
     * never reaches a query.
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
     * Accepts a bare code OR a full ticket URL (`.../ticket?code=XXXX`, `.../ticket.pdf?code=XXXX`,
     * with or without further query parameters) -- extracts the `code` query parameter's value if
     * present, otherwise treats the whole string as a bare code, then [canonicalize]s the result.
     * Pure string handling (no `java.net.URI`/platform URL parser) so this stays usable from
     * `commonMain`/Kotlin-JS. A door volunteer scanning a QR with a generic camera app (rather than
     * this app's own, not-yet-built scanner) lands on the full URL, not the bare code -- this makes
     * pasting that URL into the check-in code field work exactly the same as typing the printed
     * code underneath the QR.
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
                // A "code" query param present-but-unparsable/empty is a deliberate non-match (falls
                // through to canonicalize(null) => null) rather than silently falling back to the
                // whole URL string, which could never canonicalize to a valid code anyway.
                codeParam
            }
        return canonicalize(candidate)
    }
}
