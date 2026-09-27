package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * A/V-sync fix (2026-09-27) -- realistic nanosecond timestamps modelled on a real LiveKit
 * `ListEgress` sample (see `LiveKitEgressInfo` KDoc: `started_at` 1786260219805661967,
 * `duration` 22496117368). The scenario throughout: one participant's microphone egress receives
 * its first packet at `t0`; the camera egress receives ITS first packet 300 ms later (egress join/
 * subscribe latency differs per egress process) and then loses the head of the stream before the
 * first written frame (0.82 s of video received but never muxed).
 */
class RecordingTrackAlignmentTest :
    FunSpec({
        val t0 = 1_786_260_219_805_661_967L // audio: first packet
        val videoStartedAt = t0 + 300_000_000L // video: first packet 300 ms later
        val tolerance = 0.0005

        test("startOffsetSeconds: video starting 300 ms after the earliest track -> 0.300, earliest track -> 0.0, unknown start -> 0.0") {
            RecordingTrackAlignment.startOffsetSeconds(
                startedAtEpochNanos = videoStartedAt,
                t0EpochNanos = t0,
            ) shouldBe (0.300 plusOrMinus tolerance)
            RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = t0, t0EpochNanos = t0) shouldBe 0.0
            RecordingTrackAlignment.startOffsetSeconds(startedAtEpochNanos = null, t0EpochNanos = t0) shouldBe 0.0
        }

        test("headLossSeconds: reported 22.496 s vs probed 21.676 s (0.82 s never written) -> 0.820") {
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 22_496L, probedDurationSeconds = 21.676) shouldBe
                (0.820 plusOrMinus tolerance)
        }

        test("headLossSeconds: audio whose ogg spans the full reported duration (one 20 ms packet longer) -> 0.0, never negative") {
            // ffprobe reports last_pts + packet duration; LiveKit reports last_pts -- the probe is
            // routinely a few ms LONGER than the reported duration for a lossless track.
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 22_496L, probedDurationSeconds = 22.516) shouldBe 0.0
        }

        test("headLossSeconds: sub-threshold difference (one 33 ms video frame of muxer rounding) -> 0.0") {
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 22_496L, probedDurationSeconds = 22.480) shouldBe 0.0
        }

        test("headLossSeconds: no correction without both inputs, or for a non-finite/negative probe") {
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = null, probedDurationSeconds = 21.676) shouldBe 0.0
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 22_496L, probedDurationSeconds = null) shouldBe 0.0
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 22_496L, probedDurationSeconds = Double.NaN) shouldBe 0.0
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 22_496L, probedDurationSeconds = -1.0) shouldBe 0.0
        }

        test("headLossSeconds: an implausible head (> MAX_HEAD_LOSS_SECONDS) is refused rather than applied") {
            RecordingTrackAlignment.headLossSeconds(reportedDurationMs = 60_000L, probedDurationSeconds = 40.0) shouldBe 0.0
            RecordingTrackAlignment.headLossSeconds(
                reportedDurationMs = 60_000L,
                probedDurationSeconds = 50.5,
            ) shouldBe (9.5 plusOrMinus tolerance)
        }

        test("offsetSeconds: the composed video offset is start offset PLUS head loss (0.300 + 0.820 = 1.120), audio stays at 0.0") {
            RecordingTrackAlignment.offsetSeconds(
                startedAtEpochNanos = videoStartedAt,
                t0EpochNanos = t0,
                reportedDurationMs = 22_196L,
                probedDurationSeconds = 21.376,
            ) shouldBe (1.120 plusOrMinus tolerance)
            RecordingTrackAlignment.offsetSeconds(
                startedAtEpochNanos = t0,
                t0EpochNanos = t0,
                reportedDurationMs = 22_496L,
                probedDurationSeconds = 22.516,
            ) shouldBe 0.0
        }

        test("offsetSeconds without a probe degrades to exactly the pre-fix behaviour (start offset only)") {
            RecordingTrackAlignment.offsetSeconds(
                startedAtEpochNanos = videoStartedAt,
                t0EpochNanos = t0,
                reportedDurationMs = 22_196L,
                probedDurationSeconds = null,
            ) shouldBe (0.300 plusOrMinus tolerance)
        }

        test("FfprobeMediaProber.parseDuration: plain csv line, trailing comma, N/A, garbage") {
            FfprobeMediaProber.parseDuration("21.676000\n") shouldBe (21.676 plusOrMinus tolerance)
            FfprobeMediaProber.parseDuration("21.676000,\n") shouldBe (21.676 plusOrMinus tolerance)
            FfprobeMediaProber.parseDuration("N/A\n") shouldBe null
            FfprobeMediaProber.parseDuration("") shouldBe null
            FfprobeMediaProber.parseDuration("-3.0\n") shouldBe null
        }

        test("FfprobeMediaProber.deriveFfprobePath: sibling of the configured ffmpeg binary, bare name stays bare") {
            FfprobeMediaProber.deriveFfprobePath("ffmpeg") shouldBe "ffprobe"
            FfprobeMediaProber.deriveFfprobePath("/usr/bin/ffmpeg") shouldBe "/usr/bin/ffprobe"
            FfprobeMediaProber.deriveFfprobePath("/opt/ffmpeg") shouldBe "/opt/ffprobe"
            FfprobeMediaProber.deriveFfprobePath("/opt/tools/ffmpeg-7") shouldBe "/opt/tools/ffprobe-7"
            FfprobeMediaProber.deriveFfprobePath("/opt/tools/avconv") shouldBe "/opt/tools/ffprobe"
        }
    })
