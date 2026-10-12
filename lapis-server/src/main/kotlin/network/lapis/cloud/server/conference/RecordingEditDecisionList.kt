package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.shared.conference.CONFERENCE_SPEAKER_STRIP_MAX_RECORDING
import network.lapis.cloud.shared.conference.GridShape
import network.lapis.cloud.shared.conference.assignSlots
import network.lapis.cloud.shared.conference.bestFitGrid
import network.lapis.cloud.shared.conference.singleRowGrid
import kotlin.math.roundToLong

private val logger = KotlinLogging.logger {}

/** A half-open stretch `[startSeconds, endSeconds)` of a timeline. */
data class TimeRange(
    val startSeconds: Double,
    val endSeconds: Double,
) {
    val lengthSeconds: Double get() = endSeconds - startSeconds
}

/**
 * One video track as the edit decision list sees it: [ranges] are the stretches in which the track actually carries pictures, in
 * WALL-CLOCK seconds relative to the recording's `t0` (the track's own offset already added). [firstSeenSeconds] orders the pictures
 * (oldest first) for slot assignment; [isScreenShare] picks the presentation layout.
 */
data class EdlVideoTrack(
    val trackId: String,
    val isScreenShare: Boolean,
    val firstSeenSeconds: Double,
    val ranges: List<TimeRange>,
    /** V1.9.115: opaque key of the person (see [RecordingComposeVideoInput.personKey]); `null` for a screen share. */
    val personKey: String? = null,
)

/**
 * One stretch of the output with a CONSTANT layout.
 *
 * [startFrame] / [endFrame] are ABSOLUTE output frames (half open), so the segment lengths sum to the total without any rounding drift.
 * [screenShareTrackId] is the track shown large (presentation layout), `null` in the gallery; [cameraSlots] maps every other visible
 * track to its slot (`0 until n`), [shape] is the grid the slots refer to (a single row below a screen share).
 */
data class EdlSegment(
    val index: Int,
    val startFrame: Long,
    val endFrame: Long,
    val screenShareTrackId: String?,
    val cameraSlots: Map<String, Int>,
    val shape: GridShape,
    /** V1.9.115: the track shown large in the SPEAKER layout, `null` otherwise (grid or presentation). */
    val speakerTrackId: String? = null,
    /** V1.9.115: the tracks of the strip below the large speaker tile, in display order (at most [CONFERENCE_SPEAKER_STRIP_MAX_RECORDING]). */
    val stripTrackIds: List<String> = emptyList(),
) {
    val frames: Long get() = endFrame - startFrame
}

/**
 * V1.9.109 -- the EDIT DECISION LIST of a recording composition: pure arithmetic, no I/O, no process, no clock.
 *
 * ## Why a list of segments instead of one filter graph
 *
 * Until V1.9.108 the composition was ONE `ffmpeg` call with one overlay per track on a black canvas and a layout fixed by the number
 * of tracks that ever existed -- so a person who left still occupied a cell for the rest of the meeting, a person who joined late
 * squeezed everyone from the start, and an error of any filter (a drifting audio clock, a lost packet) grew until the end of the file.
 * The list cuts the output into segments of CONSTANT layout; each segment is built on its own (see [SegmentArgumentBuilder]) and the
 * segments are joined without re-encoding. A mistake therefore never grows beyond the end of its own segment.
 *
 * ## Cut points
 *
 * Every start and end of a track's data range ([EdlVideoTrack.ranges]), rounded to an absolute frame (`round(t * fps)`, never a sum of
 * rounded lengths), plus 0, the last frame, and a forced cut every [MAX_SEGMENT_SECONDS] (a 70 minute meeting without any change is
 * 35 segments, not one; the bound on how far a mistake can travel is real). Stretches shorter than [MIN_CUT_FRAMES] frames are
 * merged into their neighbour (the first one into its successor), so a flapping camera cannot make 1-frame segments; if there are still
 * more than [MAX_SEGMENTS] segments the minimum length doubles until there are not (and the log says so).
 *
 * ## What is visible
 *
 * A track is visible in a segment when one of its ranges covers the MIDDLE of the segment (robust against a range edge falling between
 * two frames). With a visible screen share the layout is the presentation layout (the LATEST started visible screen share large -- the
 * rule of the live view, changed from "earliest" in V1.9.115 -- every other visible track in one row below); otherwise, with
 * [RecordingLayout.SPEAKER], the person of the speaker span covering the segment large (a strip of at most
 * [CONFERENCE_SPEAKER_STRIP_MAX_RECORDING] others below: the most recent speakers first, then by first appearance), and where there is
 * no usable speaker (before the first span, the speaker has no picture, fewer than two pictures) the shared best-fit grid
 * ([bestFitGrid], the same function the live call uses). Pictures keep their slot while the shape stays the same ([assignSlots]).
 * The boundaries of the speaker spans are cut points too, so the large tile changes on an exact frame.
 *
 * With more than six visible people the speaker layout cannot show everybody (the strip holds five): that is a documented limit.
 *
 * There is deliberately no grace period: a person who turns their camera off and on again within a second leaves and re-enters the
 * layout. Only a gap in the track's own data of at least the prober's threshold counts as "camera off" (a muted LiveKit track is not an
 * ended track).
 */
