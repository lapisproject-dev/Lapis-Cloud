package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.EncounterProfile

/**
 * V1.9.143 -- the Art. 9 consent of a room whose pulpit MAY be recorded. The variants tell the truth about the recording (the pulpit may be recorded, the
 * person's own picture and sound never are) and keep every statement about attendance; the two old texts stay byte for byte as they were (their
 * acknowledgments are proof); the room option, not the instance switch, selects the variant.
 */
class PulpitRecordingConsentTextTest :
    FunSpec({
        test("the old consent texts are unchanged: version, hash and the promise of no recording") {
            EncounterConsentDisclaimer.VERSION shouldBe "2026-10-06.v1"
            EncounterAssemblyConsentDisclaimer.VERSION shouldBe "2026-10-07.assembly.v1"
            EncounterConsentDisclaimer.TEXT shouldContain "Es wird nichts aufgezeichnet."
            EncounterAssemblyConsentDisclaimer.TEXT shouldContain "Es wird nichts aufgezeichnet."
        }

        test(
            "the recording variants say that the pulpit may be recorded and never the person's own picture, and drop the promise of no recording",
        ) {
            EncounterRecordingConsentDisclaimer.TEXT shouldContain "kann die Kanzel aufgezeichnet werden"
            EncounterRecordingConsentDisclaimer.TEXT shouldContain "nie aufgezeichnet"
            EncounterRecordingConsentDisclaimer.TEXT shouldNotContain "Es wird nichts aufgezeichnet."
            EncounterAssemblyRecordingConsentDisclaimer.TEXT shouldContain "kann das Podium aufgezeichnet werden"
            EncounterAssemblyRecordingConsentDisclaimer.TEXT shouldNotContain "Es wird nichts aufgezeichnet."
            // the statements about attendance are the old ones, word for word
            EncounterRecordingConsentDisclaimer.KEY_POINTS[0] shouldBe EncounterConsentDisclaimer.KEY_POINTS[0]
            EncounterAssemblyRecordingConsentDisclaimer.KEY_POINTS[0] shouldBe EncounterAssemblyConsentDisclaimer.KEY_POINTS[0]
            EncounterRecordingConsentDisclaimer.TEXT shouldContain "Besondere Kategorie von Daten"
            EncounterRecordingConsentDisclaimer.TEXT shouldContain "Dieser Hinweis ist ein Entwurf und stellt keine Rechtsberatung dar."
        }

        test("each variant is its own version and hash, the hash is the formula of every other consent, and it matches only itself") {
            val all =
                listOf(
                    EncounterConsentDisclaimer,
                    EncounterAssemblyConsentDisclaimer,
                    EncounterRecordingConsentDisclaimer,
                    EncounterAssemblyRecordingConsentDisclaimer,
                )
            all.map { it.version }.toSet().size shouldBe 4
            all.map { it.sha256 }.toSet().size shouldBe 4
            EncounterRecordingConsentDisclaimer.SHA256 shouldBe
                ConsentHashing.sha256Hex("${EncounterRecordingConsentDisclaimer.VERSION}\n${EncounterRecordingConsentDisclaimer.TEXT}")
            EncounterRecordingConsentDisclaimer.matches(
                version = EncounterRecordingConsentDisclaimer.VERSION,
                sha256 = EncounterRecordingConsentDisclaimer.SHA256,
            ) shouldBe
                true
            EncounterRecordingConsentDisclaimer.matches(
                version = EncounterConsentDisclaimer.VERSION,
                sha256 = EncounterConsentDisclaimer.SHA256,
            ) shouldBe
                false
        }

        test("version and hash of the two recording variants are pinned (a changed character needs a NEW version, never a new literal)") {
            EncounterRecordingConsentDisclaimer.VERSION shouldBe "2026-10-16.rec.v1"
            EncounterRecordingConsentDisclaimer.SHA256 shouldBe "f19727bf7e154fbb480c150c2b39cfae33d81b184571c019b5672cd8f90630a9"
            EncounterAssemblyRecordingConsentDisclaimer.VERSION shouldBe "2026-10-16.assembly-rec.v1"
            EncounterAssemblyRecordingConsentDisclaimer.SHA256 shouldBe "93cf8c4522dafb63493b0a39b86afbae64ddfa5675089b0f4a52ce3799161879"
        }

        test("the room option selects the variant, per profile; without the option it is the old text") {
            encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE) shouldBe EncounterConsentDisclaimer
            encounterConsentFor(profile = EncounterProfile.ASSEMBLY) shouldBe EncounterAssemblyConsentDisclaimer
            encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE, pulpitRecordingAllowed = true) shouldBe
                EncounterRecordingConsentDisclaimer
            encounterConsentFor(profile = EncounterProfile.ASSEMBLY, pulpitRecordingAllowed = true) shouldBe
                EncounterAssemblyRecordingConsentDisclaimer
        }
    })
