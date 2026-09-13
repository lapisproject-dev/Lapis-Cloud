package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/**
 * Pure tests of [VatComplianceDisclaimer] -- no DB access. Mirrors [SepaComplianceDisclaimerTest]/
 * [AuctionComplianceDisclaimerTest] exactly (same mechanism, different text).
 */
class VatComplianceDisclaimerTest :
    FunSpec({
        test("VERSION fits the disclaimer_version VARCHAR(20) column (V31__vat.sql)") {
            (VatComplianceDisclaimer.VERSION.length <= 20) shouldBe true
        }

        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("${VatComplianceDisclaimer.VERSION}\n${VatComplianceDisclaimer.TEXT}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            VatComplianceDisclaimer.SHA256 shouldBe recomputed
            VatComplianceDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            VatComplianceDisclaimer.matches(
                version = VatComplianceDisclaimer.VERSION,
                sha256 = VatComplianceDisclaimer.SHA256,
            ) shouldBe true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            VatComplianceDisclaimer.matches(version = "2020-01-01.v0", sha256 = VatComplianceDisclaimer.SHA256) shouldBe false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + VatComplianceDisclaimer.SHA256.drop(1)
            VatComplianceDisclaimer.matches(version = VatComplianceDisclaimer.VERSION, sha256 = tampered) shouldBe false
        }

        test("matches() rejects a malformed (non-hex / odd-length / empty) hash without throwing") {
            VatComplianceDisclaimer.matches(version = VatComplianceDisclaimer.VERSION, sha256 = "not-a-hex-digest") shouldBe false
            VatComplianceDisclaimer.matches(version = VatComplianceDisclaimer.VERSION, sha256 = "abc") shouldBe false
            VatComplianceDisclaimer.matches(version = VatComplianceDisclaimer.VERSION, sha256 = "") shouldBe false
        }

        test("TEXT names every risk area the disclaimer is required to cover") {
            val text = VatComplianceDisclaimer.TEXT
            text shouldContain "ELSTER"
            text shouldContain "Wettbewerbsvorbehalt"
            text shouldContain "Kleinunternehmer"
            text shouldContain "BRUTTO"
            text shouldContain "lexoffice-/sevDesk"
        }

        test("TEXT explicitly disclaims automated legal advice and assigns responsibility to the operator") {
            val text = VatComplianceDisclaimer.TEXT
            text shouldContain "KEINE Rechtsberatung"
            text shouldContain "Betreiber"
        }
    })
