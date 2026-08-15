package network.lapis.cloud.server.federation

import com.nimbusds.jwt.JWTClaimsSet
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Both the Entity Configuration and every Subordinate Statement carry this TTL -- see `26-trust-anchor.kuml.kts` file header "generated fresh, on demand, at fetch time": there is no separate background reissue job this wave, a request IS the reissue, so this TTL only bounds how long an already-fetched/cached copy remains acceptable to a verifier that does not re-fetch on every use. */
internal val TRUST_ANCHOR_STATEMENT_TTL = 24.hours

/**
 * Builds this server's own OpenID Federation 1.0 Entity Configuration and per-pool-member
 * Subordinate Statement JWTs -- the PUBLISHING side of [network.lapis.cloud.server.routes.registerTrustAnchorRoutes].
 * Both are generated FRESH on every call (no persisted "issued statement" row, no background reissue
 * scheduler) -- signing is cheap, and generating on demand is strictly at least as fresh as any
 * periodic-reissue schedule could be, while being far simpler: removing a pool member takes effect
 * on the very next fetch, no revocation-list bookkeeping needed for that case (see
 * `26-trust-anchor.kuml.kts` file header "Why revocation needs more than expiry alone" for the ONE
 * case, a compromised SIGNING KEY, where fresh-generation-on-fetch alone is not enough and
 * JWKS-exclusion is required instead).
 *
 * Every function here must be called from within an open `transaction {}` block (reads
 * [TrustAnchorSigningKeyStore]/[TrustAnchorPoolStore]), same contract every other `TrustAnchor*Store`
 * in this package establishes. Signing itself reuses [OidcJwt.sign] verbatim -- no new JWT-signing
 * code, per this wave's task requirement to reuse the audited nimbus-based approach V0.8.2 already
 * established.
 */
object TrustAnchorStatements {
    /**
     * This server's own self-signed Entity Configuration -- `iss == sub == `[TrustAnchorConfig.entityUri],
     * `jwks` carries every currently-publishable key (see [TrustAnchorSigningKeyStore.listPublishable]),
     * `metadata.federation_entity.federation_fetch_endpoint` tells a verifier where to fetch a
     * Subordinate Statement for a specific pool member. `null` if, unexpectedly, no `ACTIVE` signing
     * key exists (should be unreachable outside a corrupted/pre-provisioning-boot state -- the route
     * layer treats this as `503 Service Unavailable`, same posture
     * [network.lapis.cloud.server.routes.registerFederationRoutes]'s own `GET /federation/actor`
     * "not yet provisioned" branch takes).
     */
    fun buildEntityConfiguration(organizationName: String): String? {
        val activeKey = TrustAnchorSigningKeyStore.findActive() ?: return null
        val publishableKeys =
            TrustAnchorSigningKeyStore.listPublishable().map { row ->
                TrustAnchorPublishableKey(
                    kid = row[TrustAnchorSigningKeyTable.kid],
                    publicKeyPem = row[TrustAnchorSigningKeyTable.publicKeyPem],
                )
            }
        val now = Clock.System.now()
        val entityUri = TrustAnchorConfig.entityUri
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(entityUri)
                .subject(entityUri)
                .issueTime(OidcJwt.toJavaDate(now))
                .expirationTime(OidcJwt.toJavaDate(now + TRUST_ANCHOR_STATEMENT_TTL))
                .claim("jwks", TrustAnchorJwks.buildJwksClaim(publishableKeys))
                .claim(
                    "metadata",
                    mapOf(
                        "federation_entity" to
                            mapOf(
                                "federation_fetch_endpoint" to TrustAnchorConfig.fetchEndpointUri,
                                "organization_name" to organizationName,
                            ),
                    ),
                ).build()
        return OidcJwt.sign(
            claimsSet = claims,
            kid = activeKey[TrustAnchorSigningKeyTable.kid],
            privateKeyPem = activeKey[TrustAnchorSigningKeyTable.privateKeyPem],
        )
    }

    /**
     * A signed Subordinate Statement for [homeServerUri] -- `iss` = this anchor's own entity URI,
     * `sub` = [homeServerUri]. `null` if [homeServerUri] is not (or no longer) in this server's own
     * pool (the route layer maps this to `404`, which is what actually makes "remove a pool member"
     * take effect -- see class KDoc), or if no `ACTIVE` signing key exists.
     */
    fun buildSubordinateStatement(homeServerUri: String): String? {
        TrustAnchorPoolStore.findByUri(homeServerUri) ?: return null
        val activeKey = TrustAnchorSigningKeyStore.findActive() ?: return null
        val now = Clock.System.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(TrustAnchorConfig.entityUri)
                .subject(homeServerUri)
                .issueTime(OidcJwt.toJavaDate(now))
                .expirationTime(OidcJwt.toJavaDate(now + TRUST_ANCHOR_STATEMENT_TTL))
                .build()
        return OidcJwt.sign(
            claimsSet = claims,
            kid = activeKey[TrustAnchorSigningKeyTable.kid],
            privateKeyPem = activeKey[TrustAnchorSigningKeyTable.privateKeyPem],
        )
    }
}
