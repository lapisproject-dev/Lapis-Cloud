package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.133 -- the client side of the conference timer, guarded as text (it cannot run against a real LiveKit call in Karma):
 *
 *  - the timer topic is read ONLY in `LiveKitRoomSession.kt`, in a branch that sits BEFORE the participant check and drops every packet
 *    that carries a sending participant -- so a person with `can_publish_data` can never forge "the time is up";
 *  - no client file ever publishes on the timer topic (it is server -> client only);
 *  - the timer's own client files never touch a media track, so the timer is not in a recording, a stream or a video overlay;
 *  - the countdown runs on the monotonic clock, never `Date.now()`.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private fun clientFiles(): List<File> = CLIENT_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

private fun code(file: File): String =
    file
        .readLines()
        .filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
        .joinToString("\n")

class ConferenceTimerClientTripwireTest :
    FunSpec({
        test("the timer topic is read in LiveKitRoomSession only, before the participant check, and never from a participant") {
            val users = clientFiles().filter { code(it).contains("CONFERENCE_TIMER_TOPIC") }.map { it.name }
            users shouldBe listOf("LiveKitRoomSession.kt")
            val session = code(File(CLIENT_DIR, "livekit/LiveKitRoomSession.kt"))
            val branch = session.indexOf("if (p3 == CONFERENCE_TIMER_TOPIC)")
            val participantCheck = session.indexOf("val participant = p1.unsafeCast<RemoteParticipant?>() ?: return@onOwned")
            (branch >= 0) shouldBe true
            (participantCheck >= 0) shouldBe true
            (branch < participantCheck) shouldBe true
            val body = session.substring(branch, participantCheck)
            body shouldContain "val fromParticipant = p1.unsafeCast<RemoteParticipant?>() != null"
            body shouldContain "if (fromParticipant"
            body shouldContain "conferenceTimerPacket(fromParticipant = false"
            body shouldContain "return@onOwned"
        }

        test("no client code publishes on the timer topic") {
            // the constant is named by LiveKitRoomSession only (first test), and there only in the receiving branch
            val session = code(File(CLIENT_DIR, "livekit/LiveKitRoomSession.kt"))
            Regex("""publishData\([^)]*CONFERENCE_TIMER_TOPIC""").containsMatchIn(session) shouldBe false
            Regex("""topic\s*=\s*CONFERENCE_TIMER_TOPIC""").containsMatchIn(session) shouldBe false
        }

        test("the timer's client files never capture, render into or publish a media track") {
            val timerFiles = clientFiles().filter { it.name.startsWith("ConferenceTimer") }
            (timerFiles.size >= 5) shouldBe true
            timerFiles.forEach { file ->
                listOf(
                    "captureStream",
                    "publishTrack",
                    "canvas",
                    "createMediaStreamDestination",
                    "getDisplayMedia",
                    "MediaStream",
                ).forEach {
                    withClue("${file.name} contains $it") { code(file).contains(it) shouldBe false }
                }
            }
        }

        test("the countdown and the clock offset use the monotonic clock only") {
            listOf(
                "livekit/ConferenceTimer.kt",
                "ConferenceTimerController.kt",
                "ConferenceTimerPill.kt",
                "ConferenceTimerDialog.kt",
            ).forEach { name ->
                val text = code(File(CLIENT_DIR, name))
                text shouldNotContain "Date.now()"
                text shouldNotContain "Clock.System"
                text shouldNotContain "Date()"
            }
            code(File(CLIENT_DIR, "ConferenceScreen.kt")) shouldContain "perfNow = { window.performance.now() }"
        }

        test("the timer wiring of the screen is in the main room only and the gong switch is not offered in a breakout") {
            val screen = code(File(CLIENT_DIR, "ConferenceScreen.kt"))
            screen shouldContain "val timerPill = if (isBreakout) null else ConferenceTimerPill("
            screen shouldContain "if (!isBreakout) TimerGongSwitchRow("
            screen shouldContain "if (!isBreakout) {\n        timerController ="
        }

        test("the timer files contain no rpcService read (the data-state ledger must not grow)") {
            clientFiles()
                .filter { it.name.startsWith("ConferenceTimer") || it.name == "livekit/ConferenceTimer.kt" }
                .filter { code(it).contains("rpcService") }
                .map { it.name }
                .shouldBeEmpty()
        }
    })
