package network.lapis.cloud.server.embed

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Result of [applyEmbedCors] -- deliberately a sealed type, not a nullable `String?`, so every call
 * site is FORCED (via `when`) to handle all three cases distinctly rather than conflating "no
 * `Origin` header" with "disallowed `Origin`" behind a single `null`. Only the second case
 * ([Rejected]) means the caller must respond 403 (see [respondEmbedForbiddenOrigin]) -- the other
 * two both mean "continue with normal processing" (see class KDoc rule 4/2).
 */
internal sealed interface EmbedCorsResult {
    /** No `Origin` header at all -- a same-origin call (e.g. the popup page's own `fetch`, or `curl`). Normal 200 processing continues, with NO CORS header on the response. */
    data object NoOriginHeader : EmbedCorsResult

    /** `Origin` header present and allowed -- [canonicalOrigin] is the STORED allowlist entry (never the raw request value), already written as `Access-Control-Allow-Origin`. */
    data class Allowed(
        val canonicalOrigin: String,
    ) : EmbedCorsResult

    /** `Origin` header present but not on the allowlist -- caller MUST respond via [respondEmbedForbiddenOrigin] and not proceed with normal handling. */
    data object Rejected : EmbedCorsResult
}

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- hand-rolled, PER-HANDLER CORS. No Ktor `CORS`
 * plugin, no route-prefix interceptor: every handler that should carry CORS headers calls
 * [applyEmbedCors]/[respondEmbedPreflight] EXPLICITLY. This is both this codebase's house style
 * ("headers are always set by hand, never through a blanket plugin" -- same reasoning as
 * `network.lapis.cloud.server.routes.applyPublicPageHeaders`) and a deliberate hardening: a future
 * endpoint under `/api/embed/v1/` (the ADMIN status endpoint, in particular) must NEVER inherit
 * CORS headers just by living under the same path prefix -- with no prefix-level interceptor, that
 * is structurally impossible; each handler opts in individually.
 *
 * **The CORS credentials-allow response header ([io.ktor.http.HttpHeaders.AccessControlAllowCredentials])
 * is never SET anywhere in this file, or anywhere else in the `embed` package** -- checked by a
 * source-text scan test (`EmbedAssetTest`) for a call site setting the header, recognizing it EITHER
 * by the literal wire-format header name OR by Ktor's typed constant (Review-Fund V1.4.1a: a scan
 * for only the literal string could not have caught a call setting it through the typed constant
 * instead, the form every other `header(...)` call site in this welle actually uses -- a scan for a
 * bare mention of the identifier, on the other hand, would wrongly flag this very KDoc sentence, so
 * the test matches the SETTING call shape specifically, not just the name). This is the single
 * strongest guarantee of the whole welle: even if the allowlist were ever misconfigured, the browser
 * would still never attach the session cookie to a cross-origin request without this header (and
 * `SameSite=Strict`, unmodified by this welle, would refuse it a second time anyway).
 *
 * `Vary: Origin` and `Cache-Control: no-store` are set UNCONDITIONALLY, before the allow/reject
 * decision -- both must be present on every response this function's caller ever produces,
 * including the eventual 403 (rule 5 in the class-level plan), so setting them once here, before
 * branching, is both simpler and impossible to forget on one branch but not the other.
 */
internal fun ApplicationCall.applyEmbedCors(
    allowlist: EmbedOriginAllowlist,
    allowInsecure: Boolean,
): EmbedCorsResult {
    // allowInsecure is currently unused here -- EmbedOriginAllowlist.canonicalize already embeds
    // the correct accepted-scheme decision from parse() time (see that function's own KDoc). Kept
    // as a parameter so a future caller-side scheme check can be added here without changing every
    // call site's signature, and so this function's signature visibly documents that scheme
    // handling is a first-class concern of this file, not an afterthought.
    response.header(HttpHeaders.Vary, "Origin")
    response.header(HttpHeaders.CacheControl, "no-store")
    val rawOrigin = request.headers[HttpHeaders.Origin] ?: return EmbedCorsResult.NoOriginHeader
    val allowedOrigin = allowlist.canonicalize(rawOrigin) ?: return EmbedCorsResult.Rejected
    response.header(HttpHeaders.AccessControlAllowOrigin, allowedOrigin)
    return EmbedCorsResult.Allowed(allowedOrigin)
}

/**
 * Responds `403` (no body) for a present-but-disallowed `Origin`. Deliberately generic: never
 * echoes the seen origin or the allowlist contents (no oracle for a scanning attacker).
 * `Vary: Origin`/`Cache-Control: no-store` are NOT set again here -- [applyEmbedCors] already set
 * both, unconditionally, before ever returning [EmbedCorsResult.Rejected] (see that function's own
 * KDoc); every call site invokes this function only after [applyEmbedCors], never standalone, so
 * re-setting them here would just append a duplicate header value.
 */
internal suspend fun ApplicationCall.respondEmbedForbiddenOrigin() {
    respond(HttpStatusCode.Forbidden)
}

/**
 * `204` CORS preflight response for [allowedMethods] (e.g. `"GET, OPTIONS"`). Caller has already
 * applied [applyEmbedCors] (or rejected via [respondEmbedForbiddenOrigin]) before calling this.
 * `Cache-Control: no-store` is NOT set again here -- [applyEmbedCors] already set it, unconditionally,
 * before this function's caller ever gets a chance to call it (same reasoning as
 * [respondEmbedForbiddenOrigin]'s own KDoc); `response.header()` is `headers.append()`, not a
 * replace, so re-setting it here would append a second, duplicate `Cache-Control` header on every
 * preflight response (Review-Fund V1.4.1a -- this exact double-append pattern was already fixed
 * once in [respondEmbedForbiddenOrigin], this was the one call site that still had it).
 */
internal suspend fun ApplicationCall.respondEmbedPreflight(allowedMethods: String) {
    response.header(HttpHeaders.AccessControlAllowMethods, allowedMethods)
    response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
    response.header(HttpHeaders.AccessControlMaxAge, "600")
    respond(HttpStatusCode.NoContent)
}
