package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun headersOf(
    transmissionId: String? = "txn-id-1",
    transmissionTime: String? = "2026-09-15T10:00:00Z",
    transmissionSig: String? = "sig-value",
    certUrl: String? = "https://api-m.paypal.com/cert",
    authAlgo: String? = "SHA256withRSA",
): Map<String, String?> =
    mapOf(
        "PAYPAL-TRANSMISSION-ID" to transmissionId,
        "PAYPAL-TRANSMISSION-TIME" to transmissionTime,
        "PAYPAL-TRANSMISSION-SIG" to transmissionSig,
        "PAYPAL-CERT-URL" to certUrl,
        "PAYPAL-AUTH-ALGO" to authAlgo,
    )

class PaypalWebhookVerificationTest :
    FunSpec({
        test("all five headers present and well-formed -> parses successfully") {
            val headers = headersOf()
            val parsed = PaypalTransmissionHeaders.parseOrNull { headers[it] }
            (parsed != null) shouldBe true
        }

        test("missing header -> null") {
            val headers = headersOf(transmissionSig = null)
            PaypalTransmissionHeaders.parseOrNull { headers[it] } shouldBe null
        }

        test("blank header -> null") {
            val headers = headersOf(transmissionId = "   ")
            PaypalTransmissionHeaders.parseOrNull { headers[it] } shouldBe null
        }

        test("oversized header (>512 chars) -> null") {
            val headers = headersOf(transmissionId = "x".repeat(513))
            PaypalTransmissionHeaders.parseOrNull { headers[it] } shouldBe null
        }

        test("certUrl on a non-PayPal host -> null") {
            val headers = headersOf(certUrl = "https://attacker.example/cert")
            PaypalTransmissionHeaders.parseOrNull { headers[it] } shouldBe null
        }

        test("certUrl with a non-https scheme -> null") {
            val headers = headersOf(certUrl = "http://api-m.paypal.com/cert")
            PaypalTransmissionHeaders.parseOrNull { headers[it] } shouldBe null
        }

        test("certUrl on api-m.sandbox.paypal.com -> accepted") {
            val headers = headersOf(certUrl = "https://api-m.sandbox.paypal.com/cert")
            val parsed = PaypalTransmissionHeaders.parseOrNull { headers[it] }
            (parsed != null) shouldBe true
        }

        test("checkTransmissionFreshness -- within tolerance -> Valid") {
            val now = Instant.parse("2026-09-15T10:00:00Z")
            val result = checkTransmissionFreshness(transmissionTime = "2026-09-15T09:58:00Z", now = now, tolerance = 5.minutes)
            result shouldBe PaypalSignatureResult.Valid
        }

        test("checkTransmissionFreshness -- past tolerance -> STALE_TIMESTAMP") {
            val now = Instant.parse("2026-09-15T10:00:00Z")
            val result = checkTransmissionFreshness(transmissionTime = "2026-09-15T09:00:00Z", now = now, tolerance = 5.minutes)
            result shouldBe PaypalSignatureResult.Invalid("STALE_TIMESTAMP")
        }

        test("checkTransmissionFreshness -- future beyond tolerance -> FUTURE_TIMESTAMP") {
            val now = Instant.parse("2026-09-15T10:00:00Z")
            val result = checkTransmissionFreshness(transmissionTime = "2026-09-15T10:30:00Z", now = now, tolerance = 5.minutes)
            result shouldBe PaypalSignatureResult.Invalid("FUTURE_TIMESTAMP")
        }

        test("checkTransmissionFreshness -- unparseable time -> MALFORMED_HEADER") {
            val now = Instant.parse("2026-09-15T10:00:00Z")
            val result = checkTransmissionFreshness(transmissionTime = "not-a-timestamp", now = now, tolerance = 5.minutes)
            result shouldBe PaypalSignatureResult.Invalid("MALFORMED_HEADER")
        }
    })
