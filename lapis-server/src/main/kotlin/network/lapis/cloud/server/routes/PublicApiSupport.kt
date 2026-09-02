package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyPrincipal
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.security.resolveApiKey

private val logger = KotlinLogging.logger {}

/** Default/max page size for every `/api/v1` list endpoint -- see [parsePublicApiPageParams] KDoc. */
private const val PUBLIC_API_DEFAULT_LIMIT = 25
private const val PUBLIC_API_MAX_LIMIT = 100

internal data class PublicApiPageParams(
    val limit: Int,
    val offset: Int,
)

/**
 * Parses `?limit=`/`?offset=` -- NEVER throws / rejects with `400 bad_request` for a paging value,
 * only CLAMPS (Design-Team-adjacent decision, see the plan §7 "Kein bad_request für Paginierung
 * -- bewusst geklemmt, nicht abgelehnt"): `limit` unparsable/`<= 0` falls back to
 * [PUBLIC_API_DEFAULT_LIMIT]; `> `[PUBLIC_API_MAX_LIMIT] is clamped down to it. `offset`
 * unparsable/negative falls back to `0`. A malformed `{id}` PATH segment (`/api/v1/meetings/{id}`)
 * is a DIFFERENT matter and IS `400 bad_request` -- see [PublicApiRoutes]'s own handler.
 */
internal fun ApplicationCall.parsePublicApiPageParams(): PublicApiPageParams {
    val rawLimit = request.queryParameters["limit"]?.toIntOrNull()
    val limit = (if (rawLimit == null || rawLimit <= 0) PUBLIC_API_DEFAULT_LIMIT else rawLimit).coerceAtMost(PUBLIC_API_MAX_LIMIT)
    val rawOffset = request.queryParameters["offset"]?.toIntOrNull()
    val offset = (rawOffset ?: 0).coerceAtLeast(0)
    return PublicApiPageParams(limit = limit, offset = offset)
}

/**
 * `Cache-Control: no-store` + `Vary: Authorization` -- set on EVERY `/api/v1` response, success
 * or error alike (Design-Team decision #4: even a 429 from the pre-auth rate limiter, which runs
 * before [requirePublicApiPrincipal] ever gets a chance to set these itself, must never be
 * cached/conflated across different callers by an intermediary that keys on URL alone). Called
 * first thing in [network.lapis.cloud.server.routes.publicApiHandler], before anything else.
 */
internal fun ApplicationCall.applyPublicApiHeaders() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
}

/**
 * Pre-authentication, IP-keyed rate limit -- checked BEFORE any DB work, including before the
 * API-key hash lookup itself (same "cheap rejection before expensive work" posture
 * `SocialPublicRoutes`' own pre-auth guards establish). `false` means a `429` has already been
 * written to [this] -- the caller must `return@get` immediately.
 */
internal suspend fun ApplicationCall.checkPublicApiPreAuthRateLimit(limiter: FederationInboxRateLimiter): Boolean {
    val key = rateLimitKeyFor(remoteHost = request.origin.remoteHost)
    if (limiter.checkAndRecord(key)) return true
    respondPublicApiRateLimited(limiter = limiter, key = key)
    return false
}

/**
 * Resolves the caller's API key and enforces the post-auth, key-keyed rate limit -- returns the
 * [ApiKeyPrincipal] on success, or `null` after having already written the appropriate error
 * response (401/429). **Must be called from `PublicApiRoutes` only** -- `resolveCurrentMember`
 * (session-based) never runs on this surface, see [network.lapis.cloud.server.security.ApiKeyAuth]
 * KDoc "four guarantees".
 *
 * [ApiKeyStore.touchLastUsed] is fired for a [ApiKeyStore.Resolution.Valid] result only, AFTER the
 * rate-limit check passes -- a request that gets rate-limited was never actually "used" in the
 * sense that bookkeeping is for.
 */
internal suspend fun ApplicationCall.requirePublicApiPrincipal(postAuthRateLimiter: FederationInboxRateLimiter): ApiKeyPrincipal? {
    return when (val resolution = resolveApiKey(this)) {
        is ApiKeyStore.Resolution.Unknown -> {
            respondPublicApiError(status = HttpStatusCode.Unauthorized, code = "unauthorized", message = "Missing or invalid API key.")
            null
        }
        is ApiKeyStore.Resolution.Revoked -> {
            logger.warn { "Public API request with a revoked key (prefix=${resolution.keyPrefix})" }
            respondPublicApiError(status = HttpStatusCode.Unauthorized, code = "key_revoked", message = "This API key has been revoked.")
            null
        }
        is ApiKeyStore.Resolution.Expired -> {
            logger.warn { "Public API request with an expired key (prefix=${resolution.keyPrefix})" }
            respondPublicApiError(status = HttpStatusCode.Unauthorized, code = "key_expired", message = "This API key has expired.")
            null
        }
        is ApiKeyStore.Resolution.Valid -> {
            val key = "apikey:${resolution.principal.apiKeyId}"
            if (!postAuthRateLimiter.checkAndRecord(key)) {
                respondPublicApiRateLimited(limiter = postAuthRateLimiter, key = key)
                return null
            }
            ApiKeyStore.touchLastUsed(resolution.principal.apiKeyId)
            resolution.principal
        }
    }
}

/**
 * `bad_request` for a malformed `{id}` path segment or an out-of-whitelist `?status=` value --
 * `internal` (not `private`) so [PublicApiRoutes] can call it directly for those two cases; every
 * OTHER error path in this file goes through [requirePublicApiPrincipal]/[respondPublicApiRateLimited].
 */
internal suspend fun ApplicationCall.respondPublicApiBadRequest(message: String) {
    respondPublicApiError(status = HttpStatusCode.BadRequest, code = "bad_request", message = message)
}

internal suspend fun ApplicationCall.respondPublicApiNotFound(message: String) {
    respondPublicApiError(status = HttpStatusCode.NotFound, code = "not_found", message = message)
}

private suspend fun ApplicationCall.respondPublicApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    if (status == HttpStatusCode.Unauthorized) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"lapis-api\", error=\"invalid_token\"")
    }
    respondText(
        text = Json.encodeToString(PublicApiErrorDto.serializer(), PublicApiErrorDto(error = code, message = message)),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private suspend fun ApplicationCall.respondPublicApiRateLimited(
    limiter: FederationInboxRateLimiter,
    key: String,
) {
    // S19: called AFTER the checkAndRecord that already returned false -- retryAfterSeconds reports
    // the window that request was just counted against, not the one before it.
    response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(key).toString())
    respondPublicApiError(status = HttpStatusCode.TooManyRequests, code = "rate_limited", message = "Too many requests -- try again later.")
}
