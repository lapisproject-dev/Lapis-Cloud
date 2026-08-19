package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/**
 * Pure tests of [SepaComplianceDisclaimer] -- no DB access. Mirrors
 * [AuctionComplianceDisclaimerTest] exactly (same mechanism, different text).
 */
class SepaComplianceDisclaimerTest :
    FunSpec({
        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("${SepaComplianceDisclaimer.VERSION}\n${SepaComplianceDisclaimer.TEXT}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            SepaComplianceDisclaimer.SHA256 shouldBe recomputed
            SepaComplianceDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            SepaComplianceDisclaimer.matches(version = SepaComplianceDisclaimer.VERSION, sha256 = SepaComplianceDisclaimer.SHA256) shouldBe
                true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            SepaComplianceDisclaimer.matches(version = "2020-01-01.v0", sha256 = SepaComplianceDisclaimer.SHA256) shouldBe false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + SepaComplianceDisclaimer.SHA256.drop(1)
            SepaComplianceDisclaimer.matches(version = SepaComplianceDisclaimer.VERSION, sha256 = tampered) shouldBe false
        }

        test("matches() rejects a malformed (non-hex / wrong-length) hash without throwing") {
            SepaComplianceDisclaimer.matches(version = SepaComplianceDisclaimer.VERSION, sha256 = "not-a-hex-digest") shouldBe false
            SepaComplianceDisclaimer.matches(version = SepaComplianceDisclaimer.VERSION, sha256 = "") shouldBe false
        }

        test("TEXT names every risk area the disclaimer is required to cover") {
            val text = SepaComplianceDisclaimer.TEXT
            text shouldContain "Gläubiger-Identifikationsnummer"
            text shouldContain "Mandatsschriftform"
            text shouldContain "Vorabankündigungsfrist"
            text shouldContain "Rücklastschriftentgelt"
            text shouldContain "Satzungs-"
        }

        test("TEXT explicitly disclaims automated legal advice and assigns responsibility to the operator") {
            val text = SepaComplianceDisclaimer.TEXT
            text shouldContain "KEINE Rechtsberatung"
            text shouldContain "Betreiber"
        }
    })
