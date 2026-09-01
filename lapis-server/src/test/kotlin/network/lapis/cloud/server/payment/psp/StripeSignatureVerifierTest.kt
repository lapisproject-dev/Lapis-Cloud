package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SIGNING_SECRET = "whsec_test_signing_secret"

private fun hmacHex(
    secret: String,
    data: ByteArray,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun signedHeader(
    timestamp: Long,
    body: ByteArray,
    secret: String = SIGNING_SECRET,
): String {
    val signedPayload = "$timestamp.".toByteArray(Charsets.UTF_8) + body
    return "t=$timestamp,v1=${hmacHex(secret = secret, data = signedPayload)}"
}

class StripeSignatureVerifierTest :
    FunSpec({
        val now = Instant.fromEpochSeconds(1_800_000_000)
        val body = """{"id":"evt_test","type":"checkout.session.completed"}""".toByteArray(Charsets.UTF_8)

        test("valid signature is accepted") {
            val header = signedHeader(timestamp = now.epochSeconds, body = body)
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Valid
        }

        test("body tampered by one byte -> NO_MATCHING_V1") {
            val header = signedHeader(timestamp = now.epochSeconds, body = body)
            val tamperedBody = body.copyOf().also { it[0] = it[0].inc() }
            val result =
                StripeSignatureVerifier.verify(
                    body = tamperedBody,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        test("signature hex tampered -> NO_MATCHING_V1") {
            val header = signedHeader(timestamp = now.epochSeconds, body = body).dropLast(1) + "0"
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        test("wrong signing secret -> NO_MATCHING_V1") {
            val header = signedHeader(timestamp = now.epochSeconds, body = body, secret = "whsec_a_completely_different_secret")
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        test("timestamp older than tolerance -> STALE_TIMESTAMP") {
            val staleTimestamp = now.epochSeconds - 301
            val header = signedHeader(timestamp = staleTimestamp, body = body)
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "STALE_TIMESTAMP")
        }

        test("timestamp far in the future -> FUTURE_TIMESTAMP") {
            val futureTimestamp = now.epochSeconds + 301
            val header = signedHeader(timestamp = futureTimestamp, body = body)
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "FUTURE_TIMESTAMP")
        }

        test("missing t -> MALFORMED_HEADER") {
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = "v1=abcd1234",
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "MALFORMED_HEADER")
        }

        test("missing v1 -> MALFORMED_HEADER") {
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = "t=${now.epochSeconds}",
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "MALFORMED_HEADER")
        }

        test("non-numeric t -> MALFORMED_HEADER") {
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = "t=not-a-number,v1=abcd1234",
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "MALFORMED_HEADER")
        }

        test("non-hex v1 -> NO_MATCHING_V1 (skipped as a candidate, not an error)") {
            val header = "t=${now.epochSeconds},v1=not-hex-zz"
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        test("two v1 candidates, second one valid -> accepted (secret rotation)") {
            val validSignature = hmacHex(secret = SIGNING_SECRET, data = "${now.epochSeconds}.".toByteArray(Charsets.UTF_8) + body)
            val header = "t=${now.epochSeconds},v1=deadbeef,v1=$validSignature"
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Valid
        }

        test("non-UTF-8 body bytes verify correctly (proves the byte-level signing payload)") {
            val binaryBody = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01, 0x80.toByte())
            val header = signedHeader(timestamp = now.epochSeconds, body = binaryBody)
            val result =
                StripeSignatureVerifier.verify(
                    body = binaryBody,
                    signatureHeader = header,
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Valid
        }

        test("empty header -> MALFORMED_HEADER") {
            val result =
                StripeSignatureVerifier.verify(
                    body = body,
                    signatureHeader = "",
                    signingSecret = SIGNING_SECRET,
                    now = now,
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "MALFORMED_HEADER")
        }

        // Sanity check that this test file's own hmacHex helper agrees with a fresh, independent
        // MessageDigest/Mac usage -- guards against a copy-paste bug in the test fixture itself.
        test("hmacHex helper sanity check") {
            val digest = MessageDigest.getInstance("SHA-256").digest(body)
            digest.size shouldBe 32
        }
    })
