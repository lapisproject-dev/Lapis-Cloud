package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.encounter.encounterConsentFor
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.166 -- the entry notice of a room with a map: the code-owned map paragraph is the LAST key point and the end of the full text, the label carries
 * the system suffix (`+map`, `+rec+map` with the recording option), and the hash covers label and text. Label and hash are pinned as LITERALS (a changed
 * character needs a NEW label). **The paragraph is a DRAFT without legal review** -- the pins make a change visible, they do not approve the wording.
 */
class EncounterMapConsentTextTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        val paragraph = EncounterConsentLayout.mapParagraph(AgreementTextKind.ENC_ASSEM)

        fun served(
            recording: Boolean,
            map: Boolean,
        ) = transaction { encounterConsentFor(profile = EncounterProfile.ASSEMBLY, pulpitRecordingAllowed = recording, mapEnabled = map) }

        test("the map paragraph is pinned word for word (the vault draft)") {
            paragraph shouldBe
                "Karte und Position. In diesem Raum gibt es eine Karte. Andere Anwesende sehen darauf, wo Sie " +
                "sich befinden und in welche Richtung Sie gehen, dazu Ihren Namen und Ihre Initialen. Ihre " +
                "Position wird nur im Arbeitsspeicher des Servers gehalten, nicht in der Datenbank, nicht in " +
                "Protokollen und nicht in Exporten. Sie verschwindet, wenn Sie den Raum verlassen, wenn die " +
                "Veranstaltung endet oder wenn der Server neu startet. Es gibt keinen Verlauf Ihrer Wege und " +
                "keine Liste, wer wann wo war."
        }

        test("a room with a map: label +map, the paragraph is the last key point and the end of the text, the hash covers both") {
            val plain = served(recording = false, map = false)
            val withMap = served(recording = false, map = true)
            withMap.version shouldBe plain.version + "+map"
            withMap.keyPoints.last() shouldBe paragraph
            withMap.keyPoints.dropLast(1) shouldBe plain.keyPoints
            withMap.text shouldBe plain.text + "\n\n" + paragraph
            withMap.text shouldEndWith paragraph
            withMap.sha256 shouldBe AgreementTextHash.of(versionLabel = withMap.version, body = withMap.text)
            (withMap.sha256 == plain.sha256) shouldBe false
            // the recording sentence of the placeholder stays true: nothing is recorded in this room
            withMap.text shouldNotContain "aufgezeichnet werden"
        }

        test("recording and map together: label +rec+map, recording paragraph first, then the map paragraph") {
            val both = served(recording = true, map = true)
            val plain = served(recording = false, map = false)
            both.version shouldBe plain.version + "+rec+map"
            both.keyPoints.takeLast(2) shouldBe listOf(EncounterConsentLayout.recordingParagraph(AgreementTextKind.ENC_ASSEM), paragraph)
            both.text shouldEndWith "\n\n" + paragraph
            both.sha256 shouldBe AgreementTextHash.of(versionLabel = both.version, body = both.text)
            (both.version.length <= EncounterConsentLayout.ENC_ACK_LABEL_MAX) shouldBe true
        }

        test("without both options the served notice is byte-identical to before") {
            val plain = served(recording = false, map = false)
            plain.version shouldBe EncounterAssemblyConsentDefault.VERSION
            plain.sha256 shouldBe EncounterAssemblyConsentDefault.SHA256
            val rec = served(recording = true, map = false)
            rec.version shouldBe EncounterAssemblyConsentDefault.VERSION + "+rec"
        }

        test("label and hash of the map variant are pinned as literals") {
            val withMap = served(recording = false, map = true)
            withMap.version shouldBe "2026-10-17.assembly.v2+map"
            withMap.sha256 shouldBe "b7572ecf6bf53cd5a7da2e0975eee945400f74cf0386903cca252f3427c7612b"
            val both = served(recording = true, map = true)
            both.version shouldBe "2026-10-17.assembly.v2+rec+map"
            both.sha256 shouldBe "3861a98edc4ae9915cf6f09f9cb22a16f16ee13a5de7fa3ca79bc498e9df8d04"
        }

        test("the church profile has no map text: fail closed") {
            runCatching { transaction { encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE, mapEnabled = true) } }
                .exceptionOrNull()
                .let { (it is ServiceBusyException) shouldBe true }
        }

        test("the reserved labels hold every suffix variant, and a label of the longest kind fits the column") {
            val reserved = EncounterConsentLabels.reserved
            val base = EncounterAssemblyConsentDefault.VERSION
            reserved shouldContain base
            reserved shouldContain base + "+rec"
            reserved shouldContain base + "+map"
            reserved shouldContain base + "+rec+map"
            EncounterConsentLabels.withSystemSuffixes("x") shouldBe listOf("x", "x+rec", "x+map", "x+rec+map")
            (("a".repeat(EncounterConsentLayout.ENC_LABEL_MAX)) + "+rec+map").length shouldBe EncounterConsentLayout.ENC_ACK_LABEL_MAX
        }
    })
