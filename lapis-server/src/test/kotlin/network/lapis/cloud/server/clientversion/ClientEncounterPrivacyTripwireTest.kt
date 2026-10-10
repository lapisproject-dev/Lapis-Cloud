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
 * - **Two storage keys, nothing else**: `localStorage`/`sessionStorage`/`indexedDB` appear only in `EncounterSceneToggle.kt` (every use names
 *   `ENCOUNTER_SCENE_OFF_KEY` -- the "scene off" choice -- or, V1.9.96, `ENCOUNTER_BELL_SOUND_ON_KEY` -- the "bell sound on" choice; no room, no person, no time) and `EncounterDevicePicker.kt` (V1.9.91: every use names
 *   `conferenceDeviceStorageKey(`, the key the video conference already uses; one writer, one remover, device ids only).
 * - **Nothing in the console**: no `console.` at all, so no identity, name, room id or URL can reach a log from this package.
 * - **No exception text**: `.message` of a caught exception is never read (the server's wording is no UI text, see `AppState.guarded`).
 * - **Listen-only**: `getUserMedia`, `mediaDevices`, `enumerateDevices`, `setCamera`, `setMicrophone`, `setScreenShare` (and since V1.9.91 the
 *   device calls `listDevices`, `switchDevice`, `setSinkId`, `devicechange`, `listMicrophones`, `switchMicrophone`) appear only in
 *   `EncounterMediaSession.kt` (the typed lock), `EncounterPulpitControls.kt` (the office holder's own devices), `EncounterAudioOutput.kt`
 *   (the speaker: the only place of `setSinkId`/`enumerateDevices`/`devicechange`) and `EncounterDevicePicker.kt` (the panel).
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
private val DEVICE_ACCESS =
    Regex(
        """\b(?:getUserMedia|mediaDevices|enumerateDevices|setCamera|setMicrophone|setScreenShare|""" +
            """listDevices|switchDevice|setSinkId|devicechange|listMicrophones|switchMicrophone)\b""",
    )
private val DEVICE_IDENTIFIERS = Regex("""\b(?:deviceId|rawLabel|ConferenceDeviceOption)\b""")
private val SINK_AND_LIST = Regex("""\b(?:setSinkId|enumerateDevices|devicechange)\b""")
private val FORBIDDEN_OUTPUT_CALLS = Regex("""\b(?:selectAudioOutput|switchActiveDevice)\b|"audiooutput"""")
private val NO_LEAK_CHANNELS =
    Regex(
        """\bconsole\s*\.|\blogger\b|\bpublishData\b|(?<![A-Za-z0-9_.])fetch\(|\bRpc\b|\brpcService\b|\bsend(?:Chat|Reaction|SeatNudge)\b|\baudit\b|\btelemetry\b""",
    )
private val DEVICE_CALLS_IN_SESSION =
    Regex("""\b(?:listDevices|switchDevice|activeDeviceId|listMicrophones|switchMicrophone|activeMicrophoneId)\b""")
private val TELEMETRY = Regex("""\bsendBeacon\b|\banalytics\b|\bgtag\b|(?<![A-Za-z0-9_.])fetch\(""")
private val SENT_AT = Regex("""\bsentAtEpochMs\b""")

private val LIVEKIT_SESSION =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/livekit/LiveKitRoomSession.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/livekit/LiveKitRoomSession.kt") }

private val SHARED_ENCOUNTER =
    File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/EncounterSpace.kt")
        .let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/EncounterSpace.kt") }

private val DEVICE_ALLOWED =
    setOf("EncounterMediaSession.kt", "EncounterPulpitControls.kt", "EncounterAudioOutput.kt", "EncounterDevicePicker.kt")

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
            names.size shouldBeGreaterThanOrEqual 16
            listOf(
                "EncounterRoom.kt",
                "EncounterMediaSession.kt",
                "EncounterPulpitControls.kt",
                "EncounterSceneToggle.kt",
                "EncounterReactions.kt",
                "EncounterChatPanel.kt",
                "EncounterPresentPanel.kt",
                "EncounterStreamPanel.kt",
                "EncounterBell.kt",
                "EncounterBellSound.kt",
            ).forEach { (it in names) shouldBe true }
        }

        test("text is text: no innerHTML and no rich text anywhere in the encounter client") {
            findings(pattern = INNER_HTML).shouldBeEmpty()
        }

        test("browser storage is used in EncounterSceneToggle.kt (scene key) and EncounterDevicePicker.kt (device keys) only") {
            findings(pattern = STORAGE, allowedFiles = setOf("EncounterSceneToggle.kt", "EncounterDevicePicker.kt")).shouldBeEmpty()
            val toggle = encounterFiles().first { it.name == "EncounterSceneToggle.kt" }
            val uses = codeLines(toggle).filter { STORAGE.containsMatchIn(it) && !it.trimStart().startsWith("import ") }
            (uses.size >= 2) shouldBe true
            uses.filterNot { it.contains("ENCOUNTER_SCENE_OFF_KEY") || it.contains("ENCOUNTER_BELL_SOUND_ON_KEY") }.shouldBeEmpty()
        }

        test("V1.9.91: the device picker's storage use is one reader, one writer, one remover -- all on the shared device key, ids only") {
            val picker = encounterFiles().first { it.name == "EncounterDevicePicker.kt" }
            val code = codeLines(picker).filterNot { it.trimStart().startsWith("import ") }
            val uses = code.filter { STORAGE.containsMatchIn(it) }
            withClue("every storage line names the shared key function: $uses") {
                (uses.size == 3) shouldBe true
                uses.filterNot { it.contains("conferenceDeviceStorageKey(") }.shouldBeEmpty()
            }
            code.count { it.contains(".setItem(") } shouldBe 1
            code.count { it.contains(".removeItem(") } shouldBe 1
            // V1.9.96: the picker's bell switch stores nothing itself -- it only calls the model's functions
            code.none { it.contains("ENCOUNTER_BELL_SOUND_ON_KEY") } shouldBe true
            // writer and remover live inside the one function that is called after a person's own choice
            val text = code.joinToString("\n")
            val writer = text.substring(text.indexOf("fun encounterRememberDevice("), text.indexOf("fun encounterRememberDevice(") + 600)
            writer.contains(".setItem(") shouldBe true
            writer.contains(".removeItem(") shouldBe true
            // no key of the encounter room says "encounter ... device" (that would record that this browser visited a room)
            encounterFiles().forEach { f ->
                codeLines(f)
                    .filter { Regex("""\bconst\s+val\s+\w*KEY\w*\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    .filter { it.contains("encounter", ignoreCase = true) && it.contains("device", ignoreCase = true) }
                    .shouldBeEmpty()
            }
            // the scene rule stays word for word true for the room and the tables
            encounterFiles().filter { it.name in setOf("EncounterRoom.kt", "EncounterTables.kt") }.forEach { f ->
                codeLines(f).filterNot { it.trimStart().startsWith("import ") }.filter { STORAGE.containsMatchIn(it) }.shouldBeEmpty()
            }
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
            DEVICE_ACCESS.containsMatchIn("    session.listDevices(kind)") shouldBe true
            DEVICE_ACCESS.containsMatchIn("    element.setSinkId(id)") shouldBe true
            DEVICE_ACCESS.containsMatchIn("    val listed = devices.size") shouldBe false
            DEVICE_IDENTIFIERS.containsMatchIn("    val id = option.deviceId") shouldBe true
            DEVICE_IDENTIFIERS.containsMatchIn("    val device = 1") shouldBe false
            FORBIDDEN_OUTPUT_CALLS.containsMatchIn("    room.switchActiveDevice(kind, id)") shouldBe true
            FORBIDDEN_OUTPUT_CALLS.containsMatchIn("    devices.selectAudioOutput()") shouldBe true
            FORBIDDEN_OUTPUT_CALLS.containsMatchIn("    if (d.kind == \"audiooutput\")") shouldBe true
            FORBIDDEN_OUTPUT_CALLS.containsMatchIn("    val k = jsKind") shouldBe false
            NO_LEAK_CHANNELS.containsMatchIn("    console.log(label)") shouldBe true
            NO_LEAK_CHANNELS.containsMatchIn("    rpcService<X>().send(id)") shouldBe true
            NO_LEAK_CHANNELS.containsMatchIn("    val label = option.rawLabel") shouldBe false
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
        // ── Welle V1.9.91: device selection ─────────────────────────────────────

        test("V1.9.91: setSinkId, enumerateDevices and devicechange exist in EncounterAudioOutput.kt only") {
            findings(pattern = SINK_AND_LIST, allowedFiles = setOf("EncounterAudioOutput.kt")).shouldBeEmpty()
            val output = encounterFiles().first { it.name == "EncounterAudioOutput.kt" }
            val code = codeLines(output)
            SINK_AND_LIST.let { re ->
                listOf("setSinkId", "enumerateDevices", "devicechange").all { w -> code.any { it.contains(w) } }
            } shouldBe
                true
            // exactly two places set a sink: the `<audio>` elements and (V1.9.96, best effort) the bell's AudioContext
            code.count { it.contains(".setSinkId(") } shouldBe 2
        }

        test("V1.9.91: no output-selection API of the permission kind and no device-kind literal outside the audio output") {
            findings(pattern = FORBIDDEN_OUTPUT_CALLS).shouldBeEmpty()
            encounterFiles().filter { it.name in setOf("EncounterAudioOutput.kt", "EncounterDevicePicker.kt") }.forEach { f ->
                withClue("${f.name} must never ask for a permission") {
                    codeLines(f).filter { Regex("""\bgetUserMedia\b""").containsMatchIn(it) }.shouldBeEmpty()
                }
            }
        }

        test(
            "V1.9.91: a device id or label stays inside the session lock, the audio output and the picker -- and never meets a remote call or a log",
        ) {
            val allowed = setOf("EncounterMediaSession.kt", "EncounterAudioOutput.kt", "EncounterDevicePicker.kt")
            findings(pattern = DEVICE_IDENTIFIERS, allowedFiles = allowed).shouldBeEmpty()
            listOf("EncounterAudioOutput.kt", "EncounterDevicePicker.kt").forEach { name ->
                val file = encounterFiles().first { it.name == name }
                withClue("$name must not touch a log, the network, the audit or the data channel") {
                    codeLines(file).filter { NO_LEAK_CHANNELS.containsMatchIn(it) }.shouldBeEmpty()
                }
            }
            // in the session file the device calls are delegations to the underlying LiveKit session (or declarations), nothing else
            val media = encounterFiles().first { it.name == "EncounterMediaSession.kt" }
            codeLines(media)
                .filter { DEVICE_CALLS_IN_SESSION.containsMatchIn(it) }
                .filterNot { it.contains("fun ") || it.contains("liveKit.") }
                .shouldBeEmpty()
            // the room and the tables hand objects on; they never name a device identifier or a device call
            listOf("EncounterRoom.kt", "EncounterTables.kt").forEach { name ->
                val file = encounterFiles().first { it.name == name }
                withClue("$name must not name a device identifier") {
                    codeLines(
                        file,
                    ).filter { DEVICE_IDENTIFIERS.containsMatchIn(it) || DEVICE_CALLS_IN_SESSION.containsMatchIn(it) }.shouldBeEmpty()
                }
            }
        }

        test("V1.9.91: the table's microphone devices are a narrow interface of their own -- list, switch, read, nothing else") {
            val media = encounterFiles().first { it.name == "EncounterMediaSession.kt" }
            val text = codeLines(media).joinToString("\n")
            val start = text.indexOf("internal interface EncounterTableMicrophoneDevices")
            (start >= 0) shouldBe true
            val body = text.substring(start, text.indexOf("\n}\n", start))
            Regex("""fun (\w+)""").findAll(body).map { it.groupValues[1] }.toList() shouldBe
                listOf("listMicrophones", "switchMicrophone", "activeMicrophoneId")
            listOf("Camera", "Screen", "Data", "Speaker", "audiooutput", "send").forEach { forbidden ->
                withClue("EncounterTableMicrophoneDevices must not mention '$forbidden'") { body.contains(forbidden) shouldBe false }
            }
            // and the V1.9.80 three-member lock is untouched
            val lock = text.substring(text.indexOf("internal interface EncounterTableSession"))
            Regex("""suspend fun (\w+)""").findAll(lock.substring(0, lock.indexOf("\n}\n"))).map { it.groupValues[1] }.toList() shouldBe
                listOf("connect", "microphone", "disconnect")
        }

        test(
            "V1.9.91: the speaker can only come out of the <audio> elements -- webAudioMix is never switched on, and every audio host feeds the output",
        ) {
            codeLines(LIVEKIT_SESSION).filter { it.contains("webAudioMix") }.shouldBeEmpty()
            val host = encounterFiles().first { it.name == "EncounterMediaHost.kt" }
            val text = codeLines(host).joinToString("\n")
            val add = text.substring(text.indexOf("fun add("), text.indexOf("fun remove("))
            add.contains("audioOutput?.apply(") shouldBe true
            val room = encounterFiles().first { it.name == "EncounterRoom.kt" }
            val hosts = codeLines(room).filter { it.contains("EncounterMediaHost(") && !it.contains("class ") }
            hosts.size shouldBe 2
            hosts.all { it.contains("audioOutput") } shouldBe true
        }

        // ── Welle V1.9.95: the blessing ──────────────────────────────────────────

        test("V1.9.95: the blessing packet is accepted before the participant check and is never decoded") {
            val text = codeLines(LIVEKIT_SESSION).joinToString("\n")
            val start = text.indexOf("if (p3 == ENCOUNTER_BLESSING_TOPIC)")
            (start >= 0) shouldBe true
            val participantCheck = text.indexOf("val participant = p1.unsafeCast<RemoteParticipant?>() ?: return@onOwned")
            withClue("the blessing branch must stand BEFORE the line that drops participant-less packets") {
                (start in 0 until participantCheck) shouldBe true
            }
            val branch = text.substring(start, participantCheck)
            listOf("decode", "Json", "TextDecoder", "serializer", "toString", "String(", "identity").forEach { forbidden ->
                withClue("the blessing branch must not contain '$forbidden': $branch") { branch.contains(forbidden) shouldBe false }
            }
            branch.contains("encounterBlessingPacketAccepted(") shouldBe true
            branch.contains("payload.length") shouldBe true
            // the acceptance rule itself: no participant, 1..MAX bytes
            text.contains("!fromParticipant && payloadLength in 1..ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES") shouldBe true
        }

        test("V1.9.95: the blessing topic is its own, the payload limit is at most 16 bytes and the fixed body fits it") {
            val shared = SHARED_ENCOUNTER.readText()
            val limit = Regex("""ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES\s*=\s*(\d+)""").find(shared)!!.groupValues[1].toInt()
            (limit <= 16) shouldBe true
            shared.contains("\"lapis-encounter-blessing\"") shouldBe true
            val payload = Regex("""ENCOUNTER_BLESSING_PAYLOAD\s*=\s*\"\"\"(.*?)\"\"\"""").find(shared)!!.groupValues[1]
            payload shouldBe """{"b":1}"""
            (payload.length <= limit) shouldBe true
            // the topic string exists exactly once in the shared module and nowhere else as a literal
            Regex("\"lapis-encounter-blessing\"").findAll(shared).count() shouldBe 1
        }

        test("V1.9.95: the blessing display keeps no storage, no console, no counter, no name and no time of day") {
            val file = encounterFiles().first { it.name == "EncounterBlessing.kt" }
            val code = codeLines(file).filterNot { it.trimStart().startsWith("import ") }
            code
                .filter {
                    STORAGE.containsMatchIn(
                        it,
                    ) ||
                        CONSOLE.containsMatchIn(it) ||
                        NO_LEAK_CHANNELS.containsMatchIn(it)
                }.shouldBeEmpty()
            code
                .filter {
                    Regex("""\bcount\w*\b|\+\+|displayName|identity|memberId|Date\(|Date\.now|toLocale|\.name\b""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(it)
                }.shouldBeEmpty()
            // the room hands the packet on without any data
            val room = encounterFiles().first { it.name == "EncounterRoom.kt" }
            codeLines(room).any { it.contains("onBlessing = { blessingDisplay.onBlessing() }") } shouldBe true
        }

        // ── Welle V1.9.96: the bell ──────────────────────────────────────────────

        test("V1.9.96: the bell packet is accepted before the participant check and is never decoded") {
            val text = codeLines(LIVEKIT_SESSION).joinToString("\n")
            val start = text.indexOf("if (p3 == ENCOUNTER_BELL_TOPIC)")
            (start >= 0) shouldBe true
            val participantCheck = text.indexOf("val participant = p1.unsafeCast<RemoteParticipant?>() ?: return@onOwned")
            withClue("the bell branch must stand BEFORE the line that drops participant-less packets") {
                (start in 0 until participantCheck) shouldBe true
            }
            val branch = text.substring(start, participantCheck)
            listOf("decode", "Json", "TextDecoder", "serializer", "toString", "String(", "identity").forEach { forbidden ->
                withClue("the bell branch must not contain '$forbidden': $branch") { branch.contains(forbidden) shouldBe false }
            }
            branch.contains("encounterBellPacketAccepted(") shouldBe true
            branch.contains("payload.length") shouldBe true
            text.contains("!fromParticipant && payloadLength in 1..ENCOUNTER_BELL_MAX_PAYLOAD_BYTES") shouldBe true
        }

        test("V1.9.96: the bell topic is its own, the payload limit is at most 16 bytes and the fixed body fits it") {
            val shared = SHARED_ENCOUNTER.readText()
            val limit = Regex("""ENCOUNTER_BELL_MAX_PAYLOAD_BYTES\s*=\s*(\d+)""").find(shared)!!.groupValues[1].toInt()
            (limit <= 16) shouldBe true
            shared.contains("\"lapis-encounter-bell\"") shouldBe true
            val payload = Regex("""ENCOUNTER_BELL_PAYLOAD\s*=\s*\"\"\"(.*?)\"\"\"""").find(shared)!!.groupValues[1]
            payload shouldBe """{"v":1}"""
            (payload.length <= limit) shouldBe true
            Regex("\"lapis-encounter-bell\"").findAll(shared).count() shouldBe 1
        }

        test("V1.9.96: only the two known keys exist under lapis.encounter., and neither names a room, a space, a member or an id") {
            val literals =
                encounterFiles()
                    .flatMap { f ->
                        codeLines(f).flatMap {
                            Regex(""""(lapis\.encounter\.[^"]*)"""")
                                .findAll(it)
                                .map { m ->
                                    m.groupValues[1]
                                }.toList()
                        }
                    }.toSet()
            literals shouldBe setOf("lapis.encounter.sceneOff", "lapis.encounter.bellSoundOn")
            literals.forEach { key ->
                key.contains("$") shouldBe false
                Regex(
                    """room|space|member|\bid\b""",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(key.removePrefix("lapis.encounter.")) shouldBe
                    false
            }
        }

        test(
            "V1.9.96: the bell sound is stored in one function, removed in the same one, and that function is written only after the person's own change",
        ) {
            val toggle = encounterFiles().first { it.name == "EncounterSceneToggle.kt" }
            val text = codeLines(toggle).joinToString("\n")
            val writer = text.substring(text.indexOf("fun storeEncounterBellSoundOn("))
            writer.contains(".setItem(ENCOUNTER_BELL_SOUND_ON_KEY") shouldBe true
            writer.contains(".removeItem(ENCOUNTER_BELL_SOUND_ON_KEY") shouldBe true
            codeLines(toggle).count { it.contains(".setItem(ENCOUNTER_BELL_SOUND_ON_KEY") } shouldBe 1
            // the writer is named in exactly one other place (the wiring of the switch model) -- never called by a refresh or an open
            val named = encounterFiles().filter { f -> codeLines(f).any { it.contains("storeEncounterBellSoundOn") } }.map { it.name }
            named.toSet() shouldBe setOf("EncounterSceneToggle.kt", "EncounterRoom.kt")
            codeLines(encounterFiles().first { it.name == "EncounterRoom.kt" })
                .filter { it.contains("storeEncounterBellSoundOn") }
                .all { it.contains("write = ::storeEncounterBellSoundOn") } shouldBe true
            // the picker calls the model's writer only inside its `change` handler
            val picker = codeLines(encounterFiles().first { it.name == "EncounterDevicePicker.kt" }).joinToString("\n")
            Regex("""model\.write\(""").findAll(picker).count() shouldBe 1
            val change = picker.substring(picker.indexOf("change = {"))
            (change.indexOf("model.write(") in 0..300) shouldBe true
            // reading in refresh/open never writes: the sync function reads only
            val sync = picker.substring(picker.indexOf("private fun syncBellSwitch()"), picker.indexOf("private inner class Row("))
            sync.contains("write(") shouldBe false
        }

        test("V1.9.96: the bell sign and the bell sound keep no storage, no console, no network, no counter, no name and no time of day") {
            val forbiddenCalls =
                Regex(
                    """(?<![A-Za-z0-9_.])fetch\(|\bAudio\(|"audio"|\bcreateElement\b|\bdecodeAudioData\b|""" +
                        """\bMediaRecorder\b|\bgetUserMedia\b|""" +
                        """\bcreateMediaStreamDestination\b|\bcreateMediaElementSource\b|\bcaptureStream\b|\brpcService\b""" +
                        """|\bconsole\s*\.|\blogger\b""",
                )
            val identity =
                Regex("""\bcount\w*\b|\+\+|displayName|identity|memberId|Date\(|Date\.now|toLocale|\.name\b""", RegexOption.IGNORE_CASE)
            listOf("EncounterBell.kt", "EncounterBellSound.kt").forEach { name ->
                val code = codeLines(encounterFiles().first { it.name == name }).filterNot { it.trimStart().startsWith("import ") }
                withClue("$name must not touch the network, the console, a log, a media capture or an element of its own") {
                    code.filter { forbiddenCalls.containsMatchIn(it) }.shouldBeEmpty()
                }
                code.filter { STORAGE.containsMatchIn(it) || NO_LEAK_CHANNELS.containsMatchIn(it) }.shouldBeEmpty()
                withClue(
                    "$name must not keep a counter, a name or a time of day",
                ) { code.filter { identity.containsMatchIn(it) }.shouldBeEmpty() }
            }
            // the room hands the packet on without any data
            val room = encounterFiles().first { it.name == "EncounterRoom.kt" }
            codeLines(room).any { it.contains("onBell = { bellDisplay?.onBell() }") } shouldBe true
        }

        test("V1.9.96: AudioContext lives in EncounterBellSound.kt only, and the sink of the speaker only in EncounterAudioOutput.kt") {
            findings(
                pattern = Regex("""\bAudioContext\b|\bwebkitAudioContext\b"""),
                allowedFiles = setOf("EncounterBellSound.kt"),
            ).shouldBeEmpty()
            codeLines(encounterFiles().first { it.name == "EncounterBellSound.kt" }).any { it.contains("AudioContext") } shouldBe true
            findings(pattern = SINK_AND_LIST, allowedFiles = setOf("EncounterAudioOutput.kt")).shouldBeEmpty()
        }
    })
