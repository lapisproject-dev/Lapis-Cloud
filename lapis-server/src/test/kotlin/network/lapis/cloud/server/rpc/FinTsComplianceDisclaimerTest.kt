package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/** Pure tests of [FinTsComplianceDisclaimer] -- no DB access. Mirrors [VatComplianceDisclaimerTest] exactly. */
class FinTsComplianceDisclaimerTest :
    FunSpec({
        test("VERSION fits the disclaimer_version VARCHAR(20) column (V33__bank_account_fints.sql)") {
            (FinTsComplianceDisclaimer.VERSION.length <= 20) shouldBe true
        }

        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("${FinTsComplianceDisclaimer.VERSION}\n${FinTsComplianceDisclaimer.TEXT}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            FinTsComplianceDisclaimer.SHA256 shouldBe recomputed
            FinTsComplianceDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            FinTsComplianceDisclaimer.matches(
                version = FinTsComplianceDisclaimer.VERSION,
                sha256 = FinTsComplianceDisclaimer.SHA256,
            ) shouldBe true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            FinTsComplianceDisclaimer.matches(version = "2020-01-01.v0", sha256 = FinTsComplianceDisclaimer.SHA256) shouldBe false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + FinTsComplianceDisclaimer.SHA256.drop(1)
            FinTsComplianceDisclaimer.matches(version = FinTsComplianceDisclaimer.VERSION, sha256 = tampered) shouldBe false
        }

        test("matches() rejects a malformed (non-hex / odd-length / empty) hash without throwing") {
            FinTsComplianceDisclaimer.matches(version = FinTsComplianceDisclaimer.VERSION, sha256 = "not-a-hex-digest") shouldBe false
            FinTsComplianceDisclaimer.matches(version = FinTsComplianceDisclaimer.VERSION, sha256 = "abc") shouldBe false
            FinTsComplianceDisclaimer.matches(version = FinTsComplianceDisclaimer.VERSION, sha256 = "") shouldBe false
        }

        test("TEXT names every risk area the disclaimer is required to cover") {
            val text = FinTsComplianceDisclaimer.TEXT
            text shouldContain "IHR EIGENES"
            text shouldContain "Kontoinformation"
            text shouldContain "GCM"
            text shouldContain "Pinning"
            text shouldContain "camt.052"
        }

        test("TEXT explicitly disclaims automated legal advice and assigns responsibility to the board") {
            val text = FinTsComplianceDisclaimer.TEXT
            text shouldContain "KEINE Rechtsberatung"
            text shouldContain "Vorstand"
        }
    })
