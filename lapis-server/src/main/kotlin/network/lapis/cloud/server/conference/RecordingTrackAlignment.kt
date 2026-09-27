package network.lapis.cloud.server.conference

/**
 * The PURE timestamp arithmetic that places one raw per-track egress file on the composed output's
 * timeline. Split out of `RecordingPoller.composeOne` so it is unit-testable with realistic
 * nanosecond timestamps (`RecordingTrackAlignmentTest`) without a DB, LiveKit, or ffmpeg.
 *
 * ## One anchor per track: the file's `started_at`
 *
 * A track's composed `offsetSeconds` is `started_at(track) - started_at(earliest track)` and
 * nothing else ([offsetSeconds] == [startOffsetSeconds]). Source-verified against LiveKit Egress
 * v1.13.0 (`livekit/egress` tag `v1.13.0`, `livekit/server-sdk-go` `50e969e`, 2026-09-27 round 2):
 *
 * - `EgressInfo.file_results[0].started_at` is the synchronizer's `startedAt`
 *   (`pkg/pipeline/watch.go:276` -> `controller.updateStartTime(c.src.GetStartedAt())` ->
 *   `FileInfo.StartedAt`), which `TrackSynchronizer.initialize` sets to the RECEIVE time of the
 *   first RTP packet of the track (`pkg/synchronizer/track.go`, `getOrSetStartedAt(receivedAt)`).
 *   Track Egress has no start gate (`pkg/pipeline/source/sdk.go`: `WithStartGate()` only for
 *   RoomComposite/Template), so the very first packet initializes. That first packet is given PTS
 *   ~0 (`getPTSWithRebase`: `pts = max(1ns, receivedAt - startTime)` for the first emitted packet),
 *   i.e. the file's own PTS timeline is anchored at exactly this wall-clock instant.
 * - `EgressInfo.started_at` (the JOB-level field) is `time.Now()` when the egress process builds
 *   its `PipelineConfig` from the `StartEgress` request (`pkg/config/pipeline.go:208`) -- BEFORE
 *   joining the room, subscribing, or receiving anything. `RecordingPoller.handleStopping` stores
 *   the file-level value and falls back to the job-level one only if the former is `0`/missing.
 *
 * Placing tracks by the difference of their file-level `started_at` is exactly what LiveKit's own
 * multi-track synchronizer does inside ONE egress (`currentPTSOffset = startedAt - firstStartedAt`
 * in `TrackSynchronizer.initialize`), so this mirrors Track Composite's anchoring across separate
 * Track Egress processes -- minus LiveKit's cross-track sender-report drift alignment, which
 * separate processes cannot share.
 *
 * ## Why `reported duration - probed media duration` is NOT applied (round 2, 2026-09-27)
 *
 * Round 1 of this fix (`93f378ba`) added `reportedDurationMs/1000 - ffprobe(duration)` to the start
 * offset as a "head loss" (media received before the first written sample). The first real ELB
 * recording with that code still had an A/V offset, and its numbers refute the premise:
 *
 * | track | `started_at` (ns)     | reported ms | probed s  | difference |
 * |-------|-----------------------|-------------|-----------|------------|
 * | mic   | 1790529971823572890   | 40312       | 39.927313 | 0.385 s    |
 * | cam   | 1790529971907704186   | 39905       | 39.804511 | 0.100 s    |
 *
 * - `FileInfo.Duration = EndedAt - StartedAt` where `EndedAt = max(sync.endedAt, pipelineEndedAt)`
 *   (`controller.updateEndTime`, `controller.go:933-959`). `sync.endedAt = startedAt + maxPTS`
 *   (last received packet), but `pipelineEndedAt = startedAt + <GStreamer pipeline running time
 *   at EOS>` (`watch.go:133`) is a WALL-CLOCK floor: it grows with the StopEgress -> drain -> EOS
 *   flush latency AFTER the last packet. So the reported duration is routinely LONGER than the
 *   media that exists on either end of the file, and `reported - probed` measures (mostly) that
 *   tail latency -- which differs between an audio and a video pipeline -- not a lost head.
 * - No head-loss mechanism exists for these codecs on the receive-anchored timeline: `rtpvp8depay`
 *   runs with `wait-for-keyframe=false` (GStreamer default, egress sets nothing else) and Opus has
 *   no keyframes; the appwriter's sample queue holds 100 samples (2 s of audio) while the pipeline
 *   reaches PLAYING, and `webmmux` (`offset-to-zero=false`) and `oggmux` both write ABSOLUTE
 *   running times, so a first written sample at PTS `h > 0` would show up as the raw file's own
 *   `start_time`, not as a shorter duration.
 *
 * Applying the difference shifted the ELB microphone by +0.385 s and the camera by +0.100 s -- a
 * spurious 0.285 s relative shift (audio later than video) ON TOP of whatever offset existed
 * before. The probe stays as a DIAGNOSTIC ([durationDiscrepancySeconds], plus the raw file's own
 * `start_time`, both logged by the poller) so the next real recording tells whether any genuine
 * head loss (`start_time > 0`) ever occurs -- it never changes the composition.
 */
object RecordingTrackAlignment {
    /**
     * This track's `started_at`-derived offset relative to the recording's earliest track
     * `t0`. `null` (no `started_at` recorded) -> 0.0 -- same fallback the poller has always used.
     */
    fun startOffsetSeconds(
        startedAtEpochNanos: Long?,
        t0EpochNanos: Long,
    ): Double = if (startedAtEpochNanos == null) 0.0 else (startedAtEpochNanos - t0EpochNanos) / 1_000_000_000.0

    /**
     * DIAGNOSTIC ONLY -- `reportedDuration - probedDuration` in seconds, signed, or `null` when
     * either input is missing or the probe is not a finite non-negative number. Positive values are
     * dominated by the egress's EOS-flush tail (see class KDoc); it is logged, never applied.
     */
    fun durationDiscrepancySeconds(
        reportedDurationMs: Long?,
        probedDurationSeconds: Double?,
    ): Double? {
        if (reportedDurationMs == null || probedDurationSeconds == null) return null
        if (!probedDurationSeconds.isFinite() || probedDurationSeconds < 0.0) return null
        return reportedDurationMs / 1000.0 - probedDurationSeconds
    }

    /** The value handed to the composer as `offsetSeconds` -- exactly [startOffsetSeconds], see class KDoc. */
    fun offsetSeconds(
        startedAtEpochNanos: Long?,
        t0EpochNanos: Long,
    ): Double = startOffsetSeconds(startedAtEpochNanos = startedAtEpochNanos, t0EpochNanos = t0EpochNanos)
}
