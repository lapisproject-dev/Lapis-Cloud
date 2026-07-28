package network.lapis.cloud.server.federation

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** One publishable Trust-Anchor signing key -- see [TrustAnchorSigningKeyStore.listPublishable]. */
data class TrustAnchorPublishableKey(
    val kid: String,
    val publicKeyPem: String,
)

/**
 * JWK Set (RFC 7517) building for this server's own Trust-Anchor Entity Configuration `jwks` claim
 * -- UNLIKE [OidcJwks] (exactly one active OIDC signing key), this can publish MULTIPLE keys at once
 * (`ACTIVE` + every still-`RETIRED` key, see `26-trust-anchor.kuml.kts` file header). Verification
 * of a fetched REMOTE anchor's `jwks` claim reuses [OidcJwks.findRsaPublicKeyPem] directly (already
 * generic over an arbitrary JWKS JSON string) -- no separate parsing code needed here.
 */
object TrustAnchorJwks {
    /** Builds a JWK Set JSON object (as a plain `Map<String, Any>`, suitable for a JWT claim value) from [keys] -- never includes any private key. */
    fun buildJwksClaim(keys: List<TrustAnchorPublishableKey>): Map<String, Any> {
        val rsaKeys =
            keys.map { key ->
                RSAKey
                    .Builder(decodePublicKeyPem(key.publicKeyPem))
                    .keyID(key.kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build()
            }
        // JWKSet.toJSONObject() already returns a plain Map<String, Object> -- no separate
        // JSONObjectUtils.toJSONString round-trip needed here, unlike OidcJwks.buildJwksJson (which
        // must produce wire-format JSON *text* for an HTTP response body, not a claim value).
        return JWKSet(rsaKeys).toJSONObject(true)
    }

    /** Renders [buildJwksClaim]'s result as compact JSON text -- used by the well-known route's own direct-fetch tests and any caller needing the wire-format string rather than a claim value. */
    fun buildJwksJson(keys: List<TrustAnchorPublishableKey>): String = JSONObjectUtils.toJSONString(buildJwksClaim(keys))

    private fun decodePublicKeyPem(pem: String): RSAPublicKey {
        val base64 = pem.lineSequence().filterNot { it.isBlank() || it.startsWith("-----") }.joinToString("")
        val der = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
    }
}
