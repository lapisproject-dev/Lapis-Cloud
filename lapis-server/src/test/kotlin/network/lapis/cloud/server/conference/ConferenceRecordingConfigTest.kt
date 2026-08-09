package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/**
 * Exercises [ConferenceRecordingConfig.load] purely through its injected `env` function -- never
 * `System.getenv` (see that class's KDoc for why). No network, no filesystem, no process spawn --
 * see class KDoc "load is string validation ONLY". [ConferenceRecordingConfig.probeFfmpegAvailable]
 * (real I/O) is deliberately NOT exercised here -- it is a startup-only concern owned by
 * `Application.module`, not `load()`.
 */
class ConferenceRecordingConfigTest :
    FunSpec({
        test("everything unset -> disabled, sane defaults, no failure") {
            val config = ConferenceRecordingConfig.load(envOf())

            config.enabled.shouldBeFalse()
            config.outputContainerDir shouldBe "/out"
            config.outputHostDir shouldBe "deploy/local/egress-out"
            config.ffmpegPath shouldBe "ffmpeg"
            config.pollIntervalSeconds shouldBe 10L
            config.egressTimeoutMinutes shouldBe 30L
            config.maxDurationMinutes shouldBe 240L
            config.composeTimeoutMinutes shouldBe 120L
            config.maxTracks shouldBe 60
            config.keepRaw.shouldBeFalse()
        }

        test("LAPIS_RECORDING_ENABLED=true -> enabled") {
            val config = ConferenceRecordingConfig.load(envOf("LAPIS_RECORDING_ENABLED" to "true"))
            config.enabled.shouldBeTrue()
        }

        test("LAPIS_RECORDING_ENABLED is case-insensitive") {
            ConferenceRecordingConfig.load(envOf("LAPIS_RECORDING_ENABLED" to "TRUE")).enabled.shouldBeTrue()
            ConferenceRecordingConfig.load(envOf("LAPIS_RECORDING_ENABLED" to "True")).enabled.shouldBeTrue()
        }

        test("any value other than 'true' -> disabled, no failure") {
            ConferenceRecordingConfig.load(envOf("LAPIS_RECORDING_ENABLED" to "yes")).enabled.shouldBeFalse()
            ConferenceRecordingConfig.load(envOf("LAPIS_RECORDING_ENABLED" to "1")).enabled.shouldBeFalse()
            ConferenceRecordingConfig.load(envOf("LAPIS_RECORDING_ENABLED" to "")).enabled.shouldBeFalse()
        }

        test("LAPIS_RECORDING_KEEP_RAW=true -> keepRaw true, independent of LAPIS_RECORDING_ENABLED") {
            val config =
                ConferenceRecordingConfig.load(
                    envOf("LAPIS_RECORDING_KEEP_RAW" to "true"),
                )
            config.keepRaw.shouldBeTrue()
            config.enabled.shouldBeFalse()
        }

        test("custom output dirs and ffmpeg path override the defaults") {
            val config =
                ConferenceRecordingConfig.load(
                    envOf(
                        "LAPIS_EGRESS_OUTPUT_CONTAINER_DIR" to "/custom-out",
                        "LAPIS_EGRESS_OUTPUT_HOST_DIR" to "/srv/egress-out",
                        "LAPIS_FFMPEG_PATH" to "/usr/local/bin/ffmpeg",
                    ),
                )

            config.outputContainerDir shouldBe "/custom-out"
            config.outputHostDir shouldBe "/srv/egress-out"
            config.ffmpegPath shouldBe "/usr/local/bin/ffmpeg"
        }

        test("blank overrides fall back to defaults, not an empty string") {
            val config =
                ConferenceRecordingConfig.load(
                    envOf(
                        "LAPIS_EGRESS_OUTPUT_CONTAINER_DIR" to "   ",
                        "LAPIS_FFMPEG_PATH" to "",
                    ),
                )

            config.outputContainerDir shouldBe "/out"
            config.ffmpegPath shouldBe "ffmpeg"
        }

        test("custom numeric fields are parsed") {
            val config =
                ConferenceRecordingConfig.load(
                    envOf(
                        "LAPIS_RECORDING_POLL_INTERVAL_SECONDS" to "5",
                        "LAPIS_RECORDING_EGRESS_TIMEOUT_MINUTES" to "15",
                        "LAPIS_RECORDING_MAX_DURATION_MINUTES" to "120",
                        "LAPIS_RECORDING_COMPOSE_TIMEOUT_MINUTES" to "60",
                        "LAPIS_RECORDING_MAX_TRACKS" to "30",
                    ),
                )

            config.pollIntervalSeconds shouldBe 5L
            config.egressTimeoutMinutes shouldBe 15L
            config.maxDurationMinutes shouldBe 120L
            config.composeTimeoutMinutes shouldBe 60L
            config.maxTracks shouldBe 30
        }

        test("unparseable numeric fields fall back to defaults rather than crashing") {
            val config =
                ConferenceRecordingConfig.load(
                    envOf(
                        "LAPIS_RECORDING_POLL_INTERVAL_SECONDS" to "not-a-number",
                        "LAPIS_RECORDING_MAX_TRACKS" to "also-not-a-number",
                    ),
                )

            config.pollIntervalSeconds shouldBe 10L
            config.maxTracks shouldBe 60
        }

        test("toString includes every field -- nothing secret to redact, see class KDoc") {
            val config =
                ConferenceRecordingConfig.load(
                    envOf("LAPIS_RECORDING_ENABLED" to "true", "LAPIS_FFMPEG_PATH" to "/opt/ffmpeg"),
                )

            config.toString() shouldBe
                "ConferenceRecordingConfig(enabled=true, outputContainerDir='/out', " +
                "outputHostDir='deploy/local/egress-out', ffmpegPath='/opt/ffmpeg', pollIntervalSeconds=10, " +
                "egressTimeoutMinutes=30, maxDurationMinutes=240, composeTimeoutMinutes=120, maxTracks=60, keepRaw=false)"
        }

        // ── probeFfmpegAvailable (real process spawn -- limited, environment-tolerant assertions) ──

        test("probeFfmpegAvailable returns false for a definitely-nonexistent binary, never throws") {
            ConferenceRecordingConfig.probeFfmpegAvailable("this-binary-definitely-does-not-exist-anywhere-12345").shouldBeFalse()
        }

        test("probeFfmpegAvailable returns true for a trivially-successful command standing in for ffmpeg") {
            // Not asserting on the real `ffmpeg` binary (may or may not be installed on the machine
            // running this test) -- `true` (the POSIX no-op command, exit code 0) exercises the
            // exact same ProcessBuilder("<path>", "-version") code path successfully without a real
            // ffmpeg dependency in this hermetic test suite.
            ConferenceRecordingConfig.probeFfmpegAvailable("true").shouldBeTrue()
        }

        test("probeFfmpegAvailable returns false for a command that exits non-zero") {
            ConferenceRecordingConfig.probeFfmpegAvailable("false").shouldBeFalse()
        }
    })
