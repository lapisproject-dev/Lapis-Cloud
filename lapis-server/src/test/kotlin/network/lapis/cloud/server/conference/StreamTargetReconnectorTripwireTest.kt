package network.lapis.cloud.server.conference

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private val SERVER_MAIN: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }

private fun serverFiles(): List<File> = SERVER_MAIN.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

private fun File.codeText(): String =
    readLines().filterNot { l -> l.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }.joinToString("\n")

/**
 * V1.9.275 -- structural rules of the re-add of one target's output: `updateStream` has exactly one caller, which never starts an egress and never
 * logs a URL; the poller and the reconnect action both go through it; the retry window is opened wherever an egress becomes LIVE.
 */
class StreamTargetReconnectorTripwireTest :
    FunSpec({
        test("updateStream( is called in StreamTargetReconnector.kt only (the declarations in LiveKitEgressClient.kt excepted)") {
            val callers =
                serverFiles()
                    .filter { it.name != "LiveKitEgressClient.kt" }
                    .filter { Regex("""\.updateStream\(""").containsMatchIn(it.codeText()) }
                    .map { it.name }
            callers shouldBe listOf("StreamTargetReconnector.kt")
        }

        test("the choke point starts no egress and never logs a URL") {
            val code = serverFiles().single { it.name == "StreamTargetReconnector.kt" }.codeText()
            withClue("no egress start") {
                (
                    code.contains(
                        "startRoomCompositeEgress",
                    ) ||
                        code.contains("startParticipantEgress") ||
                        code.contains("startTrackEgress")
                ) shouldBe
                    false
            }
            val logLines = code.lines().filter { it.contains("logger.") }
            withClue("log lines name no URL") { logLines.none { it.contains("Url") || it.contains("url") } shouldBe true }
        }

        test("the poller and the reconnect action go through reissueStreamTargetOutput") {
            serverFiles().single { it.name == "StreamPoller.kt" }.codeText().contains("reissueStreamTargetOutput(") shouldBe true
            serverFiles().single { it.name == "ConferenceStreamingService.kt" }.codeText().contains("reissueStreamTargetOutput(") shouldBe
                true
        }

        test("every place an egress becomes LIVE opens a retry window") {
            val service = serverFiles().single { it.name == "ConferenceStreamingService.kt" }.codeText()
            Regex("""openRetryWindowForStream\(""").findAll(service).count() shouldBe 2 // startStream, restartEgressForStream
            // orphan adoption
            serverFiles().single { it.name == "StreamPoller.kt" }.codeText().contains("openRetryWindowForStream(") shouldBe true
        }
    })
