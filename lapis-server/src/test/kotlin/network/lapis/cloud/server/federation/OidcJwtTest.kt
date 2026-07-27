package network.lapis.cloud.server.federation

import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * [OidcJwt] sign/verify -- the mandatory tamper/replay negative-test coverage for security-relevant
 * code (CLAUDE.md), specialized to the JOSE/JWT attack class [OidcJwt]'s class KDoc names:
 * `alg:none`, RS256->HS256 confusion, tampered payload/signature, expired/not-yet-valid,
 * `iss`/`aud`/`nonce` substitution or replay, and the Back-Channel Logout Token's own
 * spec-mandated structural checks. Every test uses a REAL RSA-2048 keypair (never a mock) so the
 * actual Nimbus/JCA path is exercised end to end, mirroring [HttpSignaturesTest]'s own posture.
 */
class OidcJwtTest :
    FunSpec({
        val kid = "test-kid-1"
        val keyPair = generateTestRsaKeyPair()
        val otherKeyPair = generateTestRsaKeyPair()

        fun validClaims(
            issuer: String = "https://home.example",
            audience: String = "rp-client-id",
            nonce: String? = "test-nonce-123",
            expiresInMinutes: Long = 60,
        ): JWTClaimsSet {
            val now = Clock.System.now()
            val builder =
                JWTClaimsSet
                    .Builder()
                    .issuer(issuer)
                    .subject("guest-subject-1")
                    .audience(audience)
                    .issueTime(OidcJwt.toJavaDate(now))
                    .expirationTime(OidcJwt.toJavaDate(now.plus(expiresInMinutes.minutes)))
            if (nonce != null) builder.claim("nonce", nonce)
            return builder.build()
        }

        // ── Happy path ──────────────────────────────────────────────────────

        test("a validly-signed RS256 ID token round-trips: sign -> verifyIdToken succeeds") {
            val token = OidcJwt.sign(validClaims(), kid, keyPair.privateKeyPem)
            val result =
                OidcJwt.verifyIdToken(
                    token,
                    keyPair.publicKeyPem,
                    expectedIssuer = "https://home.example",
                    expectedAudience = "rp-client-id",
                    expectedNonce = "test-nonce-123",
                )
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Valid>()
        }

        // ── alg confusion ───────────────────────────────────────────────────

        test("a token with alg=none is rejected, never reaching signature verification") {
            val header = base64Url("""{"alg":"none","typ":"JWT"}""")
            val payload = base64Url(claimsJson())
            val forged = "$header.$payload." // unsecured JWT shape: empty third segment

            val result = OidcJwt.verifySignature(forged, keyPair.publicKeyPem)
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test("a token whose header claims alg=none but carries a non-empty trailing segment is also rejected") {
            val header = base64Url("""{"alg":"none","typ":"JWT"}""")
            val payload = base64Url(claimsJson())
            val forged = "$header.$payload.anything"

            val result = OidcJwt.verifySignature(forged, keyPair.publicKeyPem)
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test(
            "RS256->HS256 confusion: a token claiming HS256, HMAC-signed using the RSA PUBLIC key's raw bytes as the secret, is rejected",
        ) {
            val header = base64Url("""{"alg":"HS256","typ":"JWT","kid":"$kid"}""")
            val payload = base64Url(claimsJson())
            val signingInput = "$header.$payload"

            val rsaPublicKeyBytes = decodeRsaPublicKey(keyPair.publicKeyPem).encoded
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(rsaPublicKeyBytes, "HmacSHA256"))
            val hmacSignature = mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))
            val forged = "$signingInput.${Base64URL.encode(hmacSignature)}"

            // The classic confusion attack: an attacker who only ever observed the RSA PUBLIC key
            // (which this server publishes at /federation/oidc/jwks by design) crafts a token that
            // would validate if a naive implementation dynamically selected an HMAC verifier upon
            // seeing alg=HS256 and fed it the public key bytes as the HMAC secret.
            val result = OidcJwt.verifySignature(forged, keyPair.publicKeyPem)
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        // ── Tamper ──────────────────────────────────────────────────────────

        test("a tampered payload (single byte flip after signing) is rejected") {
            val token = OidcJwt.sign(validClaims(), kid, keyPair.privateKeyPem)
            val parts = token.split(".")
            val tamperedPayload = base64Url(claimsJson(subject = "attacker-controlled-subject"))
            val tampered = "${parts[0]}.$tamperedPayload.${parts[2]}"

            val result = OidcJwt.verifySignature(tampered, keyPair.publicKeyPem)
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test("a token signed by a DIFFERENT keypair is rejected when verified against the expected public key") {
            val token = OidcJwt.sign(validClaims(), kid, otherKeyPair.privateKeyPem)
            val result = OidcJwt.verifySignature(token, keyPair.publicKeyPem)
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        // ── Temporal ────────────────────────────────────────────────────────

        test("an expired token (exp in the past, beyond the clock-skew allowance) is rejected") {
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer("https://home.example")
                    .subject("guest-subject-1")
                    .audience("rp-client-id")
                    .claim("nonce", "test-nonce-123")
                    .issueTime(OidcJwt.toJavaDate(now.minus(2.hours)))
                    .expirationTime(OidcJwt.toJavaDate(now.minus(1.hours)))
                    .build()
            val token = OidcJwt.sign(claims, kid, keyPair.privateKeyPem)

            val result =
                OidcJwt.verifyIdToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id", "test-nonce-123")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test("a not-yet-valid token (iat far in the future, beyond the clock-skew allowance) is rejected") {
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer("https://home.example")
                    .subject("guest-subject-1")
                    .audience("rp-client-id")
                    .claim("nonce", "test-nonce-123")
                    .issueTime(OidcJwt.toJavaDate(now.plus(1.hours)))
                    .expirationTime(OidcJwt.toJavaDate(now.plus(2.hours)))
                    .build()
            val token = OidcJwt.sign(claims, kid, keyPair.privateKeyPem)

            val result =
                OidcJwt.verifyIdToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id", "test-nonce-123")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        // ── iss / aud / nonce ───────────────────────────────────────────────

        test("an ID token whose iss does not match the discovery-fetched origin is rejected") {
            val token = OidcJwt.sign(validClaims(issuer = "https://attacker.example"), kid, keyPair.privateKeyPem)
            val result =
                OidcJwt.verifyIdToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id", "test-nonce-123")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test("an ID token issued for a DIFFERENT relying party (aud mismatch) is rejected") {
            val token = OidcJwt.sign(validClaims(audience = "some-other-rp-client-id"), kid, keyPair.privateKeyPem)
            val result =
                OidcJwt.verifyIdToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id", "test-nonce-123")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test("an ID token whose nonce does not match the nonce generated for THIS login attempt is rejected (replay defense)") {
            val token = OidcJwt.sign(validClaims(nonce = "a-different-earlier-nonce"), kid, keyPair.privateKeyPem)
            val result =
                OidcJwt.verifyIdToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id", "test-nonce-123")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test("an ID token missing the nonce claim entirely is rejected") {
            val token = OidcJwt.sign(validClaims(nonce = null), kid, keyPair.privateKeyPem)
            val result =
                OidcJwt.verifyIdToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id", "test-nonce-123")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        // ── Logout Token spec checks ────────────────────────────────────────

        test("a valid Logout Token round-trips: sign -> verifyLogoutToken succeeds") {
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer("https://home.example")
                    .subject("guest-subject-1")
                    .audience("rp-client-id")
                    .issueTime(OidcJwt.toJavaDate(now))
                    .expirationTime(OidcJwt.toJavaDate(now.plus(5.minutes)))
                    .claim("jti", "logout-jti-1")
                    .claim("events", OidcJwt.logoutEventsClaim())
                    .build()
            val token = OidcJwt.sign(claims, kid, keyPair.privateKeyPem)
            val result = OidcJwt.verifyLogoutToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Valid>()
        }

        test("a Logout Token carrying a nonce claim is rejected (nonce is reserved for ID tokens -- a smuggling attempt)") {
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer("https://home.example")
                    .subject("guest-subject-1")
                    .audience("rp-client-id")
                    .issueTime(OidcJwt.toJavaDate(now))
                    .expirationTime(OidcJwt.toJavaDate(now.plus(5.minutes)))
                    .claim("nonce", "should-not-be-here")
                    .claim("events", OidcJwt.logoutEventsClaim())
                    .build()
            val token = OidcJwt.sign(claims, kid, keyPair.privateKeyPem)
            val result = OidcJwt.verifyLogoutToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        test(
            "a token missing the Back-Channel Logout events structural marker is rejected as a Logout Token (defeats ID-token-as-logout-token smuggling)",
        ) {
            // Structurally a perfectly valid, freshly-signed ID token (no events claim) -- must
            // NEVER be accepted where a Logout Token is expected, or a captured/leaked ID token
            // could be replayed to silently revoke a session it was never meant to end.
            val token = OidcJwt.sign(validClaims(), kid, keyPair.privateKeyPem)
            val result = OidcJwt.verifyLogoutToken(token, keyPair.publicKeyPem, "https://home.example", "rp-client-id")
            result.shouldBeInstanceOf<OidcJwt.VerificationResult.Invalid>()
        }

        // ── extractUnverified* (pre-verification lookups) ────────────────────

        test("extractUnverifiedKid/extractUnverifiedIssuer read the header/payload without verifying anything") {
            val token = OidcJwt.sign(validClaims(), kid, keyPair.privateKeyPem)
            OidcJwt.extractUnverifiedKid(token) shouldBe kid
            OidcJwt.extractUnverifiedIssuer(token) shouldBe "https://home.example"
        }

        test("extractUnverifiedKid/extractUnverifiedIssuer return null for malformed input, never throw") {
            OidcJwt.extractUnverifiedKid("not-a-jwt") shouldBe null
            OidcJwt.extractUnverifiedIssuer("not-a-jwt") shouldBe null
        }
    })

private data class TestRsaKeyPair(
    val publicKeyPem: String,
    val privateKeyPem: String,
)

private fun generateTestRsaKeyPair(): TestRsaKeyPair {
    val generated = FederationKeyPairGenerator.generate()
    return TestRsaKeyPair(generated.publicKeyPem, generated.privateKeyPem)
}

private fun decodeRsaPublicKey(pem: String): RSAPublicKey {
    val base64 = pem.lineSequence().filterNot { it.isBlank() || it.startsWith("-----") }.joinToString("")
    val der = Base64.getDecoder().decode(base64)
    return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
}

private fun base64Url(json: String): String = Base64URL.encode(json.toByteArray(Charsets.UTF_8)).toString()

private fun claimsJson(subject: String = "guest-subject-1"): String =
    """{"iss":"https://home.example","sub":"$subject","aud":"rp-client-id","nonce":"test-nonce-123"}"""
