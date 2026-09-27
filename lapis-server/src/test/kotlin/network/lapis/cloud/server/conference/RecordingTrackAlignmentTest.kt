package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * A/V-sync fix, round 2 (2026-09-27) -- pinned to the REAL numbers of the first ELB recording made
 * with round 1 (`93f378ba`) deployed (recording `80394924-4185-4f82-8cc6-03ee8d9b269c`, DB rows of
 * `conference_recording_track` + `ffprobe` of the raw files on the server):
 *
 * | track | started_at_epoch_nanos | duration_ms | ffprobe duration |
 * |-------|------------------------|-------------|------------------|
 * | mic   | 1790529971823572890    | 40312       | 39.927313 s      |
 * | cam   | 1790529971907704186    | 39905       | 39.804511 s      |
 *
 * Round 1 turned the two duration differences (0.385 s / 0.100 s) into offsets and thereby moved
 * audio 0.285 s later than video for no reason (see [RecordingTrackAlignment] KDoc). These tests
 * guard the round-2 contract: the composed offset is the `started_at` difference alone; the probe
 * numbers are diagnostics that must never leak into the offset again.
 */
class RecordingTrackAlignmentTest :
    FunSpec({
        val micStartedAt = 1_790_529_971_823_572_890L
        val camStartedAt = 1_790_529_971_907_704_186L
        val micReportedMs = 40_312L
        val camReportedMs = 39_905L
        val micProbed = 39.927313
        val camProbed = 39.804511
        val tolerance = 0.0005

        test("ELB 2026-09-27: startOffsetSeconds -- microphone is the earliest track (0.0), camera starts 0.084 s later") {
            RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = micStartedAt, t0EpochNanos = micStartedAt) shouldBe 0.0
            RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = camStartedAt, t0EpochNanos = micStartedAt) shouldBe
                (0.084131 plusOrMinus 0.000001)
        }

        test("startOffsetSeconds: unknown started_at -> 0.0 (the poller's historical fallback)") {
            RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = null, t0EpochNanos = micStartedAt) shouldBe 0.0
        }

        test("ELB 2026-09-27: offsetSeconds is the started_at difference ONLY -- mic 0.000, cam 0.084, relative shift 0.084") {
            val mic = RecordingTrackAlignment.offsetSeconds(startedAtEpochNanos = micStartedAt, t0EpochNanos = micStartedAt)
            val cam = RecordingTrackAlignment.offsetSeconds(startedAtEpochNanos = camStartedAt, t0EpochNanos = micStartedAt)
            mic shouldBe 0.0
            cam shouldBe (0.084 plusOrMinus tolerance)
            (cam - mic) shouldBe (0.084 plusOrMinus tolerance)
        }

        test(
            "ELB 2026-09-27: the round-1 formula (offset + reported - probed) would have produced mic 0.385 / cam 0.184, " +
                "i.e. a spurious -0.200 relative shift",
        ) {
            // Documented regression: this is what 93f378ba composed with, and what must NOT come back.
            val micDiscrepancy =
                RecordingTrackAlignment.durationDiscrepancySeconds(reportedDurationMs = micReportedMs, probedDurationSeconds = micProbed)!!
            val camDiscrepancy =
                RecordingTrackAlignment.durationDiscrepancySeconds(reportedDurationMs = camReportedMs, probedDurationSeconds = camProbed)!!
            val micRound1 =
                RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = micStartedAt, t0EpochNanos = micStartedAt) + micDiscrepancy
            val camRound1 =
                RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = camStartedAt, t0EpochNanos = micStartedAt) + camDiscrepancy
            micRound1 shouldBe (0.384687 plusOrMinus tolerance) // 0.000000 + (40.312 - 39.927313)
            camRound1 shouldBe (0.184620 plusOrMinus tolerance) // 0.084131 + (39.905 - 39.804511)
            (camRound1 - micRound1) shouldBe (-0.200067 plusOrMinus tolerance)
            // ... whereas the applied round-2 offsets keep the legitimate +0.084 s and nothing else:
            val camRound2 = RecordingTrackAlignment.offsetSeconds(startedAtEpochNanos = camStartedAt, t0EpochNanos = micStartedAt)
            val micRound2 = RecordingTrackAlignment.offsetSeconds(startedAtEpochNanos = micStartedAt, t0EpochNanos = micStartedAt)
            ((camRound2 - micRound2) - (camRound1 - micRound1)) shouldBe (0.284198 plusOrMinus tolerance)
        }

        test("ELB 2026-09-27: durationDiscrepancySeconds reproduces the logged diagnostics -- mic 0.385 s, cam 0.100 s") {
            RecordingTrackAlignment.durationDiscrepancySeconds(
                reportedDurationMs = micReportedMs,
                probedDurationSeconds = micProbed,
            ) shouldBe (0.385 plusOrMinus tolerance)
            RecordingTrackAlignment.durationDiscrepancySeconds(
                reportedDurationMs = camReportedMs,
                probedDurationSeconds = camProbed,
            ) shouldBe (0.100 plusOrMinus tolerance)
        }

        test(
            "durationDiscrepancySeconds: signed (a probe LONGER than reported is negative, not clamped), " +
                "null without both inputs or for a bad probe",
        ) {
            RecordingTrackAlignment.durationDiscrepancySeconds(reportedDurationMs = 22_496L, probedDurationSeconds = 22.516) shouldBe
                (-0.020 plusOrMinus tolerance)
            RecordingTrackAlignment.durationDiscrepancySeconds(reportedDurationMs = null, probedDurationSeconds = 21.676).shouldBeNull()
            RecordingTrackAlignment.durationDiscrepancySeconds(reportedDurationMs = 22_496L, probedDurationSeconds = null).shouldBeNull()
            RecordingTrackAlignment
                .durationDiscrepancySeconds(reportedDurationMs = 22_496L, probedDurationSeconds = Double.NaN)
                .shouldBeNull()
            RecordingTrackAlignment.durationDiscrepancySeconds(reportedDurationMs = 22_496L, probedDurationSeconds = -1.0).shouldBeNull()
        }

        test("FfprobeMediaProber.parseProbe: default=noprint_wrappers=1 output with start_time and duration") {
            val probe = FfprobeMediaProber.parseProbe("start_time=0.000000\nduration=39.927313\n")
            probe.durationSeconds shouldBe (39.927313 plusOrMinus 0.0000005)
            probe.startTimeSeconds shouldBe (0.0 plusOrMinus 0.0000005)
        }

        test("FfprobeMediaProber.parseProbe: N/A, negative Opus pre-skip start_time, missing keys, garbage") {
            FfprobeMediaProber.parseProbe("start_time=N/A\nduration=N/A\n") shouldBe RecordingMediaProbe.EMPTY
            FfprobeMediaProber.parseProbe("start_time=-0.006500\nduration=21.676000\n").let {
                it.startTimeSeconds shouldBe (-0.0065 plusOrMinus 0.0000005)
                it.durationSeconds shouldBe (21.676 plusOrMinus 0.0000005)
            }
            FfprobeMediaProber.parseProbe("duration=-3.0\n") shouldBe RecordingMediaProbe.EMPTY
            FfprobeMediaProber.parseProbe("") shouldBe RecordingMediaProbe.EMPTY
            FfprobeMediaProber.parseProbe("not ffprobe output at all\n") shouldBe RecordingMediaProbe.EMPTY
        }

        test("FfprobeMediaProber.deriveFfprobePath: sibling of the configured ffmpeg binary, bare name stays bare") {
            FfprobeMediaProber.deriveFfprobePath("ffmpeg") shouldBe "ffprobe"
            FfprobeMediaProber.deriveFfprobePath("/usr/bin/ffmpeg") shouldBe "/usr/bin/ffprobe"
            FfprobeMediaProber.deriveFfprobePath("/opt/ffmpeg") shouldBe "/opt/ffprobe"
            FfprobeMediaProber.deriveFfprobePath("/opt/tools/ffmpeg-7") shouldBe "/opt/tools/ffprobe-7"
            FfprobeMediaProber.deriveFfprobePath("/opt/tools/avconv") shouldBe "/opt/tools/ffprobe"
        }
    })
