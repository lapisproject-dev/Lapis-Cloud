package network.lapis.cloud.server.federation

import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlin.time.Clock
import kotlin.time.Instant

/** A verified, self-signed Entity Configuration -- the anchor's own currently-published key set (JSON text, ready for [OidcJwks.findRsaPublicKeyPem]) and where to fetch a Subordinate Statement about one of its pool members. */
internal data class VerifiedEntityConfiguration(
    val jwksJson: String,
    val fetchEndpoint: String,
)

/**
 * The pure, network-free cryptographic core of [TrustAnchorResolver]'s one-hop trust-chain
 * resolution -- split out specifically so it can be exercised directly with real, locally-generated
 * RSA keypairs and hand-crafted (including deliberately tampered) JWTs, same "real crypto, no
 * mocks, no network" testing posture [OidcJwtTest] already establishes for [OidcJwt]. See
 * [TrustAnchorResolver] KDoc for the full self-signed-bootstrap reasoning this implements.
 */
internal object TrustAnchorChainVerification {
    /**
     * Verifies a fetched Entity Configuration [compact] JWT self-asserts [expectedAnchorEntityUri]
     * (`iss == sub == ` that URI), is temporally valid, and is correctly signed by a key drawn from
     * its OWN embedded `jwks` claim. Returns the anchor's currently-published `jwks` (as JSON text)
     * and its advertised fetch endpoint on success, `null` on ANY failure.
     */
    fun verifyEntityConfiguration(
        compact: String,
        expectedAnchorEntityUri: String,
        now: Instant = Clock.System.now(),
    ): VerifiedEntityConfiguration? {
        val claims = parseUnverifiedClaims(compact) ?: return null
        if (claims.issuer != expectedAnchorEntityUri || claims.subject != expectedAnchorEntityUri) return null
        if (!isTemporallyValid(claims, now)) return null

        val jwksJson = extractJwksJson(claims) ?: return null
        val kid = OidcJwt.extractUnverifiedKid(compact) ?: return null
        val publicKeyPem = OidcJwks.findRsaPublicKeyPem(jwksJson, kid) ?: return null
        val verification = OidcJwt.verifySignature(compact, publicKeyPem)
        if (verification is OidcJwt.VerificationResult.Invalid) return null

        @Suppress("UNCHECKED_CAST")
        val metadata = runCatching { claims.getJSONObjectClaim("metadata") as? Map<String, Any> }.getOrNull() ?: return null

        @Suppress("UNCHECKED_CAST")
        val federationEntity = metadata["federation_entity"] as? Map<String, Any> ?: return null
        val fetchEndpoint = federationEntity["federation_fetch_endpoint"] as? String
        if (fetchEndpoint.isNullOrBlank()) return null

        return VerifiedEntityConfiguration(jwksJson = jwksJson, fetchEndpoint = fetchEndpoint)
    }

    /**
     * Verifies a fetched Subordinate Statement [compact] JWT was issued BY [expectedAnchorEntityUri]
     * ABOUT [expectedHomeServerUri] (`iss`/`sub` respectively), is temporally valid, and is correctly
     * signed by a key drawn from [jwksJson] -- the SAME key set already verified against the
     * anchor's own Entity Configuration by [verifyEntityConfiguration], never a fresh fetch and
     * never anything the statement itself could supply (a leaf Subordinate Statement in this
     * single-level scope carries no `jwks` of its own, see `26-trust-anchor.kuml.kts` file header).
     * `true` only if every check passes.
     */
    fun verifySubordinateStatement(
        compact: String,
        expectedAnchorEntityUri: String,
        expectedHomeServerUri: String,
        jwksJson: String,
        now: Instant = Clock.System.now(),
    ): Boolean {
        val claims = parseUnverifiedClaims(compact) ?: return false
        if (claims.issuer != expectedAnchorEntityUri) return false
        if (claims.subject != expectedHomeServerUri) return false
        if (!isTemporallyValid(claims, now)) return false

        val kid = OidcJwt.extractUnverifiedKid(compact) ?: return false
        val publicKeyPem = OidcJwks.findRsaPublicKeyPem(jwksJson, kid) ?: return false
        val verification = OidcJwt.verifySignature(compact, publicKeyPem)
        return verification !is OidcJwt.VerificationResult.Invalid
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractJwksJson(claims: JWTClaimsSet): String? {
        val jwksMap = runCatching { claims.getJSONObjectClaim("jwks") as? Map<String, Any> }.getOrNull() ?: return null
        return JSONObjectUtils.toJSONString(jwksMap)
    }

    private fun parseUnverifiedClaims(compact: String): JWTClaimsSet? = runCatching { SignedJWT.parse(compact).jwtClaimsSet }.getOrNull()

    private fun isTemporallyValid(
        claims: JWTClaimsSet,
        now: Instant,
    ): Boolean {
        val exp = runCatching { claims.expirationTime }.getOrNull() ?: return false
        val iat = runCatching { claims.issueTime }.getOrNull() ?: return false
        val nowJava = OidcJwt.toJavaDate(now)
        val skewMillis = OidcJwt.CLOCK_SKEW.inWholeMilliseconds
        if (nowJava.time > exp.time + skewMillis) return false
        if (iat.time > nowJava.time + skewMillis) return false
        return true
    }
}
