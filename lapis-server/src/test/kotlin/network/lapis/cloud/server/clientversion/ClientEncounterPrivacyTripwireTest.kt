package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.62 "Begegnungsraum" (B2) -- the privacy and listen-only rules of the encounter client as a source scan (the DOM tests prove the
 * behaviour; this keeps the next change from quietly undoing it). Scope: every file `encounter/Encounter*.kt` of the client. A static scan
 * is a floor, not a proof -- it only sees spellings; the rules it pins:
 *
 * - **Text is text**: no `innerHTML`, no `rich = true` (a chat line, a name, a notice must never become markup).
 * - **One storage key, nothing else**: `localStorage`/`sessionStorage`/`indexedDB` appear only in `EncounterSceneToggle.kt`, and every use there
 *   names `ENCOUNTER_SCENE_OFF_KEY` (the "scene off" choice: no room, no person, no time).
 * - **Nothing in the console**: no `console.` at all, so no identity, name, room id or URL can reach a log from this package.
 * - **No exception text**: `.message` of a caught exception is never read (the server's wording is no UI text, see `AppState.guarded`).
 * - **Listen-only**: `getUserMedia`, `mediaDevices`, `enumerateDevices`, `setCamera`, `setMicrophone`, `setScreenShare` appear only in
 *   `EncounterMediaSession.kt` (the typed lock) and `EncounterPulpitControls.kt` (the office holder's own devices).
 * - **No telemetry**: no `sendBeacon`, `analytics`, `gtag`, direct `fetch(`.
 * - **No time trail**: `sentAtEpochMs` appears only in `EncounterMediaSession.kt`, where it is sent as `0L`; no chat/seat rendering shows a time.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val ENCOUNTER_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/encounter")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/encounter") }

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun encounterFiles(): List<File> =
    ENCOUNTER_DIR
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name.startsWith("Encounter") }
        .toList()

private fun codeLines(file: File): List<String> = file.readLines().filterNot { isCommentLine(it) }

private val STORAGE = Regex("""\b(?:localStorage|sessionStorage|indexedDB)\b""")
private val CONSOLE = Regex("""\bconsole\s*\.""")
private val EXCEPTION_MESSAGE = Regex("""\.message\b""")
private val INNER_HTML = Regex("""\binnerHTML\b|\brich\s*=\s*true\b""")
private val DEVICE_ACCESS = Regex("""\b(?:getUserMedia|mediaDevices|enumerateDevices|setCamera|setMicrophone|setScreenShare)\b""")
private val TELEMETRY = Regex("""\bsendBeacon\b|\banalytics\b|\bgtag\b|(?<![A-Za-z0-9_.])fetch\(""")
private val SENT_AT = Regex("""\bsentAtEpochMs\b""")

private val DEVICE_ALLOWED = setOf("EncounterMediaSession.kt", "EncounterPulpitControls.kt")

private fun findings(
    pattern: Regex,
    allowedFiles: Set<String> = emptySet(),
): List<String> =
    encounterFiles()
        .filter { it.name !in allowedFiles }
        .flatMap { file -> codeLines(file).filter { pattern.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" } }

class ClientEncounterPrivacyTripwireTest :
    FunSpec({
        test("the scan sees the encounter client (not vacuous): every part of the room is a file of the package") {
            val names = encounterFiles().map { it.name }.toSet()
            names.size shouldBeGreaterThanOrEqual 14
            listOf(
                "EncounterRoom.kt",
                "EncounterMediaSession.kt",
                "EncounterPulpitControls.kt",
                "EncounterSceneToggle.kt",
                "EncounterReactions.kt",
                "EncounterChatPanel.kt",
                "EncounterPresentPanel.kt",
                "EncounterStreamPanel.kt",
            ).forEach { (it in names) shouldBe true }
        }

        test("text is text: no innerHTML and no rich text anywhere in the encounter client") {
            findings(pattern = INNER_HTML).shouldBeEmpty()
        }

        test("browser storage is used in EncounterSceneToggle.kt only, and only for the scene-off key") {
            findings(pattern = STORAGE, allowedFiles = setOf("EncounterSceneToggle.kt")).shouldBeEmpty()
            val toggle = encounterFiles().first { it.name == "EncounterSceneToggle.kt" }
            val uses = codeLines(toggle).filter { STORAGE.containsMatchIn(it) && !it.trimStart().startsWith("import ") }
            (uses.size >= 2) shouldBe true
            uses.filterNot { it.contains("ENCOUNTER_SCENE_OFF_KEY") }.shouldBeEmpty()
        }

        test("nothing from the encounter client reaches the console") {
            findings(pattern = CONSOLE).shouldBeEmpty()
        }

        test("the text of a caught exception is never read") {
            findings(pattern = EXCEPTION_MESSAGE).shouldBeEmpty()
        }

        test("camera, microphone and device access exist only in the typed session lock and the office holder's controls") {
            findings(pattern = DEVICE_ACCESS, allowedFiles = DEVICE_ALLOWED).shouldBeEmpty()
            // the lock really is where it is said to be (a vacuous allow-list would hide a move)
            DEVICE_ALLOWED.forEach { name ->
                encounterFiles().first { it.name == name }.let { file ->
                    codeLines(file).any { DEVICE_ACCESS.containsMatchIn(it) }
                } shouldBe
                    true
            }
        }

        test("no telemetry and no direct fetch in the encounter client") {
            findings(pattern = TELEMETRY).shouldBeEmpty()
        }

        test("the chat time field is sent as zero and never rendered") {
            findings(pattern = SENT_AT, allowedFiles = setOf("EncounterMediaSession.kt")).shouldBeEmpty()
            val sender = encounterFiles().first { it.name == "EncounterMediaSession.kt" }
            codeLines(sender).any { it.contains("sentAtEpochMs = 0L") } shouldBe true
        }

        test("the detectors recognise a positive and a negative example") {
            STORAGE.containsMatchIn("    localStorage.getItem(KEY)") shouldBe true
            STORAGE.containsMatchIn("    val storageKey = x") shouldBe false
            CONSOLE.containsMatchIn("    console.log(identity)") shouldBe true
            CONSOLE.containsMatchIn("    val console2 = x") shouldBe false
            EXCEPTION_MESSAGE.containsMatchIn("    notifyError(e.message)") shouldBe true
            EXCEPTION_MESSAGE.containsMatchIn("    notifyError(tr(\"x\"))") shouldBe false
            INNER_HTML.containsMatchIn("    el.innerHTML = text") shouldBe true
            INNER_HTML.containsMatchIn("    div(text, rich = true)") shouldBe true
            DEVICE_ACCESS.containsMatchIn("    session.setCamera(true)") shouldBe true
            DEVICE_ACCESS.containsMatchIn("    session.sendChat(text)") shouldBe false
            TELEMETRY.containsMatchIn("    window.fetch(url)") shouldBe false
            TELEMETRY.containsMatchIn("    fetch(url)") shouldBe true
            TELEMETRY.containsMatchIn("    navigator.sendBeacon(url)") shouldBe true
            SENT_AT.containsMatchIn("    message.sentAtEpochMs") shouldBe true
        }
    })
