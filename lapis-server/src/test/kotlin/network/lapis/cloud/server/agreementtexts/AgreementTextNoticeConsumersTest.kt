package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.encounter.ResolvedEncounterConsent
import network.lapis.cloud.server.encounter.encounterConsentFor
import network.lapis.cloud.server.events.EventCaptureRequirement
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EncounterProfile
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val MARKDOWN_NOTICE =
    "**Wichtiger** Hinweis\n\nSie sind *hier* willkommen.\n\nNichts wird [gespeichert](https://example.org).\n\n" +
        "Sie können jederzeit gehen.\n\n" +
        "## Einzelheiten\n\n- Punkt eins\n- Punkt zwei\n\n<script>alert(1)</script>"

/**
 * V1.9.196 -- the notice consumers (encounter entry notice, event capture consent): for a MARKDOWN text the headline and key points are PLAIN text
 * (Markdown characters removed by the server), the full text arrives as server-rendered HTML, the hash stays over the source text (and, with a
 * recording or map paragraph, over the composed source), and a plain text behaves exactly as before.
 */
class AgreementTextNoticeConsumersTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        test("encounter notice: MARKDOWN gives a plain headline and key points, HTML for the full text, and the hash over the source") {
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.ENC_CHURCH,
                    label = "md.v1",
                    body = MARKDOWN_NOTICE,
                    format = AgreementTextFormat.MARKDOWN,
                )
            val consent = transaction { encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE) } as ResolvedEncounterConsent
            consent.format shouldBe AgreementTextFormat.MARKDOWN
            consent.sha256 shouldBe sha
            consent.version shouldBe "md.v1"
            consent.text shouldBe MARKDOWN_NOTICE
            consent.headline shouldBe "Wichtiger Hinweis"
            consent.keyPoints[0] shouldBe "Sie sind hier willkommen."
            consent.keyPoints.forEach {
                it shouldNotContain "*"
                it shouldNotContain "]("
            }
            consent.renderedHtml!! shouldContain "<h3>Einzelheiten</h3>"
            consent.renderedHtml!! shouldContain "<li>Punkt eins</li>"
            consent.renderedHtml!! shouldContain "&lt;script&gt;"
            consent.renderedHtml!! shouldNotContain "<script"
        }

        test("encounter notice with a recording paragraph: the composed text is rendered and hashed, the label gets +rec") {
            AgreementTextTestState.publishDirect(
                kind = AgreementTextKind.ENC_CHURCH,
                label = "md.v1",
                body = MARKDOWN_NOTICE,
                format = AgreementTextFormat.MARKDOWN,
            )
            val consent = transaction { encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE, pulpitRecordingAllowed = true) }
            val recording = EncounterConsentLayout.recordingParagraph(AgreementTextKind.ENC_CHURCH)
            consent.version shouldBe "md.v1${EncounterConsentLayout.RECORDING_SUFFIX}"
            consent.text shouldBe MARKDOWN_NOTICE + "\n\n" + recording
            consent.sha256 shouldBe AgreementTextHash.of(versionLabel = consent.version, body = consent.text)
            consent.keyPoints.last() shouldBe recording
            consent.renderedHtml!! shouldContain recording
        }

        test(
            "encounter notice with a recording paragraph: a text of MAX_CHARS is rendered completely, including the code-owned paragraph",
        ) {
            val head = "A\n\nB\n\nC\n\nD\n\n"
            val body = head + "y".repeat(AgreementTextRules.MAX_CHARS - head.length)
            body.length shouldBe AgreementTextRules.MAX_CHARS
            AgreementTextTestState.publishDirect(
                kind = AgreementTextKind.ENC_CHURCH,
                label = "max.v1",
                body = body,
                format = AgreementTextFormat.MARKDOWN,
            )
            val consent = transaction { encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE, pulpitRecordingAllowed = true) }
            consent.text.length shouldBeGreaterThan AgreementTextRules.MAX_CHARS
            consent.renderedHtml!! shouldContain EncounterConsentLayout.recordingParagraph(AgreementTextKind.ENC_CHURCH)
        }

        test("encounter notice: a plain text is delivered exactly as before, without HTML") {
            AgreementTextTestState.publishDirect(
                kind = AgreementTextKind.ENC_ASSEM,
                label = "plain.v1",
                body = "Titel mit **Sternen**\n\nEins.\n\nZwei.\n\nDrei.\n\nVolltext.",
            )
            val consent = transaction { encounterConsentFor(profile = EncounterProfile.ASSEMBLY) }
            consent.format shouldBe AgreementTextFormat.PLAIN
            consent.renderedHtml shouldBe null
            consent.headline shouldBe "Titel mit **Sternen**"
            consent.keyPoints.take(3) shouldBe listOf("Eins.", "Zwei.", "Drei.")
        }

        test("the built-in encounter placeholder is plain and has no HTML") {
            val consent = transaction { encounterConsentFor(profile = EncounterProfile.CHURCH_SERVICE) }
            consent.format shouldBe AgreementTextFormat.PLAIN
            consent.renderedHtml shouldBe null
        }

        test("event capture notice: MARKDOWN gives plain summary, HTML full text and the source hash; plain stays as it was") {
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.EVENT_REC,
                    label = "md.v1",
                    body = MARKDOWN_NOTICE,
                    format = AgreementTextFormat.MARKDOWN,
                )
            val notice = transaction { EventCaptureRequirement.noticeDto() }
            notice.format shouldBe AgreementTextFormat.MARKDOWN
            notice.sha256 shouldBe sha
            notice.headline shouldBe "Wichtiger Hinweis"
            notice.keyPoints.first() shouldBe "Sie sind hier willkommen."
            notice.fullText shouldBe MARKDOWN_NOTICE
            notice.renderedHtml!! shouldContain "<h3>Einzelheiten</h3>"
            notice.renderedHtml!! shouldNotContain "<script"
            notice.placeholder shouldBe false

            AgreementTextTestState.reset()
            AgreementTextTestState.publishDirect(
                kind = AgreementTextKind.EVENT_REC,
                label = "plain.v1",
                body = "Titel\n\n**Eins**.\n\nZwei.\n\nDrei.\n\nVolltext.",
            )
            val plain = transaction { EventCaptureRequirement.noticeDto() }
            plain.format shouldBe AgreementTextFormat.PLAIN
            plain.renderedHtml shouldBe null
            plain.keyPoints.first() shouldBe "**Eins**."
        }
    })
