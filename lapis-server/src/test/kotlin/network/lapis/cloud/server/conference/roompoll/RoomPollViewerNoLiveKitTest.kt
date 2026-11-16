package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

private fun clientDir(): File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client").let {
        if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
    }

private fun viewerFiles(): List<File> =
    clientDir()
        .listFiles { f -> f.name.startsWith("RoomPollViewer") && f.extension == "kt" }
        .orEmpty()
        .toList()

/**
 * Room polls, wave B -- source tripwires of the viewer page: it is a text-and-buttons page that reads and answers one poll. It must never
 * pull in LiveKit or the room join, never parse markup, and never reach the OAuth return mechanism (`safeReturnTo`). The tests of the client
 * itself run in a browser without a file system, so the source scan lives here (like the i18n guard).
 */
class RoomPollViewerNoLiveKitTest :
    FunSpec({
        test("the viewer page files exist (the scan is not vacuous)") {
            viewerFiles().map { it.name }.toSet() shouldBe
                setOf("RoomPollViewerModel.kt", "RoomPollViewerController.kt", "RoomPollViewerScreen.kt", "RoomPollViewerReturn.kt")
        }

        test("no viewer page file mentions LiveKit, the room join, the conference service or raw markup") {
            (viewerFiles() + File(clientDir(), "RoomPollQrSvg.kt")).forEach { file ->
                val code = SourceScan.blank(file.readText())
                listOf(
                    "livekit",
                    "LiveKit",
                    "joinRoom",
                    "IConferenceService",
                    "innerHTML",
                    "outerHTML",
                    "insertAdjacentHTML",
                    "document.write",
                ).forEach {
                    (code.contains(it)) shouldBe false
                }
            }
        }

        test("the page reaches the server only through the three calls of RoomPollViewerRpc") {
            val controller = SourceScan.blank(File(clientDir(), "RoomPollViewerController.kt").readText())
            val rpcClass = controller.substringAfter("internal class RoomPollViewerRpc(").substringBefore("\n)")
            Regex("""\bval (\w+):""").findAll(rpcClass).map { it.groupValues[1] }.toList() shouldBe listOf("version", "state", "answer")
            // every call of the poll service in the viewer files sits in that one class
            viewerFiles().forEach { file ->
                val calls = Regex("""rpcService<""").findAll(SourceScan.blank(file.readText())).count()
                calls shouldBe if (file.name == "RoomPollViewerController.kt") 3 else 0
            }
            Regex("""IConferenceRoomPollService""").findAll(controller).count() shouldBe 4
        }

        test("the return memory never touches safeReturnTo and the route does not start with /conference") {
            SourceScan.blank(File(clientDir(), "RoomPollViewerReturn.kt").readText()).contains("safeReturnTo") shouldBe false
            val routing = File(clientDir(), "Routing.kt").readText()
            routing.contains("""const val ROOM_POLL_VIEWER = "/room-poll/:roomId"""") shouldBe true
        }

        test("the viewer route is not behind requireAuth (the page shows its own login prompt)") {
            val routing = SourceScan.blank(File(clientDir(), "Routing.kt").readText())
            val handler =
                routing
                    .substringAfter(
                        "routing.kvOn(Routes.ROOM_POLL_VIEWER)",
                    ).substringBefore("routing.kvOn(Routes.EVENT_CONFERENCE)")
            handler.contains("requireAuth") shouldBe false
            handler.contains("renderRoomPollViewerScreen") shouldBe true
        }
    })
