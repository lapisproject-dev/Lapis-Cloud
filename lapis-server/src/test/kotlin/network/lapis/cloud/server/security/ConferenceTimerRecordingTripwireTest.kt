package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan
import java.io.File

private fun clientFiles(): List<File> =
    listOf(File("../lapis-client/src/jsMain/kotlin"), File("lapis-client/src/jsMain/kotlin"))
        .first { it.exists() }
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

/**
 * V1.9.133 -- the conference timer is a DOM overlay of the client, so it is NOT in a recording or a stream. That holds only as long as
 * (a) nothing renders the Lapis client page into the broadcast (the egress assumptions of [ConferenceReactionsRecordingTripwireTest]),
 * (b) the video-overlay compositor (client side; V1.9.129 computes overlays INTO the camera track) never learns about the timer and (c) the client
 * timer files never capture or publish a track.
 */
class ConferenceTimerRecordingTripwireTest :
    FunSpec({
        test("the egress client still has no custom template / web-page egress and exactly the four known kinds") {
            val raw = EncounterSourceScan.mainFile("conference/LiveKitEgressClient.kt").readText()
            listOf("custom_base_url", "template_base", "customBaseUrl", "templateBase", "web_egress", "WebEgress").forEach {
                raw shouldNotContain
                    it
            }
            val code = SourceScan.blank(raw)
            Regex("""suspend fun (start\w+Egress)\(""").findAll(code).map { it.groupValues[1] }.toSet() shouldBe
                setOf("startTrackEgress", "startParticipantEgress", "startParticipantFileEgress", "startRoomCompositeEgress")
        }

        test("the recording is composed from raw tracks, never from a rendered client page") {
            val composer = EncounterSourceScan.mainFile("conference/FfmpegSegmentedComposer.kt").readText()
            composer shouldContain "ffmpeg"
            listOf("chrome", "chromium", "puppeteer", "playwright", "headless").forEach { composer.lowercase() shouldNotContain it }
        }

        test("no video-overlay client file mentions the timer (the compositor computes overlays INTO the camera track)") {
            val overlayFiles = clientFiles().filter { it.name.startsWith("ConferenceVideoOverlay") }
            (overlayFiles.size >= 4) shouldBe true
            overlayFiles.filter { it.readText().contains("timer", ignoreCase = true) }.map { it.name }.shouldBeEmpty()
        }

        test("the client timer files never capture, canvas-render or publish a track") {
            val timerFiles = clientFiles().filter { it.name.startsWith("ConferenceTimer") }
            (timerFiles.size >= 5) shouldBe true
            timerFiles.forEach { file ->
                val text = file.readText()
                listOf("captureStream", "publishTrack", "canvas", "createMediaStreamDestination", "getDisplayMedia").forEach {
                    if (text.contains(it)) error("${file.name} contains $it")
                }
            }
        }
    })
