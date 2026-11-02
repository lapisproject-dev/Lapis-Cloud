package network.lapis.cloud.server.conference.pausefeed

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

private val MAIN: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }

private fun mainFiles() = MAIN.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }.toList()

private fun File.codeLines(): List<String> =
    readLines().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }

/**
 * V1.9.255 -- pins the structure of the pause feed:
 *
 *  * the feed package never talks to LiveKit and never builds a token or a room name;
 *  * only `restartEgressForStream` (the choke point), the start path and the secret-ballot / poller flows start a composite egress, and the
 *    choke point wraps its claim in `stopThen`;
 *  * ffmpeg is started only from the two feed/transcode places, always as an argument list (no shell);
 *  * no log line of the feed package names a URL, a key or an argument list.
 */
class PauseFeedTripwireTest :
    FunSpec({
        val feedFiles = File(MAIN, "conference/pausefeed").listFiles { f -> f.extension == "kt" }!!.toList()

        test("no file of the feed package mentions LiveKit, a token, an access token or a room join") {
            val banned = listOf("LiveKit", "AccessToken", "joinToken", "createToken", "livekitRoomName")
            feedFiles.forEach { f ->
                val code = f.codeLines().joinToString("\n")
                banned.forEach { word -> withClue("'$word' in ${f.name}") { code.contains(word) shouldBe false } }
            }
        }

        test("an egress is started only by the places that know the pause feed; restartEgressForStream claims under stopThen") {
            val starters =
                mainFiles().filter { f ->
                    f.codeLines().any {
                        it.contains("startRoomCompositeEgress(") ||
                            it.contains("startParticipantEgress(")
                    }
                }
            val allowed = setOf("ConferenceStreamingService.kt", "LiveKitEgressClient.kt")
            withClue(
                "files that start a stream egress: ${starters.map { it.name }}",
            ) { (starters.map { it.name }.toSet() - allowed).shouldBeEmpty() }
            val service = File(MAIN, "rpc/ConferenceStreamingService.kt").readText()
            val body = service.substringAfter("internal suspend fun restartEgressForStream(").substringBefore("val toRestart")
            body.contains("pauseFeed.stopThen(streamId = streamId)") shouldBe true
        }

        test("ffmpeg is spawned only in the feed launcher, the ffprobe prober and the shared runners, always with an argument list") {
            val spawners =
                mainFiles().filter { f -> f.codeLines().any { it.contains("ProcessBuilder(") } }.map { it.name }.toSet()
            withClue("files with a ProcessBuilder: $spawners") {
                feedFiles.filter { f -> f.codeLines().any { it.contains("ProcessBuilder(") } }.map { it.name }.toSet() shouldBe
                    setOf("PauseFeedProcess.kt", "PauseMusicProcessor.kt")
            }
            feedFiles.forEach { f ->
                f.codeLines().filter { it.contains("ProcessBuilder(") }.forEach { line ->
                    withClue("${f.name}: $line") {
                        (line.contains("sh -c") || line.contains("\"sh\"") || line.contains("bash") || line.contains("cmd")) shouldBe false
                    }
                }
            }
        }

        test("client: the pause preview is a 16:9 frame (aspect-ratio) and the music label is never built as markup") {
            val client =
                File("../lapis-client/src/jsMain").let { if (it.exists()) it else File("lapis-client/src/jsMain") }
            val css = File(client, "resources/theme.css").readText()
            val block = css.substringAfter(".lapis-pause-preview {").substringBefore("}")
            block.contains("aspect-ratio: 16 / 9") shouldBe true
            val screen =
                File(client, "kotlin/network/lapis/cloud/client/ConferencePauseMediaSettings.kt").readLines().filterNot {
                    it.trim().startsWith("//") ||
                        it.trim().startsWith("*")
                }
            // the only rich (HTML) content of the section is the audio element with a sanitized, server-made version -- never the label
            screen.filter { it.contains("rich = true") }.size shouldBe 1
            screen.none { it.contains("label") && it.contains("rich") } shouldBe true
        }

        test("no log line of the feed package carries a URL, a key, an argument list or an exception message") {
            val banned =
                listOf(
                    "targetUrls",
                    "\$url",
                    "\${url",
                    "\$key",
                    "\${key",
                    "\${wish",
                    "\$wish",
                    "command.args",
                    "e.message",
                    "\${accepted",
                    "\$accepted",
                )
            feedFiles.forEach { f ->
                f.codeLines().filter { it.contains("logger.") }.forEach { line ->
                    banned.forEach { token ->
                        // the sanitized stderr dump is the one allowed carrier ("lines" are already run through command.sanitize)
                        if (!line.contains("pause feed ffmpeg output")) {
                            withClue("${f.name}: '$line' contains '$token'") { line.contains(token) shouldBe false }
                        }
                    }
                }
            }
        }
    })
