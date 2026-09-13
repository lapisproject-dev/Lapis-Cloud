package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/**
 * Pure tests of [VolunteerAllowanceCapDisclaimer] -- no DB access. Mirrors
 * [AuctionComplianceDisclaimerTest]'s structure exactly.
 */
class VolunteerAllowanceCapDisclaimerTest :
    FunSpec({
        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        "${VolunteerAllowanceCapDisclaimer.VERSION}\n${VolunteerAllowanceCapDisclaimer.TEXT}".toByteArray(Charsets.UTF_8),
                    ).joinToString("") { "%02x".format(it) }
            VolunteerAllowanceCapDisclaimer.SHA256 shouldBe recomputed
            VolunteerAllowanceCapDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            VolunteerAllowanceCapDisclaimer.matches(
                version = VolunteerAllowanceCapDisclaimer.VERSION,
                sha256 = VolunteerAllowanceCapDisclaimer.SHA256,
            ) shouldBe true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            VolunteerAllowanceCapDisclaimer.matches(version = "2020-01-01.v0", sha256 = VolunteerAllowanceCapDisclaimer.SHA256) shouldBe
                false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + VolunteerAllowanceCapDisclaimer.SHA256.drop(1)
            VolunteerAllowanceCapDisclaimer.matches(version = VolunteerAllowanceCapDisclaimer.VERSION, sha256 = tampered) shouldBe false
        }

        test("matches() is case-insensitive for hex casing of a correct hash") {
            VolunteerAllowanceCapDisclaimer.matches(
                version = VolunteerAllowanceCapDisclaimer.VERSION,
                sha256 = VolunteerAllowanceCapDisclaimer.SHA256.uppercase(),
            ) shouldBe true
        }

        test("matches() rejects a malformed (non-hex / wrong-length) hash without throwing") {
            VolunteerAllowanceCapDisclaimer.matches(version = VolunteerAllowanceCapDisclaimer.VERSION, sha256 = "not-a-hex-digest") shouldBe
                false
            VolunteerAllowanceCapDisclaimer.matches(version = VolunteerAllowanceCapDisclaimer.VERSION, sha256 = "") shouldBe false
            VolunteerAllowanceCapDisclaimer.matches(version = VolunteerAllowanceCapDisclaimer.VERSION, sha256 = "abc") shouldBe false
        }

        test("TEXT names the lohnsteuer/other-organizations risk areas the disclaimer is required to cover") {
            // Whitespace-normalized (the raw TEXT wraps sentences across lines inside its
            // triple-quoted literal, so a phrase-level shouldContain must not be sensitive to
            // exactly where a line break happens to fall).
            val text = VolunteerAllowanceCapDisclaimer.TEXT.replace(Regex("\\s+"), " ")
            text shouldContain "lohnsteuer"
            text shouldContain "sozialversicherungspflichtig"
            text shouldContain "Andere Organisationen sind Lapis Cloud nicht bekannt"
        }

        test("TEXT explicitly disclaims automated legal advice and assigns responsibility to the operator") {
            val text = VolunteerAllowanceCapDisclaimer.TEXT
            text shouldContain "KEINE Rechtsberatung"
            text shouldContain "Betreiber"
        }
    })
