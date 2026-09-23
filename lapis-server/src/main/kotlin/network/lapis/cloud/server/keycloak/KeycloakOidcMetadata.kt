package network.lapis.cloud.server.keycloak

import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.federation.OidcJwks
import java.time.Duration
import java.time.Instant

private val KEYCLOAK_JSON = Json { ignoreUnknownKeys = true }

/** How long an unknown `kid` is allowed to force at most one extra JWKS refetch -- see [KeycloakOidcMetadata.publicKeyPem] KDoc. */
internal val UNKNOWN_KID_REFETCH_COOLDOWN: Duration = Duration.ofSeconds(30)

/**
 * Lazy, TTL-cached OIDC discovery + JWKS metadata for the operator's pinned Keycloak issuer
 * ([KeycloakConfig.issuerUrl]) -- the Relying-Party counterpart to
 * `network.lapis.cloud.server.federation.OidcRoutes`'s own `fetchDiscoveryDocument`/JWKS-fetch
 * pair, but pointed permanently at ONE trusted, operator-configured issuer instead of an
 * arbitrary home server named in a login attempt.
 *
 * **Issuer-equality check is a real security control, not decoration.** [discoveryDocument] rejects
 * (returns `null`, never caches) a fetched document whose `issuer` field does not equal
 * [KeycloakConfig.issuerUrl] EXACTLY (string equality after both sides went through
 * [KeycloakIssuerUrlGuard]'s normalization) -- this is the same defence `OidcRoutes`'s own
 * `discovery.issuer != homeServer` check performs for the federation feature: without it, a
 * Keycloak instance reachable at the configured issuer URL but internally configured with a
 * DIFFERENT issuer identifier (e.g. behind a misconfigured reverse proxy, or a multi-realm
 * Keycloak where the wrong realm answered) could otherwise be silently accepted.
 *
 * **Unknown-`kid` refetch is rate-limited.** A JWT presenting a `kid` this class has never seen is
 * exactly what a legitimate key-rotation on the real Keycloak looks like (forces one refetch to
 * pick up the new key) -- but it is ALSO exactly what an attacker minting JWTs with random `kid`
 * values to drive load against the real Keycloak looks like. [publicKeyPem] therefore allows at
 * most one forced refetch per [UNKNOWN_KID_REFETCH_COOLDOWN] window, tracked globally (not
 * per-`kid`) so a burst of different random `kid`s cannot each buy their own refetch.
 *
 * All parsing failures (malformed JSON, an unparseable/non-RSA JWK) resolve to `null` -- this class
 * never throws an unhandled exception that could crash a request-handling coroutine mid-login.
 */
internal class KeycloakOidcMetadata(
    private val config: KeycloakConfig,
    private val httpClient: HttpClient = keycloakHttpClient(),
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private var discoveryCache: CachedDiscovery? = null
    private var jwksCache: CachedJwks? = null
    private var lastUnknownKidRefetchAt: Instant? = null

    private data class CachedDiscovery(
        val dto: KeycloakDiscoveryDto,
        val fetchedAt: Instant,
    )

    private data class CachedJwks(
        val json: String,
        val fetchedAt: Instant,
    )

    /** The cached (or freshly fetched, if the TTL expired) discovery document, or `null` if it cannot be obtained/verified. */
    suspend fun discoveryDocument(): KeycloakDiscoveryDto? = mutex.withLock { discoveryDocumentUnlocked() }

    /**
     * PEM-encoded RSA public key for [kid], or `null` if it cannot be resolved. On a `kid` not
     * present in the cached JWKS, forces at most one refetch per [UNKNOWN_KID_REFETCH_COOLDOWN] --
     * see class KDoc "Unknown-`kid` refetch is rate-limited".
     */
    suspend fun publicKeyPem(kid: String): String? =
        mutex.withLock {
            val issuer = config.issuerUrl ?: return@withLock null
            val discovery = discoveryDocumentUnlocked() ?: return@withLock null
            val cachedJwksJson = jwksJsonUnlocked(discovery = discovery, issuer = issuer) ?: return@withLock null

            val pem = runCatching { OidcJwks.findRsaPublicKeyPem(jwksJson = cachedJwksJson, kid = kid) }.getOrNull()
            if (pem != null) return@withLock pem

            val nowInstant = now()
            val last = lastUnknownKidRefetchAt
            if (last != null && Duration.between(last, nowInstant) < UNKNOWN_KID_REFETCH_COOLDOWN) {
                return@withLock null
            }
            lastUnknownKidRefetchAt = nowInstant

            val refreshed = fetchJwksRaw(jwksUri = discovery.jwks_uri, issuer = issuer) ?: return@withLock null
            jwksCache = CachedJwks(json = refreshed, fetchedAt = nowInstant)
            runCatching { OidcJwks.findRsaPublicKeyPem(jwksJson = refreshed, kid = kid) }.getOrNull()
        }

    private suspend fun discoveryDocumentUnlocked(): KeycloakDiscoveryDto? {
        val issuer = config.issuerUrl ?: return null
        val cached = discoveryCache
        if (cached != null && !isExpired(fetchedAt = cached.fetchedAt, ttlSeconds = config.discoveryCacheSeconds)) {
            return cached.dto
        }
        val fresh = fetchDiscoveryDocumentRaw(issuer) ?: return cached?.dto
        discoveryCache = CachedDiscovery(dto = fresh, fetchedAt = now())
        return fresh
    }

    private suspend fun jwksJsonUnlocked(
        discovery: KeycloakDiscoveryDto,
        issuer: String,
    ): String? {
        val cached = jwksCache
        if (cached != null && !isExpired(fetchedAt = cached.fetchedAt, ttlSeconds = config.jwksCacheSeconds)) {
            return cached.json
        }
        val fresh = fetchJwksRaw(jwksUri = discovery.jwks_uri, issuer = issuer) ?: return cached?.json
        jwksCache = CachedJwks(json = fresh, fetchedAt = now())
        return fresh
    }

    private fun isExpired(
        fetchedAt: Instant,
        ttlSeconds: Int,
    ): Boolean = Duration.between(fetchedAt, now()).seconds >= ttlSeconds

    /** Fetches and parses `{issuer}/.well-known/openid-configuration`, verifying `issuer` equality -- see class KDoc. Never throws. */
    private suspend fun fetchDiscoveryDocumentRaw(issuer: String): KeycloakDiscoveryDto? =
        runCatching {
            val url = "$issuer/.well-known/openid-configuration"
            val response = httpClient.getCapped(url = url, pinnedIssuerUrl = issuer, maxBytes = MAX_KEYCLOAK_RESPONSE_BYTES)
            if (response.status !in 200..299) return@runCatching null
            val bytes = response.body ?: return@runCatching null
            val dto =
                runCatching {
                    KEYCLOAK_JSON.decodeFromString(KeycloakDiscoveryDto.serializer(), bytes.toString(Charsets.UTF_8))
                }.getOrNull() ?: return@runCatching null
            if (dto.issuer != issuer) return@runCatching null
            dto
        }.getOrNull()

    /** Fetches the JWKS document's raw JSON text from [jwksUri] (re-validated against [issuer]). Never throws. */
    private suspend fun fetchJwksRaw(
        jwksUri: String,
        issuer: String,
    ): String? =
        runCatching {
            val response = httpClient.getCapped(url = jwksUri, pinnedIssuerUrl = issuer, maxBytes = MAX_KEYCLOAK_RESPONSE_BYTES)
            if (response.status !in 200..299) return@runCatching null
            response.body?.toString(Charsets.UTF_8)
        }.getOrNull()
}