object RecordingEditDecisionList {
    /** The longest a segment may be; longer stretches are cut into pieces of at most this many seconds. */
    const val MAX_SEGMENT_SECONDS = 120.0

    /** Protection against a flapping camera: more segments than this raises the minimum cut length. */
    const val MAX_SEGMENTS = 1500

    /** Segments shorter than this many frames are merged into their neighbour. */
    const val MIN_CUT_FRAMES = 2

    fun build(
        tracks: List<EdlVideoTrack>,
        totalSeconds: Double,
        fps: Int,
        canvasW: Int,
        canvasH: Int,
        layout: RecordingLayout = RecordingLayout.GRID,
        speakerSpans: List<RecordingSpeakerSpan> = emptyList(),
    ): List<EdlSegment> {
        require(fps > 0) { "fps must be positive" }
        require(totalSeconds.isFinite() && totalSeconds > 0.0) { "totalSeconds must be > 0" }
        val totalFrames = (totalSeconds * fps).roundToLong().coerceAtLeast(1L)

        val rawCuts = sortedSetOf(0L, totalFrames)
        for (track in tracks) {
            for (range in track.ranges) {
                rawCuts += frameOf(seconds = range.startSeconds, fps = fps, totalFrames = totalFrames)
                rawCuts += frameOf(seconds = range.endSeconds, fps = fps, totalFrames = totalFrames)
            }
        }
        if (layout == RecordingLayout.SPEAKER) {
            for (span in speakerSpans) {
                rawCuts += frameOf(seconds = span.startSeconds, fps = fps, totalFrames = totalFrames)
                rawCuts += frameOf(seconds = span.endSeconds, fps = fps, totalFrames = totalFrames)
            }
        }
        val forcedStep = (MAX_SEGMENT_SECONDS * fps).roundToLong()
        var forced = forcedStep
        while (forced < totalFrames) {
            rawCuts += forced
            forced += forcedStep
        }

        var minFrames = MIN_CUT_FRAMES.toLong()
        var cuts = mergeShort(cuts = rawCuts.toList(), minFrames = minFrames)
        while (cuts.size - 1 > MAX_SEGMENTS && minFrames < totalFrames) {
            minFrames *= 2
            cuts = mergeShort(cuts = rawCuts.toList(), minFrames = minFrames)
            logger.warn { "RecordingEditDecisionList: more than $MAX_SEGMENTS segments -- minimum cut length raised to $minFrames frames" }
        }

        val ordered = tracks.sortedWith(compareBy<EdlVideoTrack> { it.firstSeenSeconds }.thenBy { it.trackId })
        val segments = ArrayList<EdlSegment>(cuts.size - 1)
        var previousSlots: Map<String, Int> = emptyMap()
        var previousShape: GridShape? = null
        for (i in 0 until cuts.size - 1) {
            val start = cuts[i]
            val end = cuts[i + 1]
            val middle = (start + end) / 2.0 / fps
            val visible = ordered.filter { track -> track.ranges.any { middle >= it.startSeconds && middle < it.endSeconds } }
            // the screen share that started LAST is large (V1.9.115); ties by track id for determinism
            val share =
                visible.filter { it.isScreenShare }.maxWithOrNull(
                    compareBy<EdlVideoTrack> { it.firstSeenSeconds }.thenBy { it.trackId },
                )
            val cameras = visible.filter { it !== share }
            if (layout == RecordingLayout.SPEAKER && share == null) {
                val speakerSegment =
                    speakerSegment(index = i, start = start, end = end, middle = middle, cameras = cameras, spans = speakerSpans)
                if (speakerSegment != null) {
                    segments += speakerSegment
                    previousSlots = emptyMap()
                    previousShape = null
                    continue
                }
            }
            val shape =
                if (share !=
                    null
                ) {
                    singleRowGrid(cameras.size)
                } else {
                    bestFitGrid(count = cameras.size, canvasW = canvasW, canvasH = canvasH)
                }
            val slots =
                assignSlots(
                    previous = previousSlots,
                    prevShape = previousShape,
                    visibleInFirstSeenOrder = cameras.map { it.trackId },
                    shape = shape,
                )
            segments +=
                EdlSegment(
                    index = i,
                    startFrame = start,
                    endFrame = end,
                    screenShareTrackId = share?.trackId,
                    cameraSlots = slots,
                    shape = shape,
                )
            previousSlots = slots
            previousShape = shape
        }
        return segments
    }

