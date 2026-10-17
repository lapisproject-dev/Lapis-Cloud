package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import java.io.File
import java.security.MessageDigest

/**
 * V1.9.156 -- the built-in placeholder of `EVENT_REC` and the capture variant of the guest notice, pinned as LITERALS (pattern of
 * [PulpitRecordingBuiltinTextPinTest]). A changed character fails the test; the answer is never to update the literal but to publish a new version through
 * the administration (for the placeholder) or to bump the version and the hash together (for the guest notice).
 */
class EventCaptureBuiltinTextPinTest :
    FunSpec({
        test("the placeholder of EVENT_REC keeps its label and hash") {
            EventCaptureConsentDefault.VERSION shouldBe "2026-10-18.event-rec.v1"
            EventCaptureConsentDefault.SHA256 shouldBe "3a0b7d481a96be26da506c21222ef31a0259d877dc262c4d6cc9ea638df463a8"
            AgreementTextHash.of(versionLabel = EventCaptureConsentDefault.VERSION, body = EventCaptureConsentDefault.TEXT) shouldBe
                EventCaptureConsentDefault.SHA256
        }

        test("BuiltinAgreementTexts hands out the placeholder for EVENT_REC, one for every profile") {
            BuiltinAgreementTexts.of(kind = AgreementTextKind.EVENT_REC).versionLabel shouldBe EventCaptureConsentDefault.VERSION
            BuiltinAgreementTexts.allOf(AgreementTextKind.EVENT_REC).size shouldBe 1
        }

        test("the placeholder follows the paragraph rule (headline, three key points) and says the recording and the right to leave") {
            EncounterConsentLayout.violation(label = "", body = EventCaptureConsentDefault.TEXT) shouldBe null
            EncounterConsentLayout.paragraphs(EventCaptureConsentDefault.TEXT).size shouldBe 5
            EventCaptureConsentDefault.TEXT shouldContain "aufgezeichnet"
            EventCaptureConsentDefault.TEXT shouldContain "live übertragen"
            EventCaptureConsentDefault.TEXT shouldContain "jederzeit verlassen"
        }

        test("the capture variant of the guest notice is pinned; the old notice is byte for byte what it was") {
            EventGuestNoticeCapture.VERSION shouldBe "2026-10-18.capture.v1"
            EventGuestNoticeCapture.TEXT shouldBe "Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus."
            EventGuestNoticeCapture.SHA256 shouldBe "c9bc01afdd159ec7489eccb32224a9bf991cb4c7ba4b6ff36b7beae55c0f1fa3"
            sha256(EventGuestNoticeCapture.TEXT) shouldBe EventGuestNoticeCapture.SHA256
            // the proof of earlier acknowledgments: never edited
            EventGuestNotice.VERSION shouldBe "2026-10-12.v1"
            EventGuestNotice.TEXT shouldBe
                "Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus. Es wird nichts aufgezeichnet."
            EventGuestNotice.SHA256 shouldBe "13b27a7cecae57223f7ec03492aaee7080bc6ee7f9e5736200ca600e6b47b4d1"
            // the variant must not make the promise that is false in a released event
            (EventGuestNoticeCapture.TEXT.contains("nichts aufgezeichnet")) shouldBe false
        }

        test(
            "the client carries the capture sentence of the guest notice word for word, in the member dialog (the page shares the same literal)",
        ) {
            val client =
                listOf(File("../lapis-client/src/jsMain/kotlin"), File("lapis-client/src/jsMain/kotlin"))
                    .first { it.exists() }
                    .resolve("network/lapis/cloud/client")
            listOf("EventCaptureConsent.kt", "EventGuestJoinScreen.kt").forEach { name ->
                client.resolve(name).readText().contains("\"${EventGuestNoticeCapture.TEXT}\"") shouldBe true
            }
        }
    })

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
