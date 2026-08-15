package network.lapis.cloud.server.federation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

// Private AND distinctly `JWT_`-prefixed (not just `REASON_*` like HttpSignatures' own public
// top-level constants) -- Kotlin's top-level name resolution treats a private declaration in one
// file as ambiguous with a public declaration of the SAME bare name from another file in the SAME
// package (both are in scope inside this file), so `private` alone does not avoid the collision --
// only a distinct name does. Genuinely a different reason-code namespace anyway: HTTP Signatures
// vs. JOSE/JWT.
private const val JWT_REASON_MALFORMED_JWT = "MALFORMED"
private const val JWT_REASON_UNSUPPORTED_ALG = "UNSUPPORTED_ALG"
private const val JWT_REASON_SIGNATURE_MISMATCH = "SIGNATURE_MISMATCH"
private const val JWT_REASON_EXPIRED = "EXPIRED"
private const val JWT_REASON_NOT_YET_VALID = "NOT_YET_VALID"
private const val JWT_REASON_ISS_MISMATCH = "ISS_MISMATCH"
private const val JWT_REASON_AUD_MISMATCH = "AUD_MISMATCH"
private const val JWT_REASON_NONCE_MISMATCH = "NONCE_MISMATCH"
private const val JWT_REASON_MISSING_CLAIM = "MISSING_CLAIM"
private const val JWT_REASON_UNEXPECTED_NONCE = "UNEXPECTED_NONCE"
private const val JWT_REASON_MISSING_LOGOUT_EVENT_MARKER = "MISSING_LOGOUT_EVENT_MARKER"

/** [OIDC Back-Channel Logout 1.0](https://openid.net/specs/openid-connect-backchannel-1_0.html) §2.4 structural marker distinguishing a Logout Token from a regular ID Token. */
private const val BACKCHANNEL_LOGOUT_EVENT_URI = "http://schemas.openid.net/event/backchannel-logout"

/**
 * V0.8.2 OIDC-Gastzugang-Federation -- JOSE/JWT sign+verify for ID Tokens and Logout Tokens,
 * backed by `com.nimbusds:nimbus-jose-jwt` (see `gradle/libs.versions.toml` for why a library, not
 * hand-rolled -- a deliberate departure from [HttpSignatures]' hand-rolled posture).
 *
 * **Why a library, not hand-rolled**: [HttpSignatures] (draft-cavage) is a narrow, fixed,
 * single-algorithm scheme -- one JCA call, no algorithm negotiation exposed to the wire format at
 * all. JOSE/JWT is the opposite shape: the `alg` HEADER is attacker-controlled and is a full IANA
 * registry (`RS256`, `HS256`, `ES256`, `none`, ...) -- the wire format's entire multi-year CVE
 * history (`alg:none` bypass, RS256->HS256 confusion using the RSA public key as an HMAC secret,
 * `jku`/`jwk`/`x5c` header injection) is caused by implementations dynamically dispatching a
 * verifier based on that attacker-controlled header. Nimbus is Apache-2.0, pure JVM, zero
 * transitive deps, and is the de-facto-standard JVM JOSE library (Spring Security OAuth2, Ktor's
 * own `ktor-server-auth-jwt`, Keycloak) -- exactly the alternative the V0.8.2 plan floats.
 *
 * **Algorithm allowlist is enforced at the CODE level, not delegated to the library's header
 * dispatch**: [verifySignature] reads the token's raw `alg` header value via a plain,
 * pre-parse JSON lookup and requires it to equal the literal string `"RS256"` BEFORE ever touching
 * [SignedJWT.parse]/[RSASSAVerifier] -- an `alg: none` token, or one claiming `HS256`/any other
 * value, is rejected at this first gate, never reaching a verifier at all. A single, fixed
 * [RSASSAVerifier] instance (constructed from the known RSA public key) is the ONLY verifier this
 * object ever constructs -- there is no algorithm-keyed verifier-factory lookup anywhere in this
 * file for [verifySignature] (or any function built on it) to be confused by. This is
 * belt-and-suspenders on top of Nimbus's own internal safety (`RSASSAVerifier` only ever attempts
 * RSA-family verification regardless of what a token's header claims) -- defense in depth against
 * a future maintenance change accidentally introducing header-driven verifier selection.
 *
 * **Never throws**: every parse/verify failure maps to a typed [VerificationResult.Invalid]
 * reason, same "safe to call on entirely attacker-controlled input without a `try`/`catch` at the
 * call site" contract [HttpSignatures.verify] already establishes.
 */
object OidcJwt {
    private val ALLOWED_ALGORITHM = JWSAlgorithm.RS256

    /** Freshness/clock-skew allowance for `exp`/`iat`/`nbf` checks -- mirrors [HttpSignatures.FRESHNESS_WINDOW]'s own small allowance. */
    val CLOCK_SKEW: Duration = 2.minutes

    sealed interface VerificationResult {
        data class Valid(
            val claims: JWTClaimsSet,
        ) : VerificationResult

        data class Invalid(
            val reason: String,
        ) : VerificationResult
    }

