package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.272 -- the privacy rules of the device section in the event guest call view, as a source scan (the DOM tests prove the behaviour;
 * this keeps the next change from quietly undoing it). A person without an account joins an event; nothing about their devices may be kept
 * or sent anywhere. Scope: the three files of the guest view that handle devices, in the `client/` package (NOT the `encounter/` package
 * that `ClientEncounterPrivacyTripwireTest` scans). A static scan is a floor, not a proof -- it only sees spellings. The rules:
 *
 * - **Nothing is remembered**: no `localStorage`, `sessionStorage`, `indexedDB` (a guest leaves nothing in the browser).
 * - **Nothing is sent**: no `fetch(`, `XMLHttpRequest`, `sendBeacon`, `Rpc`, `rpcService` (the join request lives in `EventGuestJoinScreen.kt`).
 * - **Nothing in the console or a log**: no `console.`, no `logger`.
 * - **No device id and no raw label in a message**: a line that hands text to `onNote(`, `tr(`, `gettext(` or `content =` never names
 *   `deviceId` or `rawLabel`; the label reaches the page only as the text of an option through `conferenceDeviceOptionLabel(`.
 * - **The join form touches no device**: `EventGuestJoinScreen.kt` neither opens nor lists one.
 * - **The shared wiring stays neutral**: `ConferenceDeviceSelectWiring.kt` has no toast, storage or console of its own; the callers decide.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val GUEST_DEVICE_FILES = listOf("EventGuestCallView.kt", "EventGuestDevicePanel.kt", "ConferenceDeviceSelectWiring.kt")

private fun guestFile(name: String): File = File(CLIENT_DIR, name)

private fun guestCode(name: String): List<String> =
    guestFile(name)
        .readLines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .filterNot { it.trimStart().startsWith("import ") }

private fun guestFindings(
    pattern: Regex,
    files: List<String> = GUEST_DEVICE_FILES,
): List<String> = files.flatMap { name -> guestCode(name).filter { pattern.containsMatchIn(it) }.map { "$name: ${it.trim()}" } }

private val GUEST_STORAGE = Regex("""\b(?:localStorage|sessionStorage|indexedDB)\b""")
private val GUEST_NETWORK = Regex("""(?<![A-Za-z0-9_.])fetch\(|\bXMLHttpRequest\b|\bsendBeacon\b|\bRpc\b|\brpcService\b""")
private val GUEST_CONSOLE = Regex("""\bconsole\s*\.|\blogger\b|\bKotlinLogging\b""")
private val GUEST_TEXT_SINK = Regex("""\bonNote\(|\btr\(|\bgettext\(|\bcontent\s*=""")
private val GUEST_DEVICE_IDENTIFIER = Regex("""\b(?:deviceId|rawLabel)\b""")
private val GUEST_DEVICE_ACCESS =
    Regex(
        """\b(?:getUserMedia|mediaDevices|enumerateDevices|setCamera|setMicrophone|setScreenShare|listDevices|switchDevice|setSinkId|devicechange)\b""",
    )

class ClientEventGuestDevicePrivacyTripwireTest :
    FunSpec({
        test("the scan sees the guest device files (not vacuous)") {
            GUEST_DEVICE_FILES.forEach { name ->
                withClue("$name must exist in the client package") { guestFile(name).isFile shouldBe true }
                withClue("$name must have code lines") { (guestCode(name).size >= 20) shouldBe true }
            }
            guestFile("EventGuestJoinScreen.kt").isFile shouldBe true
        }

        test("a guest leaves nothing in the browser: no storage of any kind") {
            guestFindings(pattern = GUEST_STORAGE).shouldBeEmpty()
        }

        test("no remote call from the guest device files") {
            guestFindings(pattern = GUEST_NETWORK).shouldBeEmpty()
        }

        test("nothing reaches the console or a logger") {
            guestFindings(pattern = GUEST_CONSOLE).shouldBeEmpty()
        }

        test("no device id and no raw label in any text that is shown or announced") {
            val leaks =
                GUEST_DEVICE_FILES.flatMap { name ->
                    guestCode(name)
                        .filter { GUEST_TEXT_SINK.containsMatchIn(it) && GUEST_DEVICE_IDENTIFIER.containsMatchIn(it) }
                        .map { "$name: ${it.trim()}" }
                }
            leaks.shouldBeEmpty()
            // the label becomes page text only as the label of an option
            val labelUses = guestFindings(pattern = Regex("""\brawLabel\b"""))
            labelUses.filterNot { it.contains("conferenceDeviceOptionLabel(") }.shouldBeEmpty()
        }

        test("the join form opens and lists no device") {
            guestFindings(pattern = GUEST_DEVICE_ACCESS, files = listOf("EventGuestJoinScreen.kt")).shouldBeEmpty()
        }

        test("the shared select wiring has no toast, storage or console of its own") {
            guestFindings(
                pattern = Regex("""\bnotifyError\b|\bnotifyInfo\b|\bnotifySuccess\b"""),
                files = listOf("ConferenceDeviceSelectWiring.kt"),
            ).shouldBeEmpty()
        }

        test("the guest view stays lean: no chat, whiteboard, notes, roster or recording controls") {
            guestFindings(
                pattern = Regex("""\b(?:ConferenceChat|Whiteboard|ConferenceNotes|RosterPanel|RecordingsPanel|startRecording)\b"""),
                files = listOf("EventGuestCallView.kt", "EventGuestDevicePanel.kt"),
            ).shouldBeEmpty()
        }
    })
