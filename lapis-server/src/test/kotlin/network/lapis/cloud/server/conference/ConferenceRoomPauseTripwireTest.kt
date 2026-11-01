package network.lapis.cloud.server.conference

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

private val MAIN: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }

private fun mainFiles(): List<File> =
    MAIN.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }.toList()

// Code only: comment lines are dropped so a KDoc cannot trigger or hide a finding.
private fun File.codeLines(): List<String> =
    readLines().filterNot {
        it.trim().let { l ->
            l.startsWith("//") ||
                l.startsWith("*") ||
                l.startsWith("/*")
        }
    }

/**
 * V1.9.254 -- pins the structure of the pause mode:
 *
 *  - `conference_room.capture_paused_at` and the pause rows are written ONLY by `ConferenceRoomPauseCoordinator.kt` (which also holds
 *    `RoomPauseDb`); the stop paths that must end a pause call into `RoomPauseDb`, they do not write the column themselves;
 *  - `conference_recording.paused_at` is written only on the pause path (coordinator) and the three stop paths;
 *  - the coordinator never talks to LiveKit inside a `transaction { }` (Exposed repeats the block on a `SQLException`);
 *  - the coordinator logs no identity, name or room id;
 *  - the pause path has no web egress: nothing can show a page of this server (or the room UI) to a platform.
 */
class ConferenceRoomPauseTripwireTest :
    FunSpec({
        val coordinator = "ConferenceRoomPauseCoordinator.kt"

        test("capture_paused_at is written only inside the coordinator file") {
            val writers =
                mainFiles().filter { f ->
                    f.name != coordinator && f.codeLines().any { it.contains("capturePausedAt] =") || it.contains("[capturePausedAt] =") }
                }
            withClue("files that write conference_room.capture_paused_at: ${writers.map { it.name }}") { writers.shouldBeEmpty() }
        }

        test("the pause table is inserted, updated and deleted only in the coordinator file; the poller only reads it") {
            val writers =
                mainFiles().filter { f ->
                    f.name != coordinator &&
                        f.codeLines().any {
                            it.contains("ConferenceRecordingPauseTable.insert") ||
                                it.contains("ConferenceRecordingPauseTable.update") ||
                                it.contains("ConferenceRecordingPauseTable.deleteWhere")
                        }
                }
            withClue("files that write conference_recording_pause: ${writers.map { it.name }}") { writers.shouldBeEmpty() }
        }

        test("recording.paused_at is written only by the coordinator and the three stop paths") {
            val allowed = setOf(coordinator, "RecordingPoller.kt", "ConferenceRecordingService.kt")
            val writers =
                mainFiles().filter { f ->
                    val code = f.codeLines().joinToString("\n")
                    // an update of the RECORDING table that touches pausedAt (the stream table has a pausedAt of its own)
                    Regex("""ConferenceRecordingTable\s*\.update\([^{]*\{[^}]*pausedAt\]""").containsMatchIn(code) ||
                        Regex("""ConferenceRecordingTable\.update\(\{[^}]*\}\)\s*\{[^}]*\[pausedAt\]""").containsMatchIn(code)
                }
            withClue("files that write conference_recording.paused_at: ${writers.map { it.name }}") {
                (writers.map { it.name }.toSet() - allowed).shouldBeEmpty()
            }
        }

        test("the coordinator makes no LiveKit call inside a transaction block") {
            val code = File(MAIN, "conference/$coordinator").codeLines().joinToString("\n")
            val forbidden =
                listOf(
                    "liveKitAdminClient.",
                    "liveKitEgressClient.",
                    "quiesceStreamEgress(",
                    "restartEgressForStream(",
                    "requestRecordingTick(",
                )
            var index = code.indexOf("transaction {")
            var checked = 0
            while (index >= 0) {
                var depth = 0
                var end = index
                for (i in code.indexOf('{', index) until code.length) {
                    when (code[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
                val block = code.substring(index, end)
                forbidden.forEach { call ->
                    withClue("'$call' inside a transaction block of the coordinator") {
                        block.contains(call) shouldBe
                            false
                    }
                }
                checked++
                index = code.indexOf("transaction {", end)
            }
            (checked >= 4) shouldBe true // pause, resume and the two state reads
        }

        test("the coordinator logs counts and exception classes only -- no identity, name, room id or member") {
            val logLines = File(MAIN, "conference/$coordinator").codeLines().filter { it.contains("logger.") }
            logLines.isNotEmpty() shouldBe true
            val banned = listOf("current.", "memberId", "roomId", "roomName", "identity", "displayName", "\${e.message}")
            logLines.forEach { line -> banned.forEach { token -> withClue("log line '$line'") { line.contains(token) shouldBe false } } }
        }

        test("no web egress exists: the egress client has no method that renders a page") {
            val egress = File(MAIN, "conference/LiveKitEgressClient.kt").readText()
            listOf("StartWebEgress", "web_egress", "WebEgress", "custom_base_url", "template_base").forEach {
                withClue("'$it' in LiveKitEgressClient.kt") { egress.contains(it) shouldBe false }
            }
        }

        test("client: the pause notice is the ONE room notice (lapis-room-notice) and the new client code has no colour literal") {
            val client =
                File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                    .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }
            val screen = File(client, "ConferenceScreen.kt").readText()
            withClue("the banner must carry lapis-room-notice") { screen.contains("lapis-room-notice") shouldBe true }
            // exactly one element with role=status that is the room banner: the pause adds no second bar
            screen.split("secretBallotPauseBanner = ").size shouldBe 2
            val colour = Regex("""#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(""")
            listOf("ConferenceRoomPause.kt").forEach { name ->
                val hits = File(client, name).codeLines().filter { colour.containsMatchIn(it) }
                withClue("colour literal in $name: $hits") { hits.shouldBeEmpty() }
            }
        }
    })
