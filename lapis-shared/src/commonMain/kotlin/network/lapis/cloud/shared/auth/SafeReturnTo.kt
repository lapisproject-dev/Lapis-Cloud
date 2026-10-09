package network.lapis.cloud.shared.auth

/** Upper bound for a `returnTo` target (characters, after the single decode). Longer values are rejected. */
const val RETURN_TO_MAX_LENGTH = 4096

/** The ONLY path a `returnTo` target may point at: the OAuth authorize endpoint's own consent page. */
const val RETURN_TO_REQUIRED_PATH = "/federation/oidc/authorize"

/**
 * V1.9.89 -- the one allowlist behind the "log in, then continue at the assistant's consent page" round trip.
 *
 * [raw] is the value after exactly ONE decode (the `returnTo` hash parameter decoded once by the client, the
 * `returnTo` query parameter decoded once by Ktor, the cookie value decoded once by the `URI_ENCODING`). The
 * result is [raw] unchanged, or `null` -- fail-closed: any doubt means "go to the dashboard as before".
 *
 * Rules, in this order (a violation of any rule yields `null`):
 * 1. not null, length 1..[RETURN_TO_MAX_LENGTH];
 * 2. every character in `'!'..'~'` (printable ASCII without space): no whitespace, no control characters
 *    (CR/LF/TAB/NUL/DEL), no non-ASCII homoglyphs;
 * 3. neither a backslash nor a `#`;
 * 4. starts with `/federation/oidc/authorize?` -- byte-exact and case-sensitive. This alone excludes `//host`,
 *    `/\host`, `https:`, `javascript:` and `data:`;
 * 5. the path (before the first `?`) equals [RETURN_TO_REQUIRED_PATH] and has no `%`, `.` or `;` (defensive);
 * 6. every `%` in the query is followed by exactly two hex digits (`%zz`, `%2`, a trailing `%` are rejected);
 * 7. the query holds no `%25` followed by two hex digits (a double-encoded value, e.g. `%252F`): a value that was
 *    encoded twice would be decoded to something else by the next hop.
 *
 * Path and query are treated differently on purpose: a legitimate authorize URL carries `redirect_uri=http%3A%2F%2F...`
 * in its query, so single-encoded escapes are fine there, while the path must be a plain literal.
 *
 * Hand-written character loops, no regular expressions: the JVM and the JS engine must behave identically.
 */
fun safeReturnTo(raw: String?): String? {
    if (raw == null || raw.isEmpty() || raw.length > RETURN_TO_MAX_LENGTH) return null
    for (c in raw) {
        if (c < '!' || c > '~') return null
        if (c == '\\' || c == '#') return null
    }
    if (!raw.startsWith("$RETURN_TO_REQUIRED_PATH?")) return null
    val path = raw.substringBefore('?')
    if (path != RETURN_TO_REQUIRED_PATH) return null
    if (path.any { it == '%' || it == '.' || it == ';' }) return null
    val query = raw.substring(path.length + 1)
    var i = 0
    while (i < query.length) {
        if (query[i] == '%') {
            if (i + 2 >= query.length) return null
            val h1 = query[i + 1]
            val h2 = query[i + 2]
            if (!isHex(h1) || !isHex(h2)) return null
            if (h1 == '2' && h2 == '5') {
                // "%25" itself is a legitimate escape of '%'; what is forbidden is "%25" + two hex digits (double encoding).
                if (i + 4 < query.length && isHex(query[i + 3]) && isHex(query[i + 4])) return null
            }
            i += 3
        } else {
            i++
        }
    }
    return raw
}

private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
