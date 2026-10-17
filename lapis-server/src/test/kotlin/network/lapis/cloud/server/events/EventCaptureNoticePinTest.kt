package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.encounter.ConsentHashing
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION
import java.io.File

/**
 * V1.9.156 -- the operator notice an ADMIN acknowledges before switching event capture on. Version and hash of the German reference text are pinned as
 * LITERALS (pattern of `PulpitRecordingNoticePinTest`): every textual change forces a new version -- the answer to a failing pin is to bump
 * [EVENT_CAPTURE_NOTICE_VERSION] together with [EventCaptureNotice.VERSION] and to carry the new text into the client, never to edit the literal.
 */
class EventCaptureNoticePinTest :
    FunSpec({
        test("version and hash of the notice are pinned") {
            EventCaptureNotice.VERSION shouldBe "1"
            EventCaptureNotice.SHA256 shouldBe "a80817a821d3468ca580901f065aeaddc18027e220b6508651bc182bccbe4096"
        }

        test("the shared constant is the version of the server's reference text") {
            EVENT_CAPTURE_NOTICE_VERSION shouldBe EventCaptureNotice.VERSION
        }

        test("the hash is sha256 over the version, a line feed and the paragraphs joined by blank lines") {
            EventCaptureNotice.SHA256 shouldBe
                ConsentHashing.sha256Hex("${EventCaptureNotice.VERSION}\n${EventCaptureNotice.PARAGRAPHS_DE.joinToString("\n\n")}")
        }

        test(
            "the notice names the country-specific legal check, the operator's responsibility, the screenshots and the text the operator must publish first",
        ) {
            val text = EventCaptureNotice.TEXT_DE
            text shouldContain "Recht Ihres Landes"
            text shouldContain "ersetzt keine Rechtsberatung"
            text shouldContain "Als Betreiber sind Sie dafür verantwortlich"
            text shouldContain "Bildschirmfotos"
            text shouldContain "vor dem Einschalten veröffentlicht"
            text shouldContain "nicht nur der Vortragenden"
        }

        test("every paragraph of the reference text stands word for word in the client source (so the translated text cannot drift)") {
            val client =
                listOf(File("../lapis-client/src/jsMain/kotlin"), File("lapis-client/src/jsMain/kotlin"))
                    .first { it.exists() }
                    .resolve("network/lapis/cloud/client/EventCaptureSettings.kt")
                    .readText()
            EventCaptureNotice.PARAGRAPHS_DE.forEach { paragraph ->
                (client.contains("\"$paragraph\"")) shouldBe true
            }
        }
    })
