package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.lapis.cloud.server.payment.psp.StripeSignatureResult
import network.lapis.cloud.server.payment.psp.StripeSignatureVerifier
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SECRET = "whsec_lapis_test_signing_secret"

class WebhookSignerTest :
    FunSpec({
        val timestamp = 1_800_000_000L
        val payload =
            """{"id":"evt-1","eventType":"resolution.adopted","entityId":"x","occurredAt":"2026-01-01T00:00:00"}""".toByteArray(
                Charsets.UTF_8,
            )

        test("round-trip: WebhookSigner.sign verifies as Valid against StripeSignatureVerifier") {
            val header = WebhookSigner.sign(payload = payload, secret = SECRET, timestampSeconds = timestamp)
            val result =
                StripeSignatureVerifier.verify(
                    body = payload,
                    signatureHeader = header,
                    signingSecret = SECRET,
                    now = Instant.fromEpochSeconds(timestamp),
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Valid
        }

        test("one tampered payload byte fails verification") {
            val header = WebhookSigner.sign(payload = payload, secret = SECRET, timestampSeconds = timestamp)
            val tampered = payload.copyOf().also { it[0] = it[0].inc() }
            val result =
                StripeSignatureVerifier.verify(
                    body = tampered,
                    signatureHeader = header,
                    signingSecret = SECRET,
                    now = Instant.fromEpochSeconds(timestamp),
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        test("wrong secret fails verification") {
            val header = WebhookSigner.sign(payload = payload, secret = SECRET, timestampSeconds = timestamp)
            val result =
                StripeSignatureVerifier.verify(
                    body = payload,
                    signatureHeader = header,
                    signingSecret = "whsec_lapis_wrong_secret",
                    now = Instant.fromEpochSeconds(timestamp),
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Invalid(reason = "NO_MATCHING_V1")
        }

        test("two attempts of the same delivery produce different t/v1 but sign the identical body") {
            val first = WebhookSigner.sign(payload = payload, secret = SECRET, timestampSeconds = timestamp)
            val second = WebhookSigner.sign(payload = payload, secret = SECRET, timestampSeconds = timestamp + 1800)
            first shouldNotBe second
            // Both still verify against the SAME body -- proves the body itself never changed between attempts.
            StripeSignatureVerifier.verify(
                body = payload,
                signatureHeader = first,
                signingSecret = SECRET,
                now = Instant.fromEpochSeconds(timestamp),
                tolerance = 300.seconds,
            ) shouldBe StripeSignatureResult.Valid
            StripeSignatureVerifier.verify(
                body = payload,
                signatureHeader = second,
                signingSecret = SECRET,
                now = Instant.fromEpochSeconds(timestamp + 1800),
                tolerance = 300.seconds,
            ) shouldBe StripeSignatureResult.Valid
        }

        test("multi-byte UTF-8 payload round-trips correctly (bytes, not String)") {
            val utf8Payload = """{"eventType":"Bezirk Möbelträger 🎉"}""".toByteArray(Charsets.UTF_8)
            val header = WebhookSigner.sign(payload = utf8Payload, secret = SECRET, timestampSeconds = timestamp)
            val result =
                StripeSignatureVerifier.verify(
                    body = utf8Payload,
                    signatureHeader = header,
                    signingSecret = SECRET,
                    now = Instant.fromEpochSeconds(timestamp),
                    tolerance = 300.seconds,
                )
            result shouldBe StripeSignatureResult.Valid
        }

        test("header grammar is exactly t=<digits>,v1=<64 lowercase hex chars>") {
            val header = WebhookSigner.sign(payload = payload, secret = SECRET, timestampSeconds = timestamp)
            val regex = Regex("^t=\\d+,v1=[0-9a-f]{64}$")
            (regex.matches(header)) shouldBe true
        }
    })
