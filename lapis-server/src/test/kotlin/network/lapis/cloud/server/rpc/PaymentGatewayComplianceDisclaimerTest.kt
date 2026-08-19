package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/**
 * Pure tests of [PaymentGatewayComplianceDisclaimer] -- no DB access. Mirrors
 * [AuctionComplianceDisclaimerTest]/[SepaComplianceDisclaimerTest] exactly (same mechanism,
 * different text).
 */
class PaymentGatewayComplianceDisclaimerTest :
    FunSpec({
        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        "${PaymentGatewayComplianceDisclaimer.VERSION}\n${PaymentGatewayComplianceDisclaimer.TEXT}".toByteArray(
                            Charsets.UTF_8,
                        ),
                    ).joinToString("") { "%02x".format(it) }
            PaymentGatewayComplianceDisclaimer.SHA256 shouldBe recomputed
            PaymentGatewayComplianceDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            PaymentGatewayComplianceDisclaimer.matches(
                version = PaymentGatewayComplianceDisclaimer.VERSION,
                sha256 = PaymentGatewayComplianceDisclaimer.SHA256,
            ) shouldBe true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            PaymentGatewayComplianceDisclaimer.matches(
                version = "2020-01-01.v0",
                sha256 = PaymentGatewayComplianceDisclaimer.SHA256,
            ) shouldBe false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + PaymentGatewayComplianceDisclaimer.SHA256.drop(1)
            PaymentGatewayComplianceDisclaimer.matches(version = PaymentGatewayComplianceDisclaimer.VERSION, sha256 = tampered) shouldBe
                false
        }

        test("matches() rejects a malformed (non-hex / wrong-length) hash without throwing") {
            PaymentGatewayComplianceDisclaimer.matches(
                version = PaymentGatewayComplianceDisclaimer.VERSION,
                sha256 = "not-a-hex-digest",
            ) shouldBe false
        }

        test("TEXT names every risk area the disclaimer is required to cover") {
            val text = PaymentGatewayComplianceDisclaimer.TEXT
            text shouldContain "ZAG"
            text shouldContain "Auftragsverarbeitungsvertrag"
            text shouldContain "Drittlandübermittlung"
            text shouldContain "GwG"
            text shouldContain "Parteiengesetz"
            text shouldContain "PCI-DSS"
        }

        test("TEXT explicitly disclaims automated legal advice and assigns responsibility to the operator") {
            val text = PaymentGatewayComplianceDisclaimer.TEXT
            text shouldContain "KEINE Rechtsberatung"
            text shouldContain "Betreiber"
        }
    })
