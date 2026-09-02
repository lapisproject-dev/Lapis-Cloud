package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OutboundUrlGuardTest :
    FunSpec({
        test("http:// is rejected as NOT_HTTPS when allowInsecureHttp = false") {
            val result = checkWebhookUrl(raw = "http://example.com/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_HTTPS)
        }

        test("http:// is accepted when allowInsecureHttp = true, for a publicly routable host") {
            val result = checkWebhookUrl(raw = "http://93.184.216.34/hook", allowInsecureHttp = true)
            result shouldBe WebhookUrlCheck.Ok(safeFederationTargetForTest(originalHost = "93.184.216.34"))
        }

        test("IPv6 unique-local address [fd00::1] is rejected as NOT_PUBLICLY_ROUTABLE") {
            val result = checkWebhookUrl(raw = "https://[fd00::1]/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("cloud metadata address 169.254.169.254 is rejected as NOT_PUBLICLY_ROUTABLE") {
            val result = checkWebhookUrl(raw = "https://169.254.169.254/latest/meta-data/", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("IPv4-mapped-IPv6 loopback [::ffff:127.0.0.1] is rejected as NOT_PUBLICLY_ROUTABLE") {
            val result = checkWebhookUrl(raw = "https://[::ffff:127.0.0.1]/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("CGNAT address 100.64.0.1 is rejected as NOT_PUBLICLY_ROUTABLE") {
            val result = checkWebhookUrl(raw = "https://100.64.0.1/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("plain loopback 127.0.0.1 is rejected as NOT_PUBLICLY_ROUTABLE") {
            val result = checkWebhookUrl(raw = "https://127.0.0.1/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("RFC 1918 private address 10.0.0.5 is rejected as NOT_PUBLICLY_ROUTABLE") {
            val result = checkWebhookUrl(raw = "https://10.0.0.5/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("a URL longer than 2048 characters is rejected as TOO_LONG") {
            val longPath = "a".repeat(2100)
            val result = checkWebhookUrl(raw = "https://example.com/$longPath", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.TOO_LONG)
        }

        test("an unresolvable host is rejected as NOT_PUBLICLY_ROUTABLE, not as a crash") {
            val result = checkWebhookUrl(raw = "https://this-host-does-not-exist.invalid/hook", allowInsecureHttp = false)
            result shouldBe WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
        }

        test("Rejected carries structurally nothing but the fixed enum reason -- no free-text field to leak an IP/hostname through") {
            val rejections =
                listOf(
                    checkWebhookUrl(raw = "http://example.com", allowInsecureHttp = false),
                    checkWebhookUrl(raw = "https://[fd00::1]/x", allowInsecureHttp = false),
                    checkWebhookUrl(raw = "https://169.254.169.254/x", allowInsecureHttp = false),
                    checkWebhookUrl(raw = "https://example.com/${"a".repeat(2100)}", allowInsecureHttp = false),
                )
            rejections.forEach { r -> (r is WebhookUrlCheck.Rejected) shouldBe true }
            (rejections.map { (it as WebhookUrlCheck.Rejected).reason }) shouldBe
                listOf(
                    WebhookUrlRejectionReason.NOT_HTTPS,
                    WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE,
                    WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE,
                    WebhookUrlRejectionReason.TOO_LONG,
                )
        }
    })

/** Test-only equality helper -- [SafeFederationTarget] carries a [java.net.InetAddress] whose `equals` compares by address bytes, so a literal-IP host round-trips as expected here. */
private fun safeFederationTargetForTest(originalHost: String) =
    network.lapis.cloud.server.federation.SafeFederationTarget(
        originalHost = originalHost,
        pinnedAddress = java.net.InetAddress.getByName(originalHost),
    )