    /**
     * The speaker-layout segment for [middle], or `null` when the grid applies: no span covers the middle, the speaker of the span has
     * no picture here, or there are fewer than two pictures (one picture fills the canvas as a grid of one).
     */
    private fun speakerSegment(
        index: Int,
        start: Long,
        end: Long,
        middle: Double,
        cameras: List<EdlVideoTrack>,
        spans: List<RecordingSpeakerSpan>,
    ): EdlSegment? {
        if (cameras.size < 2) return null
        val active = spans.firstOrNull { middle >= it.startSeconds && middle < it.endSeconds } ?: return null
        val main = cameras.firstOrNull { it.personKey != null && it.personKey == active.personKey } ?: return null
        val lastSpokeEnd =
            cameras.associateWith { track ->
                spans
                    .filter { it.personKey == track.personKey && track.personKey != null && it.startSeconds < middle }
                    .maxOfOrNull { minOf(it.endSeconds, middle) }
            }
        val others = cameras.filter { it !== main }
        val strip =
            others
                .filter { lastSpokeEnd[it] != null }
                .sortedWith(compareByDescending<EdlVideoTrack> { lastSpokeEnd[it] }.thenBy { it.firstSeenSeconds }.thenBy { it.trackId }) +
                others
                    .filter { lastSpokeEnd[it] == null }
                    .sortedWith(compareBy<EdlVideoTrack> { it.firstSeenSeconds }.thenBy { it.trackId })
        return EdlSegment(
            index = index,
            startFrame = start,
            endFrame = end,
            screenShareTrackId = null,
            cameraSlots = emptyMap(),
            shape = GridShape(cols = 1, rows = 1),
            speakerTrackId = main.trackId,
            stripTrackIds = strip.take(CONFERENCE_SPEAKER_STRIP_MAX_RECORDING).map { it.trackId },
        )
    }

    private fun frameOf(
        seconds: Double,
        fps: Int,
        totalFrames: Long,
    ): Long = (seconds * fps).roundToLong().coerceIn(0L, totalFrames)

    /**
     * Removes cut points until no segment is shorter than [minFrames]. A short segment is merged into its predecessor (its start point
     * disappears), the first segment into its successor (its end point disappears).
     */
    private fun mergeShort(
        cuts: List<Long>,
        minFrames: Long,
    ): List<Long> {
        val result = cuts.toMutableList()
        var i = 0
        while (i < result.size - 1) {
            val length = result[i + 1] - result[i]
            if (length >= minFrames || result.size <= 2) {
                i++
                continue
            }
            if (i == 0) {
                result.removeAt(1)
            } else {
                result.removeAt(i)
                i--
            }
        }
        return result
    }
}
