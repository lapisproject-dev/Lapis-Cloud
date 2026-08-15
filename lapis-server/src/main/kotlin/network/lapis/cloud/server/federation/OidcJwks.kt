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

/**
 * JWK Set (RFC 7517) building (this server's own `GET /federation/oidc/jwks`, Issuer side) and
 * parsing (a fetched home server's JWKS, RP side) -- both backed by Nimbus's typed `RSAKey`/
 * `JWKSet` (see [OidcJwt] KDoc for why this wave uses Nimbus rather than hand-rolling JOSE).
 */
object OidcJwks {
    /** Builds this server's own JWK Set JSON (one RSA public-signing key) from [publicKeyPem]/[kid] -- never includes the private key. */
    fun buildJwksJson(
        publicKeyPem: String,
        kid: String,
    ): String {
        val publicKey = decodePublicKeyPem(publicKeyPem)
        val rsaKey =
            RSAKey
                .Builder(publicKey)
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build()
        // JWKSet.toJSONObject() returns a plain java.util.Map<String, Object> -- its default
        // Object.toString() is NOT valid JSON ("{k=v}" Java-map format, not "{"k":"v"}"). Must go
        // through JSONObjectUtils.toJSONString explicitly to get real, spec-shaped JSON text.
        return JSONObjectUtils.toJSONString(JWKSet(listOf(rsaKey)).toJSONObject(true))
    }

    /** Looks up [kid] in a fetched [jwksJson] (a home server's `jwks_uri` response) -- returns the matching key's PEM-encoded RSA public key, or `null` if the JSON is unparseable/the JWKS has no matching `kid`/it is not an RSA signature key. */
    fun findRsaPublicKeyPem(
        jwksJson: String,
        kid: String,
    ): String? {
        val jwkSet = runCatching { JWKSet.parse(jwksJson) }.getOrNull() ?: return null
        val jwk = jwkSet.getKeyByKeyId(kid) as? RSAKey ?: return null
        val publicKey = runCatching { jwk.toRSAPublicKey() }.getOrNull() ?: return null
        return pemEncode(derBytes = publicKey.encoded, label = "PUBLIC KEY")
    }

    private fun decodePublicKeyPem(pem: String): RSAPublicKey {
        val base64 = pem.lineSequence().filterNot { it.isBlank() || it.startsWith("-----") }.joinToString("")
        val der = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
    }

    private fun pemEncode(
        derBytes: ByteArray,
        label: String,
    ): String {
        val base64 = Base64.getEncoder().encodeToString(derBytes)
        val wrapped = base64.chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$wrapped\n-----END $label-----\n"
    }
}
