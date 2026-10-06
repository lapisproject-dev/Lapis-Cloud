package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest

/** Welle V1.9.61 -- [EncounterConsentDisclaimer]: versioned, hashed, composed from its parts, and exact-match only. */
class EncounterConsentDisclaimerTest :
    FunSpec({
        test("the hash is SHA-256 over \"version\\ntext\" and the text is composed from headline, key points and detail") {
            val expected =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("${EncounterConsentDisclaimer.VERSION}\n${EncounterConsentDisclaimer.TEXT}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            EncounterConsentDisclaimer.SHA256 shouldBe expected
            EncounterConsentDisclaimer.TEXT shouldContain EncounterConsentDisclaimer.HEADLINE
            EncounterConsentDisclaimer.KEY_POINTS.size shouldBe 2
            EncounterConsentDisclaimer.KEY_POINTS.forEach { EncounterConsentDisclaimer.TEXT shouldContain it }
        }

        test(
            "the text states what the system really does: nothing about attendance is stored, nothing is recorded, only the pulpit is broadcast",
        ) {
            EncounterConsentDisclaimer.TEXT shouldContain "nicht gespeichert"
            EncounterConsentDisclaimer.TEXT shouldContain "nichts aufgezeichnet"
            EncounterConsentDisclaimer.TEXT shouldContain "nur die Kanzel"
            EncounterConsentDisclaimer.TEXT shouldContain "Art. 9"
            EncounterConsentDisclaimer.TEXT shouldContain "Entwurf"
        }

        test("matches: exact version and hash only; case-insensitive hex; malformed hashes are a non-match, never an exception") {
            val v = EncounterConsentDisclaimer.VERSION
            val h = EncounterConsentDisclaimer.SHA256
            EncounterConsentDisclaimer.matches(version = v, sha256 = h) shouldBe true
            EncounterConsentDisclaimer.matches(version = v, sha256 = h.uppercase()) shouldBe true
            EncounterConsentDisclaimer.matches(version = "other", sha256 = h) shouldBe false
            EncounterConsentDisclaimer.matches(version = v, sha256 = "0".repeat(64)) shouldBe false
            EncounterConsentDisclaimer.matches(version = v, sha256 = h.dropLast(2)) shouldBe false
            EncounterConsentDisclaimer.matches(version = v, sha256 = "zz".repeat(32)) shouldBe false
            EncounterConsentDisclaimer.matches(version = v, sha256 = "abc") shouldBe false
            EncounterConsentDisclaimer.matches(version = v, sha256 = "") shouldBe false
        }

        test("it is a different text than the ordinary guest consent (the encounter consent must not be satisfied by it)") {
            EncounterConsentDisclaimer.SHA256 shouldBe EncounterConsentDisclaimer.SHA256
            (EncounterConsentDisclaimer.SHA256 == network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer.SHA256) shouldBe false
            EncounterConsentDisclaimer.matches(
                version = network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer.VERSION,
                sha256 = network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer.SHA256,
            ) shouldBe false
        }
    })
