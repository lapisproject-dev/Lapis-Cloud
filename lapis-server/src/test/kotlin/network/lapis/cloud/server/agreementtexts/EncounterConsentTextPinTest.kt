package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.encounter.encounterConsentFor
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EncounterProfile
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.148 -- the built-in entry notices of the encounter rooms: label and hash pinned as LITERALS (a changed character needs a NEW label, never a
 * new literal), the paragraph rule holds, and the notice SAYS what the server really shows to everybody present. The negative pin guards the old
 * claim "others see at most your name", which was false once seats, tables, guest status, reactions, chat and the floor existed.
 */
class EncounterConsentTextPinTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        data class Variant(
            val profile: EncounterProfile,
            val recording: Boolean,
        )

        val variants = EncounterProfile.entries.flatMap { listOf(Variant(it, false), Variant(it, true)) }

        fun served(v: Variant) = transaction { encounterConsentFor(profile = v.profile, pulpitRecordingAllowed = v.recording) }

        test("label and hash of both placeholders are pinned") {
            EncounterChurchConsentDefault.VERSION shouldBe "2026-10-17.v2"
            EncounterChurchConsentDefault.SHA256 shouldBe "24602ba6d26824f4981e2d2c5ddb7a449a56de6ec673bd732eba1b8697a5cd10"
            EncounterAssemblyConsentDefault.VERSION shouldBe "2026-10-17.assembly.v2"
            EncounterAssemblyConsentDefault.SHA256 shouldBe "9b42ff465de024f36ebd120ff353a18e8f19d112948e76beed07439e91b2cb0c"
            EncounterChurchConsentDefault.SHA256 shouldBe
                AgreementTextHash.of(versionLabel = EncounterChurchConsentDefault.VERSION, body = EncounterChurchConsentDefault.TEXT)
        }

        test("the placeholders follow the paragraph rule and keep every key point within its limit") {
            listOf(
                EncounterChurchConsentDefault.VERSION to EncounterChurchConsentDefault.TEXT,
                EncounterAssemblyConsentDefault.VERSION to EncounterAssemblyConsentDefault.TEXT,
            ).forEach { (label, text) ->
                EncounterConsentLayout.violation(label = label, body = text) shouldBe null
                (label.length + EncounterConsentLayout.RECORDING_SUFFIX.length <= 50) shouldBe true
            }
        }

        test("every served variant says what everybody present sees and hears of the guest") {
            variants.forEach { v ->
                val c = served(v)
                val head = c.headline + "\n" + c.keyPoints.joinToString("\n")
                head shouldContain "Alle Anwesenden sehen Ihren Namen"
                head shouldContain "wo Sie sitzen"
                head shouldContain "Initialen"
                head shouldContain "an welchem Tisch und auf welchem Platz"
                head shouldContain "an alle Geräte im Raum übertragen"
                head shouldContain "Ihre Reaktionen"
                head shouldContain "Ihr Bild wird nie gesendet"
                head shouldContain "wenn Sie Ihr Mikrofon selbst einschalten"
                head shouldContain "wenn Ihnen das Wort erteilt wird"
                head shouldContain "Chatnachrichten"
                head shouldContain "nicht gespeichert"
                head shouldContain "keine Teilnahmeliste"
                c.text shouldContain "Art. 9"
                c.text shouldContain "Entwurf"
            }
        }

        test("the old claim that others see at most the name is in no served variant") {
            variants.forEach { v ->
                val c = served(v)
                c.text shouldNotContain "höchstens Ihren Namen"
                c.keyPoints.forEach { it shouldNotContain "höchstens Ihren Namen" }
            }
        }

        test(
            "without recording the placeholder says nothing is recorded; with recording the code removes that and appends its own sentence last",
        ) {
            EncounterProfile.entries.forEach { profile ->
                val plain = served(Variant(profile, false))
                plain.text shouldContain EncounterConsentLayout.NO_RECORDING_SENTENCE
                plain.version.endsWith(EncounterConsentLayout.RECORDING_SUFFIX) shouldBe false

                val rec = served(Variant(profile, true))
                val paragraph =
                    EncounterConsentLayout.recordingParagraph(EncounterConsentLabels.kindOf(profile))
                rec.text shouldNotContain EncounterConsentLayout.NO_RECORDING_SENTENCE
                rec.text shouldEndWith paragraph
                rec.keyPoints.last() shouldBe paragraph
                rec.keyPoints.size shouldBe plain.keyPoints.size + 1
                rec.version shouldBe plain.version + EncounterConsentLayout.RECORDING_SUFFIX
                // no double blank, no stray space where the sentence was cut out (the leading space belongs to the pattern)
                rec.text shouldNotContain "  "
                rec.keyPoints.forEach { it shouldNotContain "  " }
                (rec.sha256 == plain.sha256) shouldBe false
                rec.sha256 shouldBe AgreementTextHash.of(versionLabel = rec.version, body = rec.text)
            }
        }

        test("the recording sentence agrees with the room notices: the pulpit may be recorded, the congregation is not") {
            EncounterConsentLayout.recordingParagraph(
                network.lapis.cloud.shared.domain.AgreementTextKind.ENC_CHURCH,
            ) shouldContain "nie aufgezeichnet"
            RecRoomChurchDefault.TEXT shouldContain "Kanzel wird aufgezeichnet, die Gemeinde nicht"
            RecRoomAssemblyDefault.TEXT shouldContain "Podium wird aufgezeichnet, die Teilnehmenden nicht"
            EncounterConsentLayout.recordingParagraph(
                network.lapis.cloud.shared.domain.AgreementTextKind.ENC_ASSEM,
            ) shouldContain "Podium"
        }

        test("the assembly text says assembly and podium, never service and pulpit") {
            val c = served(Variant(EncounterProfile.ASSEMBLY, false))
            c.headline shouldBe "Sie nehmen an einer Versammlung teil."
            c.text shouldContain "Übertragen wird das Podium."
            c.text shouldNotContain "Kanzel"
            c.text shouldNotContain "Gottesdienst"
        }
    })
