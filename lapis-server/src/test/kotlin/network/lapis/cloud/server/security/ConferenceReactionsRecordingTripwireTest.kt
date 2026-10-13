package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan

/**
 * V1.9.127 -- reactions and raised-hand badges are DOM overlays of the client, so they are NOT in a recording or a stream. That holds only
 * as long as nothing renders the Lapis client page into the broadcast: the egress client knows track, participant and room-composite egress
 * with LiveKit's built-in layout and has no web / template path. These tests fail the build the day somebody adds one, so the documented
 * statement ("not in the recording / stream") cannot silently become false.
 */
class ConferenceReactionsRecordingTripwireTest :
    FunSpec({
        test("LiveKitEgressClient has no custom template / web-page egress") {
            // The RAW text on purpose: the wire names live in string literals, which SourceScan.blank() empties.
            val code = EncounterSourceScan.mainFile("conference/LiveKitEgressClient.kt").readText()
            listOf("custom_base_url", "template_base", "customBaseUrl", "templateBase", "web_egress", "WebEgress").forEach {
                code shouldNotContain it
            }
        }

        test("the egress client offers exactly the four known egress kinds (a fifth one forces a decision about the overlays)") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("conference/LiveKitEgressClient.kt").readText())
            val starts = Regex("""suspend fun (start\w+Egress)\(""").findAll(code).map { it.groupValues[1] }.toSet()
            starts shouldBe setOf("startTrackEgress", "startParticipantEgress", "startParticipantFileEgress", "startRoomCompositeEgress")
        }

        test("the recording is composed from raw tracks (RecordingComposer / FfmpegSegmentedComposer), never from a rendered client page") {
            val composer = EncounterSourceScan.mainFile("conference/FfmpegSegmentedComposer.kt").readText()
            composer shouldContain "ffmpeg"
            listOf("chrome", "chromium", "puppeteer", "playwright", "headless").forEach { composer.lowercase() shouldNotContain it }
        }
    })
