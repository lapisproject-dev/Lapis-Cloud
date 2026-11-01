package network.lapis.cloud.server.conference

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** One pause of a recording as stored in `conference_recording_pause` (UTC wall-clock). [seq] is 1-based: pause `n` ends stage `n - 1`. */
data class RecordingPauseSpan(
    val seq: Int,
    val pausedAt: LocalDateTime,
    val resumedAt: LocalDateTime?,
)

/** The start of one track row of a given stage, as far as the cut computation needs it. */
data class StageTrackStart(
    val stage: Int,
    val startedAtEpochNanos: Long?,
)

/**
 * V1.9.254 -- turns the pauses of a recording into the stretches that are cut out of the composed file.
 *
 * All values are WALL-CLOCK seconds relative to `t0` (the earliest track start, the zero of the composition timeline).
 *
 *  * start = `paused_at` (UTC instant) minus `t0`; whatever the stopping egresses of the old stage still wrote afterwards (1-3 s) is cut too.
 *  * end   = the later of `resumed_at` and the first start of a track of the NEXT stage (the new egresses need a moment), minus `t0`; with
 *    no track of the next stage `resumed_at`; for a pause that is still open (stop in the pause) the end of the recording.
 *
 * The server clock and the LiveKit clock (`started_at_epoch_nanos`) are assumed equal (same host); a skew shifts the cut by exactly that
 * skew. The UTC zone is spelled out -- the process zone must never enter this arithmetic.
 */
object RecordingPauseCuts {
    fun compute(
        pauses: List<RecordingPauseSpan>,
        tracks: List<StageTrackStart>,
        t0EpochNanos: Long,
        totalSeconds: Double,
    ): List<TimeRange> {
        if (pauses.isEmpty() || !totalSeconds.isFinite() || totalSeconds <= 0.0) return emptyList()
        val result = ArrayList<TimeRange>()
        for (pause in pauses.sortedBy { it.seq }) {
            val start = secondsRelative(at = pause.pausedAt, t0EpochNanos = t0EpochNanos)
            val firstOfNextStage =
                tracks
                    .filter { it.stage == pause.seq }
                    .mapNotNull { it.startedAtEpochNanos }
                    .minOrNull()
                    ?.let { (it - t0EpochNanos) / NANOS_PER_SECOND }
            val resumed = pause.resumedAt?.let { secondsRelative(at = it, t0EpochNanos = t0EpochNanos) }
            val end =
                when {
                    resumed == null -> totalSeconds
                    firstOfNextStage == null -> resumed
                    else -> maxOf(resumed, firstOfNextStage)
                }
            val clippedStart = start.coerceIn(0.0, totalSeconds)
            val clippedEnd = end.coerceIn(0.0, totalSeconds)
            if (clippedEnd > clippedStart) result += TimeRange(startSeconds = clippedStart, endSeconds = clippedEnd)
        }
        return merge(result)
    }

    /** Total length of [cuts] after merging overlaps and clamping to `[0, totalSeconds]`. */
    fun totalLength(
        cuts: List<TimeRange>,
        totalSeconds: Double,
    ): Double =
        merge(
            cuts.map {
                TimeRange(
                    startSeconds = it.startSeconds.coerceIn(0.0, totalSeconds),
                    endSeconds = it.endSeconds.coerceIn(0.0, totalSeconds),
                )
            },
        ).sumOf { it.lengthSeconds }

    private fun merge(ranges: List<TimeRange>): List<TimeRange> {
        val sorted = ranges.filter { it.lengthSeconds > 0.0 }.sortedBy { it.startSeconds }
        val merged = ArrayList<TimeRange>()
        for (r in sorted) {
            val last = merged.lastOrNull()
            if (last != null && r.startSeconds <= last.endSeconds) {
                merged[merged.size - 1] = TimeRange(startSeconds = last.startSeconds, endSeconds = maxOf(last.endSeconds, r.endSeconds))
            } else {
                merged += r
            }
        }
        return merged
    }

    private fun secondsRelative(
        at: LocalDateTime,
        t0EpochNanos: Long,
    ): Double {
        val instant = at.toInstant(TimeZone.UTC)
        val nanos = instant.epochSeconds * NANOS_PER_SECOND.toLong() + instant.nanosecondsOfSecond
        return (nanos - t0EpochNanos) / NANOS_PER_SECOND
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
