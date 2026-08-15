package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/**
 * Pure tests of [AuctionComplianceDisclaimer] -- no DB access. Verifies the hash is stable and
 * self-consistent, [AuctionComplianceDisclaimer.matches] rejects any tamper, and [AuctionComplianceDisclaimer.TEXT]
 * actually names every risk area the V0.6.2 planning discussion identified.
 */
class AuctionComplianceDisclaimerTest :
    FunSpec({
        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("${AuctionComplianceDisclaimer.VERSION}\n${AuctionComplianceDisclaimer.TEXT}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            AuctionComplianceDisclaimer.SHA256 shouldBe recomputed
            AuctionComplianceDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            AuctionComplianceDisclaimer.matches(
                version = AuctionComplianceDisclaimer.VERSION,
                sha256 = AuctionComplianceDisclaimer.SHA256,
            ) shouldBe
                true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            AuctionComplianceDisclaimer.matches(version = "2020-01-01.v0", sha256 = AuctionComplianceDisclaimer.SHA256) shouldBe false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + AuctionComplianceDisclaimer.SHA256.drop(1)
            AuctionComplianceDisclaimer.matches(version = AuctionComplianceDisclaimer.VERSION, sha256 = tampered) shouldBe false
        }

        test("matches() rejects a malformed (non-hex / wrong-length) hash without throwing") {
            AuctionComplianceDisclaimer.matches(version = AuctionComplianceDisclaimer.VERSION, sha256 = "not-a-hex-digest") shouldBe false
            AuctionComplianceDisclaimer.matches(version = AuctionComplianceDisclaimer.VERSION, sha256 = "") shouldBe false
            AuctionComplianceDisclaimer.matches(version = AuctionComplianceDisclaimer.VERSION, sha256 = "abc") shouldBe false
        }

        test("TEXT names every risk area the disclaimer is required to cover") {
            val text = AuctionComplianceDisclaimer.TEXT
            text shouldContain "ZAG"
            text shouldContain "MiCAR"
            text shouldContain "Gewerbeordnung"
            text shouldContain "UStG"
            text shouldContain "EStG"
            text shouldContain "GewStG"
            text shouldContain "Verbraucherschutz"
            text shouldContain "PartG"
            text shouldContain "GwG"
        }

        test("TEXT explicitly disclaims automated legal advice and assigns responsibility to the operator") {
            val text = AuctionComplianceDisclaimer.TEXT
            text shouldContain "KEINE Rechtsberatung"
            text shouldContain "Betreiber"
        }
    })
