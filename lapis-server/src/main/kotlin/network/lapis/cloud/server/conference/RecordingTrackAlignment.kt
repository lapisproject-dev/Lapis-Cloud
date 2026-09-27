package network.lapis.cloud.server.conference

/**
 * A/V-sync fix (ELB test recording 2026-09-27: ~1 s audio/video offset plus a truncated start) --
 * the PURE timestamp arithmetic that places one raw per-track egress file on the composed output's
 * timeline. Split out of `RecordingPoller.composeOne` so it is unit-testable with realistic
 * nanosecond timestamps (`RecordingTrackAlignmentTest`) without a DB, LiveKit, or ffmpeg.
 *
 * ## Two anchors per track, not one
 *
 * LiveKit Track Egress (v1.13.0, `pkg/pipeline/source/sdk` + `server-sdk-go/pkg/synchronizer`)
 * reports per file:
 *
 * - `started_at` = receive time of the FIRST RTP packet of that track (the synchronizer's
 *   `startedAt`, set in `TrackSynchronizer.initialize`); the first packet is given PTS ~0 relative
 *   to it, so the file's own timeline is anchored at `started_at` ...
 * - ... EXCEPT that the muxer (GStreamer `mp4mux`/`oggmux`) writes the file's timeline relative to
 *   the first sample that was actually WRITTEN, not the first packet received. `qtmux` in
 *   particular shifts a single-track file so its first written sample sits at t=0 and emits NO edit
 *   list for the gap (`gst_qt_mux_update_edit_lists`: `has_gap` is only ever true for a pad that
 *   starts later than ANOTHER pad of the same file). Every packet the pipeline received but never
 *   wrote (a keyframe whose first packet was lost, frames the depayloader/parser discarded before
 *   the first decodable sample, ...) therefore silently vanishes from the head of the file while
 *   `started_at` still points at the first received packet. The composer's own
 *   `setpts=PTS-STARTPTS+offset` cannot see that head either -- it re-zeros whatever the file's
 *   first timestamp is.
 * - `duration` = `ended_at - started_at`, where `ended_at = started_at + maxPTS` and `maxPTS` is
 *   the largest PTS the synchronizer assigned to any RECEIVED packet (`Synchronizer.End`). So the
 *   reported duration spans "first received packet .. last received packet" on the
 *   receive-time-anchored timeline, INDEPENDENT of what was written.
 *
 * Hence: `reportedDuration - probedMediaDuration` = the length of the head that was received but
 * never written (up to one sample duration of bias, see [headLossSeconds]). Adding that to the
 * track's `started_at`-derived offset re-aligns the file's first written sample with the wall-clock
 * instant it was actually captured -- the correction is exact whether or not the muxer preserved
 * the head as an edit list, because the composer re-zeros the file's own start either way.
 *
 * A probe that failed ([probedDurationSeconds] `null`) or a nonsensical result (negative head, or a
 * head larger than [MAX_HEAD_LOSS_SECONDS]) yields NO correction -- degrade to the previous
 * behaviour, never to a wild offset; the caller logs the discrepancy so a real recording's raw
 * files can be inspected against these numbers.
 */
object RecordingTrackAlignment {
    /**
     * Ceiling on a plausible head loss. A track whose first written sample is more than this many
     * seconds after its first received packet is not a "lost keyframe" but something else entirely
     * (a broken file, a probe that measured the wrong thing) -- do not silently shift such a track.
     */
    const val MAX_HEAD_LOSS_SECONDS = 10.0

    /** Below this the head loss is indistinguishable from muxer sample-duration rounding -- treated as zero. */
    const val MIN_HEAD_LOSS_SECONDS = 0.020

    /**
     * This track's `started_at`-derived offset relative to the recording's earliest track
     * `t0`. `null` (no `started_at` recorded) -> 0.0 -- same fallback the poller has always used.
     */
    fun startOffsetSeconds(
        startedAtEpochNanos: Long?,
        t0EpochNanos: Long,
    ): Double = if (startedAtEpochNanos == null) 0.0 else (startedAtEpochNanos - t0EpochNanos) / 1_000_000_000.0

    /**
     * Seconds of media received before the first WRITTEN sample -- see class KDoc. `0.0` whenever
     * the inputs do not support a correction: missing duration, failed probe, negative difference
     * (probed longer than reported -- e.g. a trailing muxer flush), sub-threshold difference, or a
     * difference above [MAX_HEAD_LOSS_SECONDS].
     */
    fun headLossSeconds(
        reportedDurationMs: Long?,
        probedDurationSeconds: Double?,
    ): Double {
        if (reportedDurationMs == null || probedDurationSeconds == null) return 0.0
        if (!probedDurationSeconds.isFinite() || probedDurationSeconds < 0.0) return 0.0
        val diff = reportedDurationMs / 1000.0 - probedDurationSeconds
        if (diff < MIN_HEAD_LOSS_SECONDS || diff > MAX_HEAD_LOSS_SECONDS) return 0.0
        return diff
    }

    /** [startOffsetSeconds] + [headLossSeconds] -- the value handed to the composer as `offsetSeconds`. */
    fun offsetSeconds(
        startedAtEpochNanos: Long?,
        t0EpochNanos: Long,
        reportedDurationMs: Long?,
        probedDurationSeconds: Double?,
    ): Double =
        startOffsetSeconds(startedAtEpochNanos = startedAtEpochNanos, t0EpochNanos = t0EpochNanos) +
            headLossSeconds(reportedDurationMs = reportedDurationMs, probedDurationSeconds = probedDurationSeconds)
}
