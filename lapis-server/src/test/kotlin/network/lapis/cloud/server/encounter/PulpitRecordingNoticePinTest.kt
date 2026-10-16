package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION
import java.io.File

/**
 * V1.9.143 -- the operator notice an ADMIN acknowledges before switching the pulpit recording on. Version and hash of the German reference text are pinned as
 * LITERALS (pattern of `MemberPublicBioConsentTextPinTest`): every textual change forces a new version -- the answer to a failing pin is to bump
 * [PULPIT_RECORDING_NOTICE_VERSION] together with [PulpitRecordingNotice.VERSION] and to carry the new text into the client, never to edit the literal.
 * The client shows the same paragraphs through gettext; this test checks that each one stands word for word in the client source.
 */
class PulpitRecordingNoticePinTest :
    FunSpec({
        test("version and hash of the notice are pinned") {
            PulpitRecordingNotice.VERSION shouldBe "1"
            PulpitRecordingNotice.SHA256 shouldBe "f3410bf7121b82e1416df1861d1accf1329de6b361704983e26f5d840fe2309e"
        }

        test("the shared constant is the version of the server's reference text") {
            PULPIT_RECORDING_NOTICE_VERSION shouldBe PulpitRecordingNotice.VERSION
        }

        test("the hash is sha256 over the version, a line feed and the paragraphs joined by blank lines") {
            PulpitRecordingNotice.SHA256 shouldBe
                ConsentHashing.sha256Hex("${PulpitRecordingNotice.VERSION}\n${PulpitRecordingNotice.PARAGRAPHS_DE.joinToString("\n\n")}")
        }

        test("the notice names the country-specific legal check, the operator's responsibility and the camera restriction") {
            val text = PulpitRecordingNotice.TEXT_DE
            text shouldContain "Recht Ihres Landes"
            text shouldContain "ersetzt keine Rechtsberatung"
            text shouldContain "Als Betreiber sind Sie dafür verantwortlich"
            text shouldContain "Das kann die Software nicht verhindern."
        }

        test("every paragraph of the reference text stands word for word in the client source (so the translated text cannot drift)") {
            val client =
                listOf(File("../lapis-client/src/jsMain/kotlin"), File("lapis-client/src/jsMain/kotlin"))
                    .first { it.exists() }
                    .resolve("network/lapis/cloud/client/PulpitRecordingSettings.kt")
                    .readText()
            val code = SourceScan.blank(client)
            PulpitRecordingNotice.PARAGRAPHS_DE.forEach { paragraph ->
                (client.contains("\"$paragraph\"")) shouldBe true
            }
            (code.isNotEmpty()) shouldBe true
        }
    })
