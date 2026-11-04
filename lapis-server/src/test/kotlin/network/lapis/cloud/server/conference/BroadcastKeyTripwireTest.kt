package network.lapis.cloud.server.conference

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

private val MAIN_DIR: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }

private fun mainKotlinFiles(): List<File> =
    MAIN_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/db/generated/") }.toList()

private fun File.code(): List<String> =
    readLines().filterNot { l -> l.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

/**
 * V1.9.274 -- structural rules of the broadcast key (see [BroadcastKeys]):
 *
 *  - every place that writes `ENDED` or `FAILED` to `conference_stream` is one of the six known places and each of them deletes the broadcast
 *    keys in the same transaction (a new place, or a place without the call, turns the build red);
 *  - a plaintext RTMP URL is built in exactly one place ([targetRtmpUrl]): no other file opens a destination or target key;
 *  - the files that hold the key rules log nothing at all.
 */
class BroadcastKeyTripwireTest :
    FunSpec({
        val endWrite = Regex("""it\[(?:ConferenceStreamTable\.)?status]\s*=\s*ConferenceStreamStatus\.(ENDED|FAILED)""")

        test("the writes of ENDED/FAILED to conference_stream are the six known places and each clears the broadcast keys") {
            val expected =
                mapOf(
                    "StreamPoller.kt" to 3, // finalizeEnded, finalizeEndedConfirmed, markFailed
                    "ConferenceStreamingService.kt" to 3, // startStream failure, stopStream, restartEgressForStream failure
                )
            val found = mutableMapOf<String, Int>()
            mainKotlinFiles().forEach { file ->
                val lines = file.code()
                lines.forEachIndexed { index, line ->
                    if (endWrite.containsMatchIn(line)) {
                        found.merge(file.name, 1, Int::plus)
                        // the call follows within the same function (generous window, the writes are followed by target updates and an audit entry)
                        val window = lines.drop(index).take(60).joinToString("\n")
                        withClue("${file.name} near '${line.trim()}' must call BroadcastKeys.clearForStream") {
                            window.contains("BroadcastKeys.clearForStream") shouldBe true
                        }
                    }
                }
            }
            found shouldBe expected
        }

        test("only BroadcastKeys.kt opens a stream key and builds the RTMP URL; the poller and the service use targetRtmpUrl") {
            val offenders =
                mainKotlinFiles()
                    .filter { it.name !in setOf("BroadcastKeys.kt", "SecretBox.kt") }
                    .filter { f ->
                        f.code().any {
                            it.contains("streamKeyCiphertext]") &&
                                !it.contains("it[") &&
                                !it.contains("== null") &&
                                !it.contains("!= null") &&
                                !it.contains("?.let")
                        } ||
                            f.code().any { it.contains("secretBox.open(") || it.contains("secretBox!!.open(") || it.contains("box.open(") }
                    }.filter { it.path.contains("/conference/") || it.path.contains("/rpc/ConferenceStream") }
                    .map { it.name }
            withClue("files that read or open stream key material outside BroadcastKeys.kt: $offenders") { offenders.shouldBeEmpty() }
        }

        test("the key rule files carry no logger and no println") {
            listOf("BroadcastKeys.kt", "StreamViewerUrls.kt").forEach { name ->
                val file = mainKotlinFiles().single { it.name == name }
                val code = file.code().joinToString("\n")
                withClue("$name must not log") {
                    (code.contains("logger") || code.contains("KotlinLogging") || code.contains("println(")) shouldBe false
                }
            }
        }

        test("the client opens a viewer page in exactly one place, from the address the server sent, never from an input") {
            val clientMain =
                File("../lapis-client/src/jsMain/kotlin").let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }
            val files = clientMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val opening = files.filter { f -> f.code().any { it.contains("window.open(") } && f.readText().contains("viewerUrl") }
            withClue("files that open a viewer page: ${opening.map { it.name }}") {
                opening.map { it.name } shouldBe
                    listOf("StreamBroadcastKeyField.kt")
            }
            val code = opening.single().code().filter { it.contains("window.open(") }
            code.size shouldBe 1
            code.single().contains("window.open(url,") shouldBe true
            // and the url is the stored one
            opening.single().readText().contains("val url = target.viewerUrl ?: return") shouldBe true
        }
    })
