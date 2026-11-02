package network.lapis.cloud.server.routes

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.net.URI

/**
 * V1.9.259 -- the STRICT same-origin check for a cookie-authenticated, server-rendered form POST (the sign-in-aware button on the public
 * event page). [isSameOriginRequest] stays the lenient variant for its other callers: it accepts a request with no `Origin` at all, which
 * is fine for an upload route but not for an action a forged form could trigger with the visitor's cookie.
 *
 * - `Sec-Fetch-Site`, when present, must be `same-origin`.
 * - `Origin` present: must be a well-formed URI matching [canonicalOrigin] in scheme, host and effective port; the literal `null` fails.
 * - `Origin` absent: the `Referer` must parse to the same origin.
 * - Both absent: refused.
 */
internal fun isStrictSameOriginFormPost(
    headers: Headers,
    canonicalOrigin: URI?,
): Boolean {
    if (canonicalOrigin == null) return false
    val secFetchSite = headers["Sec-Fetch-Site"]
    if (secFetchSite != null && secFetchSite != "same-origin") return false
    val claimed = headers[HttpHeaders.Origin] ?: headers[HttpHeaders.Referrer] ?: return false
    if (claimed == "null") return false
    val parsed = runCatching { URI(claimed) }.getOrNull() ?: return false
    return parsed.scheme == canonicalOrigin.scheme &&
        parsed.host == canonicalOrigin.host &&
        effectivePort(parsed) == effectivePort(canonicalOrigin)
}
