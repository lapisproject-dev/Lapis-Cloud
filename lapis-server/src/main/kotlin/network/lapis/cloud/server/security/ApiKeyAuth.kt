package network.lapis.cloud.server.security

import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

/**
 * Principal resolved from a valid API key ([ApiKeyStore.Resolution.Valid]) -- the API-key
 * equivalent of [CurrentMember], but deliberately its OWN, smaller type: an API key authenticates
 * a machine-to-machine caller reading `/api/v1`, never a logged-in human with a [network.lapis.cloud.shared.domain.MemberStatus]/
 * [network.lapis.cloud.shared.domain.AccountRole] to authorize against (every `/api/v1` endpoint
 * this wave adds is read-only and uniformly reachable by ANY valid, non-revoked, non-expired key --
 * see `PublicApiRoutes` KDoc).
 */
data class ApiKeyPrincipal(
    val apiKeyId: Uuid,
    val label: String,
    val keyPrefix: String,
)

/**
 * `Authorization: Bearer <token>` header extraction for the API-key path -- structurally the
 * mirror image of [RequestContext.extractSessionToken], and the other half of the four guarantees
 * documented there: returns the raw token ONLY when it starts with [ApiKeyStore.API_KEY_TOKEN_PREFIX],
 * `null` for every other shape (missing header, wrong schema, a session-shaped token) -- so an API
 * key can never be handed to [SessionStore.resolve] and a session token can never be handed to
 * [ApiKeyStore.resolve] BY THIS EXTRACTION LAYER, regardless of what either store would otherwise
 * do with a mismatched hash.
 */
internal fun extractApiKeyToken(call: ApplicationCall): String? {
    val authHeader = call.request.headers["Authorization"] ?: return null
    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return null
    val token = authHeader.substring(7).trim()
    return token.takeIf { it.startsWith(ApiKeyStore.API_KEY_TOKEN_PREFIX) }
}

/**
 * Resolves [call]'s `Authorization` header as an API key -- **ausschliesslich von
 * `PublicApiRoutes`/`PublicApiSupport` aufgerufen**, [resolveCurrentMember] runs there NIE. Missing
 * header / wrong schema / non-`lapis_`-prefixed token all collapse into [ApiKeyStore.Resolution.Unknown]
 * (Design-Team decision #4, mirrored from [ApiKeyStore.resolve]'s own KDoc) -- there is no
 * meaningful difference, from an unauthenticated caller's point of view, between "you sent nothing"
 * and "you sent something that cannot possibly be a valid key".
 */
fun resolveApiKey(call: ApplicationCall): ApiKeyStore.Resolution {
    val rawToken = extractApiKeyToken(call) ?: return ApiKeyStore.Resolution.Unknown
    return ApiKeyStore.resolve(rawToken)
}