    /** Signs [claimsSet] as a compact RS256 JWS, `kid`-tagged so the verifying party can select the right JWKS entry. */
    fun sign(
        claimsSet: JWTClaimsSet,
        kid: String,
        privateKeyPem: String,
    ): String {
        val header =
            JWSHeader
                .Builder(ALLOWED_ALGORITHM)
                .keyID(kid)
                .type(JOSEObjectType.JWT)
                .build()
        val signedJwt = SignedJWT(header, claimsSet)
        signedJwt.sign(RSASSASigner(decodePrivateKeyPem(privateKeyPem)))
        return signedJwt.serialize()
    }

    /**
     * Verifies [compact]'s signature against [publicKeyPem] ONLY -- no claim validation (issuer/
     * audience/expiry/nonce are the caller's responsibility, see [verifyIdToken]/[verifyLogoutToken]
     * for the full OIDC-shaped checks). See class KDoc "Algorithm allowlist" for the alg-confusion
     * defense this function is the single choke point for.
     */
    fun verifySignature(
        compact: String,
        publicKeyPem: String,
    ): VerificationResult {
        val parts = compact.split(".")
        if (parts.size != 3) return VerificationResult.Invalid(JWT_REASON_MALFORMED_JWT)
        // An "unsecured JWT" (alg=none) is wire-encoded as header.payload. with an EMPTY third
        // segment -- reject on structure alone before even looking at the alg header, since an
        // empty segment can never be a valid RSA signature regardless of what alg claims.
        if (parts[2].isBlank()) return VerificationResult.Invalid(JWT_REASON_UNSUPPORTED_ALG)

        val headerJson =
            runCatching { String(Base64URL(parts[0]).decode(), Charsets.UTF_8) }.getOrNull()
                ?: return VerificationResult.Invalid(JWT_REASON_MALFORMED_JWT)
        val algClaim =
            runCatching { JSONObjectUtils.parse(headerJson)["alg"] as? String }.getOrNull()
                ?: return VerificationResult.Invalid(JWT_REASON_MALFORMED_JWT)
        // The ONE gate: alg must be the literal string "RS256" -- checked BEFORE any JWSVerifier is
        // constructed. Any other value (including "none", "HS256", "HS384", mixed case, ...) is
        // rejected here, never reaching RSASSAVerifier.
        if (algClaim != ALLOWED_ALGORITHM.name) return VerificationResult.Invalid(JWT_REASON_UNSUPPORTED_ALG)

        val signedJwt = runCatching { SignedJWT.parse(compact) }.getOrNull() ?: return VerificationResult.Invalid(JWT_REASON_MALFORMED_JWT)
        // Defense in depth: the parsed header's own algorithm object must also agree.
        if (signedJwt.header.algorithm != ALLOWED_ALGORITHM) return VerificationResult.Invalid(JWT_REASON_UNSUPPORTED_ALG)

        val publicKey =
            runCatching { decodePublicKeyPem(publicKeyPem) }.getOrNull() ?: return VerificationResult.Invalid(JWT_REASON_MALFORMED_JWT)
        val verifier = RSASSAVerifier(publicKey)
        val valid = runCatching { signedJwt.verify(verifier) }.getOrDefault(false)
        return if (valid) VerificationResult.Valid(signedJwt.jwtClaimsSet) else VerificationResult.Invalid(JWT_REASON_SIGNATURE_MISMATCH)
    }

    /**
     * Full OIDC ID Token verification: [verifySignature] + `exp`/`iat` (bounded by [CLOCK_SKEW]) +
     * `iss` (must equal [expectedIssuer], the discovery document's OWN origin actually fetched --
     * not merely "some known issuer") + `aud` (must CONTAIN [expectedAudience], this RP's own
     * `client_id` for that specific home server -- defeats an ID token minted for a DIFFERENT RP)
     * + `nonce` (must equal [expectedNonce], the value generated fresh for THIS login attempt --
     * defeats replay of a captured, still-cryptographically-valid ID token from an earlier flow).
     * Check order matches [HttpSignatures.verify]'s own "cheapest/most information-revealing
     * first" convention: malformed/alg -> signature -> temporal -> iss -> aud -> nonce.
     */
    fun verifyIdToken(
        compact: String,
        publicKeyPem: String,
        expectedIssuer: String,
        expectedAudience: String,
        expectedNonce: String,
        now: Instant = Clock.System.now(),
    ): VerificationResult {
        val signatureResult = verifySignature(compact = compact, publicKeyPem = publicKeyPem)
        if (signatureResult is VerificationResult.Invalid) return signatureResult
        val claims = (signatureResult as VerificationResult.Valid).claims

        val temporalFailure = checkTemporalClaims(claims = claims, now = now)
        if (temporalFailure != null) return VerificationResult.Invalid(temporalFailure)

        if (claims.issuer != expectedIssuer) return VerificationResult.Invalid(JWT_REASON_ISS_MISMATCH)
        if (expectedAudience !in (claims.audience ?: emptyList())) return VerificationResult.Invalid(JWT_REASON_AUD_MISMATCH)

        val nonceClaim = runCatching { claims.getStringClaim("nonce") }.getOrNull()
        if (nonceClaim == null) return VerificationResult.Invalid(JWT_REASON_MISSING_CLAIM)
        if (nonceClaim != expectedNonce) return VerificationResult.Invalid(JWT_REASON_NONCE_MISMATCH)

        return VerificationResult.Valid(claims)
    }

