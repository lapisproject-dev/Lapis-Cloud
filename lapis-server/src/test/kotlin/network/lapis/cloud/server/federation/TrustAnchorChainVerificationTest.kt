package network.lapis.cloud.server.federation

import com.nimbusds.jwt.JWTClaimsSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * [TrustAnchorChainVerification] -- the pure, network-free cryptographic core of the V0.8.3
 * one-hop trust-chain resolution. Mandatory tamper/replay negative-test coverage for
 * security-relevant code (CLAUDE.md), specialized to the attack classes the task explicitly names:
 * a forged/tampered Subordinate Statement signature, an expired statement, a statement signed by a
 * key that isn't the claimed anchor's actual current key, and key-rollover allowing a grace-period
 * old (RETIRED) key to keep verifying while a REVOKED key never does. Every test uses REAL RSA-2048
 * keypairs (never a mock), same posture [OidcJwtTest] already establishes.
 */
class TrustAnchorChainVerificationTest :
    FunSpec({
        val anchorUri = "https://anchor.example"
        val homeServerUri = "https://home.example"
        val fetchEndpoint = "https://anchor.example/federation/trust-anchor/fetch"

        val activeKid = "active-kid-1"
        val activeKeyPair = generateTrustAnchorTestRsaKeyPair()
        val retiredKid = "retired-kid-1"
        val retiredKeyPair = generateTrustAnchorTestRsaKeyPair()
        val revokedKid = "revoked-kid-1"
        val revokedKeyPair = generateTrustAnchorTestRsaKeyPair()
        val strangerKeyPair = generateTrustAnchorTestRsaKeyPair()

        // The anchor's currently-published jwks -- ACTIVE + RETIRED only, REVOKED deliberately
        // excluded (mirrors TrustAnchorSigningKeyStore.listPublishable's own filter), modelling the
        // real key-rollover/compromise-response state this wave's design produces.
        val publishedJwksJson =
            TrustAnchorJwks.buildJwksJson(
                listOf(
                    TrustAnchorPublishableKey(activeKid, activeKeyPair.publicKeyPem),
                    TrustAnchorPublishableKey(retiredKid, retiredKeyPair.publicKeyPem),
                ),
            )

        fun statementClaims(
            iss: String = anchorUri,
            sub: String = homeServerUri,
            expiresIn: kotlin.time.Duration = 24.hours,
            issuedAgo: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        ): JWTClaimsSet {
            val now = Clock.System.now()
            return JWTClaimsSet
                .Builder()
                .issuer(iss)
                .subject(sub)
                .issueTime(OidcJwt.toJavaDate(now - issuedAgo))
                .expirationTime(OidcJwt.toJavaDate(now + expiresIn))
                .build()
        }

        // ── verifyEntityConfiguration: happy path ──────────────────────────

        test("a validly self-signed Entity Configuration verifies and yields its jwks + fetch endpoint") {
            val ecClaims =
                JWTClaimsSet
                    .Builder()
                    .issuer(anchorUri)
                    .subject(anchorUri)
                    .issueTime(OidcJwt.toJavaDate(Clock.System.now()))
                    .expirationTime(OidcJwt.toJavaDate(Clock.System.now() + 24.hours))
                    .claim("jwks", TrustAnchorJwks.buildJwksClaim(listOf(TrustAnchorPublishableKey(activeKid, activeKeyPair.publicKeyPem))))
                    .claim("metadata", mapOf("federation_entity" to mapOf("federation_fetch_endpoint" to fetchEndpoint)))
                    .build()
            val compact = OidcJwt.sign(ecClaims, activeKid, activeKeyPair.privateKeyPem)

            val result = TrustAnchorChainVerification.verifyEntityConfiguration(compact, anchorUri)

            requireNotNull(result)
            result.fetchEndpoint shouldBe fetchEndpoint
            result.jwksJson shouldContain activeKid
        }

        // ── verifyEntityConfiguration: negative ────────────────────────────

        test("an Entity Configuration whose sub does not self-assert the expected anchor identity is rejected") {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(anchorUri)
                    .subject("https://attacker.example")
                    .issueTime(OidcJwt.toJavaDate(Clock.System.now()))
                    .expirationTime(OidcJwt.toJavaDate(Clock.System.now() + 24.hours))
                    .claim("jwks", TrustAnchorJwks.buildJwksClaim(listOf(TrustAnchorPublishableKey(activeKid, activeKeyPair.publicKeyPem))))
                    .claim("metadata", mapOf("federation_entity" to mapOf("federation_fetch_endpoint" to fetchEndpoint)))
                    .build()
            val compact = OidcJwt.sign(claims, activeKid, activeKeyPair.privateKeyPem)

            TrustAnchorChainVerification.verifyEntityConfiguration(compact, anchorUri) shouldBe null
        }

        test("an expired Entity Configuration is rejected") {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(anchorUri)
                    .subject(anchorUri)
                    .issueTime(OidcJwt.toJavaDate(Clock.System.now() - 2.hours))
                    .expirationTime(OidcJwt.toJavaDate(Clock.System.now() - 1.hours))
                    .claim("jwks", TrustAnchorJwks.buildJwksClaim(listOf(TrustAnchorPublishableKey(activeKid, activeKeyPair.publicKeyPem))))
                    .claim("metadata", mapOf("federation_entity" to mapOf("federation_fetch_endpoint" to fetchEndpoint)))
                    .build()
            val compact = OidcJwt.sign(claims, activeKid, activeKeyPair.privateKeyPem)

            TrustAnchorChainVerification.verifyEntityConfiguration(compact, anchorUri) shouldBe null
        }

        test("an Entity Configuration signed by a key NOT present in its own jwks claim is rejected") {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(anchorUri)
                    .subject(anchorUri)
                    .issueTime(OidcJwt.toJavaDate(Clock.System.now()))
                    .expirationTime(OidcJwt.toJavaDate(Clock.System.now() + 24.hours))
                    // jwks claims a key under activeKid, but the JWT is signed with strangerKeyPair.
                    .claim("jwks", TrustAnchorJwks.buildJwksClaim(listOf(TrustAnchorPublishableKey(activeKid, activeKeyPair.publicKeyPem))))
                    .claim("metadata", mapOf("federation_entity" to mapOf("federation_fetch_endpoint" to fetchEndpoint)))
                    .build()
            val compact = OidcJwt.sign(claims, activeKid, strangerKeyPair.privateKeyPem)

            TrustAnchorChainVerification.verifyEntityConfiguration(compact, anchorUri) shouldBe null
        }

        test("a tampered Entity Configuration (single byte flip in the signature) is rejected") {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(anchorUri)
                    .subject(anchorUri)
                    .issueTime(OidcJwt.toJavaDate(Clock.System.now()))
                    .expirationTime(OidcJwt.toJavaDate(Clock.System.now() + 24.hours))
                    .claim("jwks", TrustAnchorJwks.buildJwksClaim(listOf(TrustAnchorPublishableKey(activeKid, activeKeyPair.publicKeyPem))))
                    .claim("metadata", mapOf("federation_entity" to mapOf("federation_fetch_endpoint" to fetchEndpoint)))
                    .build()
            val compact = OidcJwt.sign(claims, activeKid, activeKeyPair.privateKeyPem)
            val tampered = tamperSignature(compact)

            TrustAnchorChainVerification.verifyEntityConfiguration(tampered, anchorUri) shouldBe null
        }

        // ── verifySubordinateStatement: happy path + key rollover ──────────

        test("a validly-signed Subordinate Statement, signed by the current ACTIVE key, verifies") {
            val compact = OidcJwt.sign(statementClaims(), activeKid, activeKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe true
        }

        test("key rollover: a Subordinate Statement signed by the RETIRED (rotated-out) key still verifies -- grace period") {
            val compact = OidcJwt.sign(statementClaims(), retiredKid, retiredKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe true
        }

        test("compromise response: a Subordinate Statement signed by a REVOKED key (excluded from the published jwks) never verifies") {
            // revokedKid/revokedKeyPair is deliberately absent from publishedJwksJson -- models
            // TrustAnchorSigningKeyStore.listPublishable()'s exclusion of REVOKED keys, see
            // 26-trust-anchor.kuml.kts file header "Why revocation needs more than expiry alone".
            val compact = OidcJwt.sign(statementClaims(), revokedKid, revokedKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        // ── verifySubordinateStatement: negative ───────────────────────────

        test("a tampered Subordinate Statement (single byte flip in the signature) is rejected") {
            val compact = OidcJwt.sign(statementClaims(), activeKid, activeKeyPair.privateKeyPem)
            val tampered = tamperSignature(compact)
            TrustAnchorChainVerification.verifySubordinateStatement(tampered, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a Subordinate Statement whose payload was tampered after signing (sub substituted) is rejected") {
            val compact = OidcJwt.sign(statementClaims(), activeKid, activeKeyPair.privateKeyPem)
            val parts = compact.split(".")
            val tamperedPayloadClaims = statementClaims(sub = "https://attacker-controlled.example")
            val tamperedCompactForPayload = OidcJwt.sign(tamperedPayloadClaims, activeKid, activeKeyPair.privateKeyPem)
            val tamperedParts = tamperedCompactForPayload.split(".")
            // Recombine: original header+signature (as issued for the real sub) with a payload that
            // claims a different sub -- this must fail because the signature no longer matches.
            val tampered = "${parts[0]}.${tamperedParts[1]}.${parts[2]}"
            TrustAnchorChainVerification.verifySubordinateStatement(tampered, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a Subordinate Statement signed with a key material that does NOT match the claimed anchor's kid entry is rejected") {
            // Header names activeKid (a real, published kid) but the signature was actually
            // produced with an entirely different private key -- the classic "kid says one key,
            // signature says another" substitution the task explicitly names.
            val compact = OidcJwt.sign(statementClaims(), activeKid, strangerKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("an expired Subordinate Statement is rejected") {
            val compact = OidcJwt.sign(statementClaims(issuedAgo = 2.hours, expiresIn = -1.hours), activeKid, activeKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a not-yet-valid Subordinate Statement (iat far in the future) is rejected") {
            val compact =
                OidcJwt.sign(statementClaims(issuedAgo = (-1).hours, expiresIn = 2.hours), activeKid, activeKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a Subordinate Statement issued by a DIFFERENT party (iss mismatch) is rejected") {
            val compact = OidcJwt.sign(statementClaims(iss = "https://a-different-anchor.example"), activeKid, activeKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a Subordinate Statement about a DIFFERENT home server (sub mismatch) is rejected") {
            val compact =
                OidcJwt.sign(statementClaims(sub = "https://a-different-home-server.example"), activeKid, activeKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a Subordinate Statement whose kid is entirely unknown to the anchor's jwks is rejected") {
            val compact = OidcJwt.sign(statementClaims(), "unknown-kid", activeKeyPair.privateKeyPem)
            TrustAnchorChainVerification.verifySubordinateStatement(compact, anchorUri, homeServerUri, publishedJwksJson) shouldBe false
        }

        test("a not-well-formed compact string is rejected, never throws") {
            TrustAnchorChainVerification.verifySubordinateStatement("not-a-jwt", anchorUri, homeServerUri, publishedJwksJson) shouldBe false
            TrustAnchorChainVerification.verifyEntityConfiguration("not-a-jwt", anchorUri) shouldBe null
        }
    })

private data class TrustAnchorTestRsaKeyPair(
    val publicKeyPem: String,
    val privateKeyPem: String,
)

private fun generateTrustAnchorTestRsaKeyPair(): TrustAnchorTestRsaKeyPair {
    val generated = FederationKeyPairGenerator.generate()
    return TrustAnchorTestRsaKeyPair(generated.publicKeyPem, generated.privateKeyPem)
}

/** Flips one byte in the signature segment (last, base64url) of a compact JWT -- same tamper shape [OidcJwtTest] uses for its own single-byte-flip tests. */
private fun tamperSignature(compact: String): String {
    val parts = compact.split(".")
    val sigBytes =
        com.nimbusds.jose.util
            .Base64URL(parts[2])
            .decode()
    sigBytes[0] = (sigBytes[0].toInt() xor 0xFF).toByte()
    val tamperedSig =
        com.nimbusds.jose.util.Base64URL
            .encode(sigBytes)
            .toString()
    return "${parts[0]}.${parts[1]}.$tamperedSig"
}
