package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * [HttpSignatures] sign/verify -- the mandatory tamper/replay negative-test coverage for
 * security-relevant code (CLAUDE.md). Every [HttpSignatures.verify] call in this file uses a real
 * RSA-2048 keypair from [FederationKeyPairGenerator] (never a mock) so the actual JCA signature
 * path is exercised end to end, not just the string-parsing scaffolding around it.
 */
class HttpSignaturesTest :
    FunSpec({
        val keyPair = FederationKeyPairGenerator.generate()
        val otherKeyPair = FederationKeyPairGenerator.generate()
        val body = """{"type":"Follow","actor":"https://example.org/federation/actor"}""".toByteArray(Charsets.UTF_8)
        val keyId = "https://example.org/federation/actor#main-key"

        test("a valid signature over a realistic request verifies as Valid") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )

            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    signatureHeader = signed.signatureHeader,
                    dateHeader = signed.dateHeader,
                    digestHeader = signed.digestHeader,
                    publicKeyPem = keyPair.publicKeyPem,
                )

            result shouldBe HttpSignatures.VerificationResult.Valid
        }

        test(
            "a validly-signed request whose headers= list omits digest/host/(request-target) -- covering ONLY " +
                "date -- is rejected as MALFORMED, not accepted as Valid -- round-1 review fix (MAJOR)",
        ) {
            // Hand-crafted, NOT via HttpSignatures.sign() (which always signs the full required
            // set) -- simulates a sender that legitimately owns the key but only signs `date`,
            // which would otherwise let a MITM/relay swap `digest`/body freely while keeping a
            // signature that still verifies (the signature never covered them).
            val now = Clock.System.now()
            val nowJavaInstant = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
            val dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(nowJavaInstant.atZone(ZoneOffset.UTC))
            val digestHeader =
                "SHA-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
            val minimalSigningString = "date: $dateHeader"

            val privateKeyBase64 =
                keyPair.privateKeyPem
                    .lineSequence()
                    .filterNot { it.isBlank() || it.startsWith("-----") }
                    .joinToString("")
            val der = Base64.getDecoder().decode(privateKeyBase64)
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(minimalSigningString.toByteArray(Charsets.UTF_8))
            val signatureB64 = Base64.getEncoder().encodeToString(signature.sign())

            val signatureHeader = "keyId=\"$keyId\",algorithm=\"rsa-sha256\",headers=\"date\",signature=\"$signatureB64\""

            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    signatureHeader = signatureHeader,
                    dateHeader = dateHeader,
                    digestHeader = digestHeader,
                    publicKeyPem = keyPair.publicKeyPem,
                )

            result shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MALFORMED)
        }

        test("sign() then verify() round-trips for the exact same request") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )
            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = signed.signatureHeader,
                dateHeader = signed.dateHeader,
                digestHeader = signed.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
            ) shouldBe HttpSignatures.VerificationResult.Valid
        }

        test("a tampered body (digest header no longer matches the actual body) is rejected as DIGEST_MISMATCH") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )
            val tamperedBody = """{"type":"Follow","actor":"https://evil.example/federation/actor"}""".toByteArray(Charsets.UTF_8)

            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = tamperedBody,
                    signatureHeader = signed.signatureHeader,
                    dateHeader = signed.dateHeader,
                    digestHeader = signed.digestHeader,
                    publicKeyPem = keyPair.publicKeyPem,
                )

            result shouldBe HttpSignatures.VerificationResult.Invalid(REASON_DIGEST_MISMATCH)
        }

        test("a tampered Signature header (one flipped base64 character) is rejected as SIGNATURE_MISMATCH") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )
            val tamperedSignatureHeader = flipOneSignatureChar(signed.signatureHeader)

            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    signatureHeader = tamperedSignatureHeader,
                    dateHeader = signed.dateHeader,
                    digestHeader = signed.digestHeader,
                    publicKeyPem = keyPair.publicKeyPem,
                )

            result shouldBe HttpSignatures.VerificationResult.Invalid(REASON_SIGNATURE_MISMATCH)
        }

        test("a signature computed with the WRONG private key, verified against the real public key, is rejected as SIGNATURE_MISMATCH") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = otherKeyPair.privateKeyPem,
                )

            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    signatureHeader = signed.signatureHeader,
                    dateHeader = signed.dateHeader,
                    digestHeader = signed.digestHeader,
                    publicKeyPem = keyPair.publicKeyPem,
                )

            result shouldBe HttpSignatures.VerificationResult.Invalid(REASON_SIGNATURE_MISMATCH)
        }

        test("each of Signature/Date/Digest missing individually is rejected as MISSING_HEADER") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = null,
                dateHeader = signed.dateHeader,
                digestHeader = signed.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
            ) shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MISSING_HEADER)

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = signed.signatureHeader,
                dateHeader = null,
                digestHeader = signed.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
            ) shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MISSING_HEADER)

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = signed.signatureHeader,
                dateHeader = signed.dateHeader,
                digestHeader = null,
                publicKeyPem = keyPair.publicKeyPem,
            ) shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MISSING_HEADER)
        }

        test("a Date header 10 minutes in the past is rejected as STALE; 10 minutes in the future too") {
            val now = Clock.System.now()
            val past =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                    now = now - 10.minutes,
                )
            val future =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                    now = now + 10.minutes,
                )

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = past.signatureHeader,
                dateHeader = past.dateHeader,
                digestHeader = past.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
                now = now,
            ) shouldBe HttpSignatures.VerificationResult.Invalid(REASON_STALE)

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = future.signatureHeader,
                dateHeader = future.dateHeader,
                digestHeader = future.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
                now = now,
            ) shouldBe HttpSignatures.VerificationResult.Invalid(REASON_STALE)
        }

        test("a Date header 1 minute in either direction is within the freshness window -- still Valid") {
            val now = Clock.System.now()
            val past =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                    now = now - 1.minutes,
                )
            val future =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                    now = now + 1.minutes,
                )

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = past.signatureHeader,
                dateHeader = past.dateHeader,
                digestHeader = past.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
                now = now,
            ) shouldBe HttpSignatures.VerificationResult.Valid

            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = future.signatureHeader,
                dateHeader = future.dateHeader,
                digestHeader = future.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
                now = now,
            ) shouldBe HttpSignatures.VerificationResult.Valid
        }

        test("a malformed Signature header (missing the headers= param) is rejected as MALFORMED, never throws") {
            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    signatureHeader = "keyId=\"$keyId\",algorithm=\"rsa-sha256\",signature=\"garbage\"",
                    dateHeader = "Tue, 07 Jun 2016 20:51:35 GMT",
                    digestHeader = "SHA-256=garbage",
                    publicKeyPem = keyPair.publicKeyPem,
                )
            result shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MALFORMED)
        }

        test("a Signature header with garbage (non-base64) signature value is rejected as MALFORMED, never throws") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )
            val garbageHeader =
                "keyId=\"$keyId\",algorithm=\"rsa-sha256\",headers=\"(request-target) host date digest\",signature=\"not-base64!!!\""

            val result =
                HttpSignatures.verify(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    signatureHeader = garbageHeader,
                    dateHeader = signed.dateHeader,
                    digestHeader = signed.digestHeader,
                    publicKeyPem = keyPair.publicKeyPem,
                )
            result shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MALFORMED)
        }

        test("an empty/blank Signature header is rejected as MISSING_HEADER, never throws") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )
            HttpSignatures.verify(
                method = "POST",
                path = "/federation/inbox",
                host = "remote.example",
                body = body,
                signatureHeader = "",
                dateHeader = signed.dateHeader,
                digestHeader = signed.digestHeader,
                publicKeyPem = keyPair.publicKeyPem,
            ) shouldBe HttpSignatures.VerificationResult.Invalid(REASON_MISSING_HEADER)
        }

        test("extractKeyId returns the bare keyId from a well-formed header, null from a malformed/absent one") {
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = "/federation/inbox",
                    host = "remote.example",
                    body = body,
                    keyId = keyId,
                    privateKeyPem = keyPair.privateKeyPem,
                )
            HttpSignatures.extractKeyId(signed.signatureHeader) shouldBe keyId
            HttpSignatures.extractKeyId(null) shouldBe null
            HttpSignatures.extractKeyId("garbage, no key=value pairs at all") shouldBe null
        }
    })

private fun flipOneSignatureChar(signatureHeader: String): String {
    val signatureValueStart = signatureHeader.indexOf("signature=\"") + "signature=\"".length
    val charToFlip = signatureHeader[signatureValueStart]
    val replacement = if (charToFlip == 'A') 'B' else 'A'
    return signatureHeader.substring(0, signatureValueStart) + replacement + signatureHeader.substring(signatureValueStart + 1)
}
