package network.lapis.cloud.server.conference

import kotlin.math.ceil
import kotlin.math.max

/**
 * V1.9.115 -- the ONE mapping from a position in an audio file's own (media) timeline to the recording's wall clock. The composer places
 * audio on the wall clock with exactly this arithmetic ([FfmpegSegmentedComposer]) and the speaker timeline is placed with the very same
 * function, so the large tile and the sound cannot drift apart (pitfall 4 of the V1.9.115 plan): `offset + media / tempo`, where [tempo]
 * is the `atempo` ratio (`media / wall`, see [AudioTempo]) or `null` for none.
 */
object AudioWallClock {
    fun toWallSeconds(
        offsetSeconds: Double,
        mediaSeconds: Double,
        tempo: Double?,
    ): Double = offsetSeconds + mediaSeconds / (tempo ?: 1.0)
}

/** One measurement of the loudness of an audio file: the RMS level in dBFS of the window `[timeSeconds, timeSeconds + windowSeconds)`. */
data class AudioLevelPoint(
    val timeSeconds: Double,
    val levelDb: Double,
)

/** The loudness of a file in consecutive windows of [windowSeconds]. */
data class AudioLevelSeries(
    val points: List<AudioLevelPoint>,
    val windowSeconds: Double,
)

/** A stretch of speech on the recording's wall clock with its (RMS) loudness in dBFS. Never carries a name or an identity. */
data class SpeechWindow(
    val range: TimeRange,
    val levelDb: Double,
)

/**
 * Loudness-based speech detection (NOT speech recognition -- a cough, a bang or a loud neighbour counts as speech; documented limit).
 *
 * The noise floor is the 10th percentile of the file's own levels, a window counts as speech when its level is above
 * `max(floor + 15 dB, -50 dBFS)`. So a quiet room does not make its own noise "speech" and a file that is quiet throughout contains none.
 */
object SpeechActivity {
    const val MARGIN_ABOVE_FLOOR_DB = 15.0
    const val ABSOLUTE_MINIMUM_DB = -50.0
    const val FLOOR_PERCENTILE = 0.10

    /** Silence (`-inf`) is read as this level. */
    const val SILENCE_DB = -120.0

    fun threshold(series: AudioLevelSeries): Double {
        val levels =
            series.points
                .map { it.levelDb }
                .filter { it.isFinite() }
                .sorted()
        if (levels.isEmpty()) return Double.POSITIVE_INFINITY
        val floor = levels[((levels.size - 1) * FLOOR_PERCENTILE).toInt().coerceIn(0, levels.size - 1)]
        return max(floor + MARGIN_ABOVE_FLOOR_DB, ABSOLUTE_MINIMUM_DB)
    }

    /** The speech windows of [series], placed on the wall clock with [offsetSeconds] and [tempo] (see [AudioWallClock]). */
    fun detect(
        series: AudioLevelSeries,
        offsetSeconds: Double,
        tempo: Double?,
    ): List<SpeechWindow> {
        val limit = threshold(series)
        return series.points
            .filter { it.levelDb.isFinite() && it.levelDb > limit && it.timeSeconds.isFinite() && it.timeSeconds >= 0.0 }
            .map { point ->
                SpeechWindow(
                    range =
                        TimeRange(
                            startSeconds =
                                AudioWallClock.toWallSeconds(
                                    offsetSeconds = offsetSeconds,
                                    mediaSeconds = point.timeSeconds,
                                    tempo = tempo,
                                ),
                            endSeconds =
                                AudioWallClock.toWallSeconds(
                                    offsetSeconds = offsetSeconds,
                                    mediaSeconds =
                                        point.timeSeconds + series.windowSeconds,
                                    tempo = tempo,
                                ),
                        ),
                    levelDb = point.levelDb,
                )
            }
    }

    /** Number of grid samples of [stepMs] needed to cover [totalMs]. */
    internal fun sampleCount(
        totalMs: Long,
        stepMs: Long,
    ): Int = ceil(totalMs.toDouble() / stepMs).toInt().coerceAtLeast(0)
}
