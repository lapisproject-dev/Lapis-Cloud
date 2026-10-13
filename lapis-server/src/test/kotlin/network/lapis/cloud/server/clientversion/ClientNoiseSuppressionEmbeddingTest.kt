package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.114 -- where the noise suppression row stands, as a source scan (the DOM tests prove the behaviour, this keeps the next change from
 * quietly moving it): directly under the microphone selection of the conference's device block with no collapsible of its own, in the pulpit's
 * device panel only for an office holder (a speaker session), never touching the microphone button or the tables, and never through a device
 * call of its own (the tripwire of the encounter package owns that rule). Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private fun source(relative: String): String = File(CLIENT_DIR, relative).readText()

class ClientNoiseSuppressionEmbeddingTest :
    FunSpec({
        test("conference: the row is created right after the microphone selection and before the camera selection, in the device group") {
            val screen = source("ConferenceScreen.kt")
            val mic = screen.indexOf("val micDeviceSelect = deviceGroup.select(")
            val row = screen.indexOf("NoiseSuppressionSwitchRow(deviceGroup, noiseSuppressionController)")
            val camera = screen.indexOf("val cameraDeviceSelect = deviceGroup.select(")
            withClue("order mic < row < camera") { (mic in 0 until row && row in 0 until camera) shouldBe true }
            // exactly one row, no collapsible of its own
            Regex("""NoiseSuppressionSwitchRow\(""").findAll(screen).count() shouldBe 1
            val between = screen.substring(mic, camera)
            listOf("collapsible", "Collapsible", "toggleButton", "aria-expanded").forEach { between shouldNotContain it }
        }

        test(
            "conference: the chain comes from the session through a pointer set after the session is declared, and a published microphone re-applies the choice",
        ) {
            val screen = source("ConferenceScreen.kt")
            screen shouldContain "noiseChainProvider = { runCatching { session.noiseSuppressionChain() }.getOrNull() }"
            screen shouldContain "onLocalMicrophoneTrackPublished = {"
            screen shouldContain "noiseSuppressionController.onMicTrackPublished()"
            screen shouldContain "preference = NoiseSuppressionWarmup.preference"
            // V1.9.124: the instance default comes from the join token and only from there
            screen shouldContain "instanceDefault = joinToken.noiseSuppressionDefault"
        }

        test("encounter: the row is built only for an office holder (a speaker session) and only inside the device panel") {
            val picker = source("encounter/EncounterDevicePicker.kt")
            picker shouldContain "if (speaker != null && noiseSuppression != null) NoiseSuppressionSwitchRow(panel, noiseSuppression)"
            Regex("""NoiseSuppressionSwitchRow\(""").findAll(picker).count() shouldBe 1
            val room = source("encounter/EncounterRoom.kt")
            room shouldContain "noiseSuppression = pulpitControls?.noiseSuppression"
            room shouldContain "onMicTrackPublished = {"
            // the controller is built from the speaker session only
            val pulpit = source("encounter/EncounterPulpitControls.kt")
            pulpit shouldContain "chain = { session.noiseSuppressionChain() }"
            pulpit shouldContain "private val session: EncounterSpeakerSession"
            // the congregation's table session has no filter
            val tables = source("encounter/EncounterTables.kt")
            tables shouldNotContain "noiseSuppression"
            tables shouldNotContain "NoiseSuppression"
        }

        test("encounter: the pulpit controls keep the aria-pressed microphone button (no new glyph, no new button)") {
            val pulpit = source("encounter/EncounterPulpitControls.kt")
            pulpit shouldContain "micButton.setAttrIfChanged(\"aria-pressed\", micOn.toString())"
            Regex("""encounterControlButton\(""").findAll(pulpit).count() shouldBe 2
        }

        test("the filter and its storage key are named the same in the conference and in the encounter room (one choice per browser)") {
            val assets = source("ConferenceNoiseSuppression.kt")
            assets shouldContain "const val NOISE_SUPPRESSION_STORAGE_KEY = \"lapis.audio.noiseSuppression.v1\""
            // no second key anywhere in the encounter package (its tripwire also forbids a key naming encounter and device)
            File(CLIENT_DIR, "encounter").listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
                withClue(
                    "${file.name} must not define its own noise key",
                ) { file.readText() shouldNotContain "lapis.audio.noiseSuppression" }
            }
        }

        test(
            "V1.9.124: the audio context is started inside every join click and inside the microphone click, right after the tones' unlock",
        ) {
            val screen = source("ConferenceScreen.kt")
            // the three join clicks (start button, room card, guest consent) carry both unlocks, in this order
            Regex("""ConferenceJoinLeaveSounds\.unlockFromGesture\(\)\s+NoiseSuppressionWarmup\.unlockFromGesture\(\)""")
                .findAll(screen)
                .count() shouldBe 3
            // the fourth call is the microphone button's click handler, before the toggle itself
            Regex("""micButton\.onClick \{[^}]*NoiseSuppressionWarmup\.unlockFromGesture\(\)[^}]*toggleMic\(\)""")
                .containsMatchIn(screen) shouldBe true
            Regex("""NoiseSuppressionWarmup\.unlockFromGesture\(""").findAll(screen).count() shouldBe 4
            // the lobby remembers the default it was told about
            screen shouldContain "NoiseSuppressionWarmup.remember(availability.noiseSuppressionDefault)"
        }

        test("V1.9.124: the encounter room knows nothing of the instance default, its warm-up or its join-token field") {
            File(CLIENT_DIR, "encounter").listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                withClue("${file.name} must not use the instance default") {
                    text shouldNotContain "instanceDefault"
                    text shouldNotContain "noiseSuppressionDefault"
                    text shouldNotContain "NoiseSuppressionWarmup"
                }
            }
        }

        test(
            "V1.9.124: the server never sets the default in the encounter room's join answers, and the generic settings update never writes it",
        ) {
            val serverDir =
                File("src/main/kotlin/network/lapis/cloud/server").let {
                    if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
                }
            val encounter = File(serverDir, "rpc/EncounterSpaceService.kt").readText()
            encounter shouldNotContain "noiseSuppressionDefault"
            encounter shouldNotContain "NoiseSuppressionDefaultStore"
            // the generic write-set has no line for the column: its only write is inside updateNoiseSuppressionDefault
            val settings = File(serverDir, "rpc/OrganizationSettingsService.kt").readText()
            Regex("""it\[noiseSuppressionDefault\]\s*=""").findAll(settings).count() shouldBe 1
            val dedicated = settings.indexOf("override suspend fun updateNoiseSuppressionDefault")
            val generic = settings.indexOf("override suspend fun updateOrganizationSettings")
            val write = settings.indexOf("it[noiseSuppressionDefault] =")
            withClue("the only write sits in the dedicated method, which comes before the generic one") {
                (dedicated in 0 until write && write < generic) shouldBe true
            }
        }
    })