    /**
     * Full OIDC Back-Channel Logout Token verification (see
     * [OIDC Back-Channel Logout 1.0](https://openid.net/specs/openid-connect-backchannel-1_0.html)
     * §2.6): [verifySignature] + temporal + `iss`/`aud` (same as [verifyIdToken]) +
     * `events`-claim structural marker (distinguishes a Logout Token from a regular ID Token --
     * without it, a captured ID Token could be smuggled in as a Logout Token, silently revoking a
     * session the ID Token was never meant to authorize revoking) + `nonce` MUST be ABSENT
     * (spec requirement -- `nonce` is reserved for ID Tokens; its presence here is a smuggling
     * attempt, not a benign extra claim, so it is rejected rather than ignored).
     */
    fun verifyLogoutToken(
        compact: String,
        publicKeyPem: String,
        expectedIssuer: String,
        expectedAudience: String,
        now: Instant = Clock.System.now(),
    ): VerificationResult {
        val signatureResult = verifySignature(compact = compact, publicKeyPem = publicKeyPem)
        if (signatureResult is VerificationResult.Invalid) return signatureResult
        val claims = (signatureResult as VerificationResult.Valid).claims

        val temporalFailure = checkTemporalClaims(claims = claims, now = now)
        if (temporalFailure != null) return VerificationResult.Invalid(temporalFailure)

        if (claims.issuer != expectedIssuer) return VerificationResult.Invalid(JWT_REASON_ISS_MISMATCH)
        if (expectedAudience !in (claims.audience ?: emptyList())) return VerificationResult.Invalid(JWT_REASON_AUD_MISMATCH)

        val nonceClaim = runCatching { claims.getStringClaim("nonce") }.getOrNull()
        if (nonceClaim != null) return VerificationResult.Invalid(JWT_REASON_UNEXPECTED_NONCE)

        val events = runCatching { claims.getJSONObjectClaim("events") }.getOrNull()
        if (events == null || !events.containsKey(BACKCHANNEL_LOGOUT_EVENT_URI)) {
            return VerificationResult.Invalid(JWT_REASON_MISSING_LOGOUT_EVENT_MARKER)
        }

        return VerificationResult.Valid(claims)
    }

    /** Builds the Logout-Token-specific `events` claim value, see [verifyLogoutToken] KDoc. */
    fun logoutEventsClaim(): Map<String, Any> = mapOf(BACKCHANNEL_LOGOUT_EVENT_URI to emptyMap<String, Any>())

    private fun checkTemporalClaims(
        claims: JWTClaimsSet,
        now: Instant,
    ): String? {
        val exp = claims.expirationTime?.toInstant()?.toKotlinInstant() ?: return JWT_REASON_MISSING_CLAIM
        val iat = claims.issueTime?.toInstant()?.toKotlinInstant() ?: return JWT_REASON_MISSING_CLAIM
        if (now > exp + CLOCK_SKEW) return JWT_REASON_EXPIRED
        if (iat > now + CLOCK_SKEW) return JWT_REASON_NOT_YET_VALID
        return null
    }

    fun toJavaDate(instant: Instant): Date = Date.from(instant.toJavaInstant())

    /**
     * Reads the `kid` header value out of [compact] WITHOUT verifying anything -- used to select
     * which JWKS entry to attempt verification against before [verifySignature] can run (mirrors
     * [HttpSignatures.extractKeyId]'s own "look up the key first, verify second" shape). `null` on
     * any parse failure.
     */
    fun extractUnverifiedKid(compact: String): String? {
        val parts = compact.split(".")
        if (parts.size < 2) return null
        val headerJson = runCatching { String(Base64URL(parts[0]).decode(), Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { JSONObjectUtils.parse(headerJson)["kid"] as? String }.getOrNull()
    }

    /**
     * Reads the `iss` PAYLOAD claim out of [compact] WITHOUT verifying anything -- used by the
     * Back-Channel Logout receiver to look up the claimed home server's registration (and thus
     * which JWKS to fetch/verify against) BEFORE any signature check, deliberately never trusted
     * for authorization on its own (see `network.lapis.cloud.server.routes.OidcRoutes` KDoc
     * "reject before fetch"). `null` on any parse failure.
     */
    fun extractUnverifiedIssuer(compact: String): String? {
        val parts = compact.split(".")
        if (parts.size < 2) return null
        val payloadJson = runCatching { String(Base64URL(parts[1]).decode(), Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { JSONObjectUtils.parse(payloadJson)["iss"] as? String }.getOrNull()
    }

    private fun decodePrivateKeyPem(pem: String): RSAPrivateKey {
        val der = stripPem(pem)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
    }

    private fun decodePublicKeyPem(pem: String): RSAPublicKey {
        val der = stripPem(pem)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
    }

    private fun stripPem(pem: String): ByteArray {
        val base64 = pem.lineSequence().filterNot { it.isBlank() || it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(base64)
    }
}
