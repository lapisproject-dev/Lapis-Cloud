package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterProfile

/**
 * V1.9.143 -- the four placeholders of the two pulpit-recording kinds: label and hash are pinned as LITERALS (pattern of [AgreementTextBuiltinPinTest]). A
 * changed character changes the hash and fails this test; the answer is never to update the literal but to publish a new version through the
 * administration. [BuiltinAgreementTexts] hands out the right one per kind and room profile.
 */
class PulpitRecordingBuiltinTextPinTest :
    FunSpec({
        test("the four placeholders keep their label and hash") {
            RecRoomChurchDefault.VERSION shouldBe "2026-10-16.rec-room.v1"
            RecRoomChurchDefault.SHA256 shouldBe "116f1e0b63e632f63422468dcada03f1afb2a7285933ecefb2a8bf42b0bcaa13"
            RecRoomAssemblyDefault.VERSION shouldBe "2026-10-16.rec-room-assembly.v1"
            RecRoomAssemblyDefault.SHA256 shouldBe "163a7af23f70f820bb3e05484a3d1345c40c19e01e0f742541b8c4f424b9e213"
            RecPulpitChurchDefault.VERSION shouldBe "2026-10-16.rec-pulpit.v1"
            RecPulpitChurchDefault.SHA256 shouldBe "0b4a96f5d640d637430aa85fd46164d78d315f1019dd35bfacdee56b4e7a22c1"
            RecPulpitAssemblyDefault.VERSION shouldBe "2026-10-16.rec-pulpit-assembly.v1"
            RecPulpitAssemblyDefault.SHA256 shouldBe "e96f1eb8dd4f563782624fd9aaf6f4e3bca6fa129caff1b765331326de06e9e5"
        }

        test("the texts say the one thing they are for: the pulpit (podium) is recorded, the congregation (participants) is not") {
            RecRoomChurchDefault.TEXT shouldBe "Die Kanzel wird aufgezeichnet, die Gemeinde nicht."
            RecRoomAssemblyDefault.TEXT shouldBe "Das Podium wird aufgezeichnet, die Teilnehmenden nicht."
            RecPulpitChurchDefault.TEXT.contains("Die Gemeinde wird nicht aufgezeichnet.") shouldBe true
            RecPulpitAssemblyDefault.TEXT.contains("Die Teilnehmenden werden nicht aufgezeichnet.") shouldBe true
        }

        test("each hash is the formula of every other placeholder: sha256 of the label, a line feed and the text") {
            AgreementTextHash.of(versionLabel = RecRoomChurchDefault.VERSION, body = RecRoomChurchDefault.TEXT) shouldBe
                RecRoomChurchDefault.SHA256
            AgreementTextHash.of(versionLabel = RecPulpitAssemblyDefault.VERSION, body = RecPulpitAssemblyDefault.TEXT) shouldBe
                RecPulpitAssemblyDefault.SHA256
        }

        test(
            "BuiltinAgreementTexts picks the placeholder per profile, ignores the profile for the registration kinds, and lists both for a REC kind",
        ) {
            BuiltinAgreementTexts.of(kind = AgreementTextKind.REC_ROOM, profile = EncounterProfile.CHURCH_SERVICE).versionLabel shouldBe
                RecRoomChurchDefault.VERSION
            BuiltinAgreementTexts.of(kind = AgreementTextKind.REC_ROOM, profile = EncounterProfile.ASSEMBLY).versionLabel shouldBe
                RecRoomAssemblyDefault.VERSION
            BuiltinAgreementTexts.of(kind = AgreementTextKind.REC_PULPIT, profile = EncounterProfile.CHURCH_SERVICE).versionLabel shouldBe
                RecPulpitChurchDefault.VERSION
            BuiltinAgreementTexts.of(kind = AgreementTextKind.REC_PULPIT, profile = EncounterProfile.ASSEMBLY).versionLabel shouldBe
                RecPulpitAssemblyDefault.VERSION
            BuiltinAgreementTexts.of(kind = AgreementTextKind.MEMBERSHIP, profile = EncounterProfile.ASSEMBLY) shouldBe
                BuiltinAgreementTexts.of(kind = AgreementTextKind.MEMBERSHIP)
            BuiltinAgreementTexts.allOf(AgreementTextKind.REC_ROOM).size shouldBe 2
            BuiltinAgreementTexts.allOf(AgreementTextKind.FRIEND).size shouldBe 1
        }
    })
