package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
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

private val LIVEKIT_SESSION =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/livekit/LiveKitRoomSession.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/livekit/LiveKitRoomSession.kt") }

private val SHARED_ENCOUNTER =
    File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/EncounterSpace.kt")
        .let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/EncounterSpace.kt") }

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

        // ── Welle V1.9.79: seats ─────────────────────────────────────────────

        test("V1.9.79: the seat nudge is never decoded -- its branch measures the length and reports the SDK identity, nothing else") {
            val text = codeLines(LIVEKIT_SESSION).joinToString("\n")
            val start = text.indexOf("ENCOUNTER_SEAT_NUDGE_TOPIC ->")
            (start >= 0) shouldBe true
            val branch = text.substring(start, text.indexOf("else -> return@onOwned", start))
            listOf("decode", "Json", "TextDecoder", "serializer", "toString", "String(").forEach { forbidden ->
                withClue("the seat nudge branch must not contain '$forbidden': $branch") { branch.contains(forbidden) shouldBe false }
            }
            branch.contains("payload.length > ENCOUNTER_SEAT_NUDGE_MAX_PAYLOAD_BYTES") shouldBe true
            branch.contains("participant.identity") shouldBe true
        }

        test("V1.9.79: the seat nudge payload limit is at most 8 bytes, and the topic is its own") {
            val shared = SHARED_ENCOUNTER.readText()
            val limit = Regex("""ENCOUNTER_SEAT_NUDGE_MAX_PAYLOAD_BYTES\s*=\s*(\d+)""").find(shared)!!.groupValues[1].toInt()
            (limit <= 8) shouldBe true
            shared.contains("\"lapis-encounter-seat\"") shouldBe true
            // the fixed body that is sent fits the limit
            val body = Regex("""ENCOUNTER_SEAT_NUDGE_BODY\s*=\s*"((?:\\.|[^"\\])*)"""").find(LIVEKIT_SESSION.readText())!!.groupValues[1]
            (body.replace("\\\"", "\"").length <= limit) shouldBe true
        }

        test("V1.9.79: no announcement of the room names a person -- the seat sentences carry row and position only") {
            val room = encounterFiles().first { it.name == "EncounterRoom.kt" }
            val announcing = codeLines(room).filter { Regex("""\bannounceSeat\(|\beventLive\.content\s*=""").containsMatchIn(it) }
            (announcing.size >= 5) shouldBe true // the scan is not vacuous: seated, released, taken, busy, newcomer, reactions
            announcing.filter { Regex("""displayName|\bname\b|\.name\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.shouldBeEmpty()
            // the vocabulary functions behind them take numbers only
            val vocabulary = encounterFiles().first { it.name == "EncounterVocabulary.kt" }.readText()
            listOf("seatedAnnouncement", "seatReleasedAnnouncement", "seatTakenAnnouncement").forEach { fn ->
                val signature = Regex("""fun $fn\(([^)]*)\)""").find(vocabulary)!!.groupValues[1]
                withClue("$fn($signature)") { signature.contains("String") shouldBe false }
            }
        }

        test("V1.9.79: nothing about seats is stored in the browser, and the seat is never part of a URL") {
            val seatCode =
                encounterFiles().filter { it.name in setOf("EncounterSeating.kt", "EncounterSceneLayout.kt", "EncounterRoom.kt") }
            seatCode.forEach { f ->
                val code = codeLines(f).filterNot { it.trimStart().startsWith("import ") }
                code.filter { STORAGE.containsMatchIn(it) }.shouldBeEmpty()
                code.filter { Regex("""location\.(href|hash|search)|history\.(push|replace)State""").containsMatchIn(it) }.shouldBeEmpty()
            }
        }

        // ── Welle V1.9.80: tables ────────────────────────────────────────────

        test("V1.9.80: the table session type has no camera, no screen and no data method, and a video track is never attached") {
            val media = encounterFiles().first { it.name == "EncounterMediaSession.kt" }
            val text = codeLines(media).joinToString("\n")
            val start = text.indexOf("internal interface EncounterTableSession")
            (start >= 0) shouldBe true
            val body = text.substring(start, text.indexOf("\n}\n", start))
            // exactly three members: connect, microphone, disconnect
            Regex("""suspend fun (\w+)""").findAll(body).map { it.groupValues[1] }.toList() shouldBe
                listOf("connect", "microphone", "disconnect")
            listOf("Camera", "Screen", "sendChat", "sendReaction", "sendSeatNudge", "Data").forEach { forbidden ->
                withClue("EncounterTableSession must not mention '$forbidden'") { body.contains(forbidden) shouldBe false }
            }
            // the audio-only filter of the factory: only a track of kind "audio" is handed on
            val factory =
                text.substring(
                    text.indexOf("private fun tableLiveKitSession"),
                    text.indexOf("internal fun openEncounterTableSession"),
                )
            factory.contains("if (track.kind == \"audio\") callbacks.onAudioTrack") shouldBe true
            factory.contains("onLocalVideoTrack = { _ -> }") shouldBe true
        }

        test("V1.9.80: nothing about tables is stored in the browser, and a table is never part of a URL") {
            encounterFiles().filter { it.name in setOf("EncounterTables.kt", "EncounterRoom.kt") }.forEach { f ->
                val code = codeLines(f).filterNot { it.trimStart().startsWith("import ") }
                code.filter { STORAGE.containsMatchIn(it) }.shouldBeEmpty()
                code.filter { Regex("""location\.(href|hash|search)|history\.(push|replace)State""").containsMatchIn(it) }.shouldBeEmpty()
            }
        }

        test("V1.9.80: no announcement of a table names a person -- the sentences carry table numbers only") {
            val tables = encounterFiles().first { it.name == "EncounterTables.kt" }
            val announcing = codeLines(tables).filter { Regex("""host\.announce\(""").containsMatchIn(it) }
            (announcing.size >= 6) shouldBe true // the scan is not vacuous
            announcing.filter { Regex("""displayName|\.name\b|initials""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.shouldBeEmpty()
            val room = encounterFiles().first { it.name == "EncounterRoom.kt" }
            codeLines(room)
                .filter { Regex("""announceTable\(""").containsMatchIn(it) && !it.contains("private fun") }
                .filter { Regex("""displayName|\.name\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .shouldBeEmpty()
        }
    })
