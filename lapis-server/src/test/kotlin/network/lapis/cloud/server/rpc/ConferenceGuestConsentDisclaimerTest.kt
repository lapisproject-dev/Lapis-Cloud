package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest

/**
 * Pure tests of [ConferenceGuestConsentDisclaimer] -- no DB access. Mirrors
 * [AuctionComplianceDisclaimerTest]'s shape (hash stability, tamper rejection), extended for the
 * Wave 5 design review's D7 "two-layer disclosure" structural invariant (TEXT is composed from
 * HEADLINE/KEY_POINTS/DETAIL, never hand-duplicated) and D15 (no hardcoded organization name).
 */
class ConferenceGuestConsentDisclaimerTest :
    FunSpec({
        test("SHA256 is a stable, independently-recomputable digest of VERSION + TEXT") {
            val recomputed =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        "${ConferenceGuestConsentDisclaimer.VERSION}\n${ConferenceGuestConsentDisclaimer.TEXT}"
                            .toByteArray(Charsets.UTF_8),
                    ).joinToString("") { "%02x".format(it) }
            ConferenceGuestConsentDisclaimer.SHA256 shouldBe recomputed
            ConferenceGuestConsentDisclaimer.SHA256.length shouldBe 64
        }

        test("matches() is true only for the exact current version+hash pair") {
            ConferenceGuestConsentDisclaimer.matches(
                ConferenceGuestConsentDisclaimer.VERSION,
                ConferenceGuestConsentDisclaimer.SHA256,
            ) shouldBe true
        }

        test("matches() rejects a stale/wrong version even with the correct hash") {
            ConferenceGuestConsentDisclaimer.matches("2020-01-01.v0", ConferenceGuestConsentDisclaimer.SHA256) shouldBe false
        }

        test("matches() rejects a tampered hash even with the correct version") {
            val tampered = "0" + ConferenceGuestConsentDisclaimer.SHA256.drop(1)
            ConferenceGuestConsentDisclaimer.matches(ConferenceGuestConsentDisclaimer.VERSION, tampered) shouldBe false
        }

        test("matches() rejects a malformed (non-hex / wrong-length) hash without throwing") {
            ConferenceGuestConsentDisclaimer.matches(ConferenceGuestConsentDisclaimer.VERSION, "not-a-hex-digest") shouldBe false
            ConferenceGuestConsentDisclaimer.matches(ConferenceGuestConsentDisclaimer.VERSION, "") shouldBe false
            ConferenceGuestConsentDisclaimer.matches(ConferenceGuestConsentDisclaimer.VERSION, "abc") shouldBe false
        }

        test("TEXT names the load-bearing disclosure topics") {
            val text = ConferenceGuestConsentDisclaimer.TEXT
            text shouldContain "Aufzeichnung"
            text shouldContain "Live"
            text shouldContain "Datenschutzerklärung"
            text shouldContain "Heimserver"
        }

        test("TEXT does NOT hardcode an organization name -- see class KDoc D15") {
            val text = ConferenceGuestConsentDisclaimer.TEXT
            text shouldNotContain "Lapis Cloud"
            text shouldNotContain "Partei der Vernunft"
        }

        test("KEY_POINTS has exactly two entries, both appearing verbatim in TEXT (D7 structural drift-proofing)") {
            ConferenceGuestConsentDisclaimer.KEY_POINTS shouldHaveSize 2
            ConferenceGuestConsentDisclaimer.KEY_POINTS.forEach { point ->
                ConferenceGuestConsentDisclaimer.TEXT shouldContain point
            }
        }

        test("TEXT starts with HEADLINE (composed, not hand-duplicated -- D7)") {
            ConferenceGuestConsentDisclaimer.TEXT.startsWith(ConferenceGuestConsentDisclaimer.HEADLINE) shouldBe true
        }

        test("TEXT uses correct German orthography (real umlauts), not ASCII transliteration -- D10") {
            val text = ConferenceGuestConsentDisclaimer.TEXT
            text shouldContain "ä"
            text shouldNotContain "Datenschutzerklaerung"
            text shouldNotContain "fuer"
        }
    })
