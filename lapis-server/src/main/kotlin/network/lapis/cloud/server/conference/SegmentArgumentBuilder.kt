package network.lapis.cloud.server.conference

import network.lapis.cloud.shared.conference.CellRect
import network.lapis.cloud.shared.conference.gridCellRects
import network.lapis.cloud.shared.conference.singleRowGrid
import network.lapis.cloud.shared.conference.speakerLayoutRects
import java.util.Locale
import kotlin.math.roundToLong

/** The output canvas of a composition. */
data class RecordingCanvas(
    val width: Int,
    val height: Int,
)

/**
 * One video input of one segment: the (already remuxed) [path] and where its media second 0 sits on the recording's wall clock
 * ([offsetSeconds]).
 */
data class SegmentVideoSource(
    val trackId: String,
    val path: String,
    val offsetSeconds: Double,
)

/**
 * One audio source: [path] (already remuxed), where its media second 0 sits on the wall clock ([offsetSeconds]), the stretches in which
 * it carries audio in WALL-CLOCK seconds ([wallRanges], the tempo already applied) and the optional [tempo] (`atempo` ratio,
 * `media / wall`; see [AudioTempo]).
 */
data class SegmentAudioSource(
    val path: String,
    val offsetSeconds: Double,
    val wallRanges: List<TimeRange>,
    val tempo: Double?,
)

/**
 * The `atempo` decision of one audio track (V1.9.109, plan question 7).
 *
 * A microphone's media can be slightly SHORTER than the wall clock span it covers (sender clock drift, dropped packets that were not
 * concealed). Stretching it by the ratio `media / wall` keeps the end of a long track in sync. The reported wall duration contains the
 * egress's EOS flush tail (0.1 to 0.4 s, see [RecordingTrackAlignment]), so the ratio is computed against `wall - EOS_TAIL`, and the
 * correction only applies when the difference is clearly beyond that tail and still plausibly drift, not a structural problem:
 * `0.95 < ratio < 0.997` and `wall - media > 1.0 s`.
 */
object AudioTempo {
    const val EOS_TAIL_SECONDS = 0.4
    const val MIN_RATIO = 0.95
    const val MAX_RATIO = 0.997
    const val MIN_DIFFERENCE_SECONDS = 1.0

    fun ratioFor(
        mediaSeconds: Double?,
        wallSeconds: Double?,
    ): Double? {
        if (mediaSeconds == null || wallSeconds == null) return null
        if (!mediaSeconds.isFinite() ||
            !wallSeconds.isFinite() ||
            mediaSeconds <= 0.0 ||
            wallSeconds <= AudioTempo.EOS_TAIL_SECONDS
        ) {
            return null
        }
        if (wallSeconds - mediaSeconds <= MIN_DIFFERENCE_SECONDS) return null
        val ratio = mediaSeconds / (wallSeconds - EOS_TAIL_SECONDS)
        return if (ratio > MIN_RATIO && ratio < MAX_RATIO) ratio else null
    }
}

/**
 * V1.9.109 -- PURE construction of the `ffmpeg` argument lists [FfmpegSegmentedComposer] runs, one list per segment of the edit decision
 * list ([RecordingEditDecisionList]). Nothing here reads a file or starts a process, so the whole filter construction is unit-testable
 * (`SegmentArgumentBuilderTest`).
 *
 * ## Video segment
 *
 * A black `color` source of exactly the segment's frames is the base; every visible track is seeked (`-ss` BEFORE `-i`, on a remuxed
 * file with an index), put on a constant frame rate, scaled into its cell and overlaid. The placement of the cells is the shared
 * layout ([gridCellRects], the presentation layout puts the screen share above a row of cameras; the speaker layout of V1.9.115 puts one
 * person large above a strip of up to five, [speakerLayoutRects]). Every segment is encoded with the
 * same parameters, closed GOPs and NO B-frames (`-bf 0`): that makes a lossless join of the segments free of DTS overlaps. The segments are
 * MP4 files, not MPEG-TS: `ffprobe` (and with it the `concat` demuxer) cannot open a two or three frame MPEG-TS written by x264 ("End of
 * file" -- found by the sync test with a 2-frame first segment), an MP4 of any length is fine, and its 1/15360 time scale represents 30 fps
 * exactly.
 *
 * ## Audio segment
 *
 * Audio is rendered to uncompressed PCM per segment and encoded to AAC ONCE at the end: AAC per segment would add the encoder's priming
 * delay (about 21 ms) at every seam. The segment holds exactly `frames * 48000 / fps` samples (an integer for 30 fps), counted in
 * samples (`atrim=end_sample`, `apad=whole_len`), never in seconds, so rounding cannot accumulate over a long recording. A source
 * contributes one input per data range that overlaps the segment (a muted stretch is silence, not a hole the resampler has to guess);
 * with no input at all the segment is silence.
 *
 * ## Locale
 *
 * Every number in a filter or option is formatted with [Locale.ROOT] ([seconds]); a German default locale would write `0,500`.
 */
object SegmentArgumentBuilder {
    const val AUDIO_SAMPLE_RATE = 48_000

    /** Fraction of the canvas height the camera strip below a screen share occupies. */
    const val CAMERA_STRIP_HEIGHT_FRACTION = 0.28

    /** The cell of every visible track of [seg] on [canvas]: the screen share (if any) and the cameras. */
    fun cells(
        seg: EdlSegment,
        canvas: RecordingCanvas,
    ): Map<String, CellRect> {
        val result = LinkedHashMap<String, CellRect>()
        if (seg.speakerTrackId != null) {
            // V1.9.115: the speaker large on top (80 % of the height), a strip of up to five pictures below it.
            val (main, strip) = speakerLayoutRects(canvasW = canvas.width, canvasH = canvas.height, stripCount = seg.stripTrackIds.size)
            result[seg.speakerTrackId] = main
            seg.stripTrackIds.forEachIndexed { index, id -> result[id] = strip[index] }
            return result
        }
        val cameraIds =
            seg.cameraSlots.entries
                .sortedBy { it.value }
                .map { it.key }
        if (seg.screenShareTrackId != null) {
            val stripHeight = ((canvas.height * CAMERA_STRIP_HEIGHT_FRACTION).toInt() and 1.inv()).coerceAtLeast(2)
            val shareHeight = if (cameraIds.isEmpty()) canvas.height else canvas.height - stripHeight
            result[seg.screenShareTrackId] = CellRect(x = 0, y = 0, width = canvas.width, height = shareHeight)
            if (cameraIds.isNotEmpty()) {
                val rects =
                    gridCellRects(
                        count = cameraIds.size,
                        canvasW = canvas.width,
                        canvasH = stripHeight,
                        shape = singleRowGrid(cameraIds.size),
                    )
                cameraIds.forEachIndexed { index, id ->
                    val rect = rects[index]
                    result[id] = rect.copy(y = rect.y + shareHeight)
                }
            }
        } else if (cameraIds.isNotEmpty()) {
            val rects = gridCellRects(count = cameraIds.size, canvasW = canvas.width, canvasH = canvas.height, shape = seg.shape)
            cameraIds.forEachIndexed { index, id -> result[id] = rects[index] }
        }
        return result
    }

    /**
     * The `ffmpeg` arguments (without the binary name) that render [seg] to the MP4 file [outVideo]. [inputs] holds every track of the
     * recording by id; only the visible ones of [seg] are used.
     */
    fun buildVideoSegment(
        seg: EdlSegment,
        inputs: Map<String, SegmentVideoSource>,
        canvas: RecordingCanvas,
        fps: Int,
        outVideo: String,
    ): List<String> {
        val frames = seg.frames
        require(frames > 0) { "segment must have at least one frame" }
        val segStart = seg.startFrame.toDouble() / fps
        val duration = frames.toDouble() / fps
        val cells = cells(seg = seg, canvas = canvas)
        val args = mutableListOf("-y", "-nostdin", "-hide_banner", "-loglevel", "error")
        args += listOf("-f", "lavfi", "-i", "color=black:size=${canvas.width}x${canvas.height}:rate=$fps:duration=${seconds(duration)}")
        val parts = mutableListOf<String>()
        var previous = "0:v"
        var overlayCount = 0
        var inputIndex = 1
        for ((trackId, cell) in cells) {
            val source = inputs[trackId] ?: throw IllegalArgumentException("no video source for track $trackId")
            val want = segStart - source.offsetSeconds
            val seek = want.coerceAtLeast(0.0)
            val lead = (-want).coerceAtLeast(0.0)
            args += listOf("-ss", seconds(seek), "-i", source.path)
            val label = "v$inputIndex"
            val lag = if (lead > 0.0005) "tpad=start_duration=${seconds(lead)}:start_mode=clone," else ""
            parts +=
                "[$inputIndex:v]${lag}fps=fps=$fps:start_time=0,scale=${cell.width}:${cell.height}:force_original_aspect_ratio=decrease," +
                "pad=${cell.width}:${cell.height}:(ow-iw)/2:(oh-ih)/2:color=black[$label]"
            overlayCount++
            val out = if (overlayCount == cells.size) "vout" else "ov$overlayCount"
            parts += "[$previous][$label]overlay=x=${cell.x}:y=${cell.y}:eof_action=repeat[$out]"
            previous = out
            inputIndex++
        }
        if (cells.isEmpty()) {
            parts += "[0:v]null[vout]"
        }
        args += listOf("-filter_complex", parts.joinToString(";"))
        args += listOf("-map", "[vout]", "-an")
        args +=
            listOf(
                "-frames:v",
                frames.toString(),
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-profile:v",
                "high",
                "-pix_fmt",
                "yuv420p",
                "-r",
                fps.toString(),
                "-g",
                (2 * fps).toString(),
                "-bf",
                "0",
                "-f",
                "mp4",
                outVideo,
            )
        return args
    }

    /** The `ffmpeg` arguments that render the audio of [seg] to the PCM WAV file [outWav] -- see the class KDoc "Audio segment". */
    fun buildAudioSegment(
        seg: EdlSegment,
        sources: List<SegmentAudioSource>,
        fps: Int,
        outWav: String,
    ): List<String> {
        val frames = seg.frames
        require(frames > 0) { "segment must have at least one frame" }
        val total = sampleCount(frames = frames, fps = fps)
        val segStart = seg.startFrame.toDouble() / fps
        val segEnd = seg.endFrame.toDouble() / fps
        val args = mutableListOf("-y", "-nostdin", "-hide_banner", "-loglevel", "error")
        val parts = mutableListOf<String>()
        var inputs = 0
        for (source in sources) {
            val ratio = source.tempo ?: 1.0
            for (range in source.wallRanges) {
                val from = maxOf(range.startSeconds, segStart)
                val to = minOf(range.endSeconds, segEnd)
                if (to - from < MIN_AUDIO_PIECE_SECONDS) continue
                val mediaStart = ((from - source.offsetSeconds) * ratio).coerceAtLeast(0.0)
                val mediaLength = (to - from) * ratio
                val delaySamples = ((from - segStart) * AUDIO_SAMPLE_RATE).roundToLong().coerceAtLeast(0L)
                args += listOf("-ss", seconds(mediaStart), "-t", seconds(mediaLength), "-i", source.path)
                val tempoChain = if (source.tempo != null) "atempo=${ratioText(source.tempo)},asetpts=N/SR/TB," else ""
                parts +=
                    "[$inputs:a]aformat=sample_rates=$AUDIO_SAMPLE_RATE:channel_layouts=stereo,$tempoChain" +
                    "aresample=async=480:first_pts=0,adelay=${delaySamples}S:all=1,apad=whole_len=$total,atrim=end_sample=$total[a$inputs]"
                inputs++
            }
        }
        if (inputs == 0) {
            args += listOf("-f", "lavfi", "-i", "anullsrc=r=$AUDIO_SAMPLE_RATE:cl=stereo")
            parts += "[0:a]atrim=end_sample=$total[aout]"
        } else {
            val labels = (0 until inputs).joinToString("") { "[a$it]" }
            // attack=0.1 (the minimum): the limiter's look-ahead delays the signal by its attack time; at the default 5 ms that would be a
            // 240 sample shift of every segment against the video -- 0.1 ms is below the sample accuracy that matters here.
            parts +=
                "${labels}amix=inputs=$inputs:normalize=0:duration=longest," +
                "alimiter=limit=0.891:attack=0.1:level=0,atrim=end_sample=$total[aout]"
        }
        args += listOf("-filter_complex", parts.joinToString(";"))
        args += listOf("-map", "[aout]", "-c:a", "pcm_s16le", "-ar", AUDIO_SAMPLE_RATE.toString(), "-ac", "2", "-rf64", "auto", outWav)
        return args
    }

    /**
     * The `concat` demuxer list of [entries] (generated file NAMES, no paths, and their frame counts): `file` / `duration` pairs.
     * Names must only contain `[A-Za-z0-9_.-]` (checked), so the list can be read with `-safe 1`.
     */
    fun buildConcatList(
        entries: List<Pair<String, Long>>,
        fps: Int,
    ): String {
        val builder = StringBuilder()
        for ((name, frames) in entries) {
            require(SAFE_NAME.matches(name)) { "unsafe segment file name" }
            builder.append("file ").append(name).append('\n')
            builder.append("duration ").append(seconds(frames.toDouble() / fps)).append('\n')
        }
        return builder.toString()
    }

    /** The final mux: both concatenated lists, video copied, audio encoded to AAC once, MP4 with the index up front. */
    fun buildFinalMux(
        videoList: String,
        audioList: String,
        out: String,
    ): List<String> =
        listOf(
            "-y",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "concat",
            "-safe",
            "1",
            "-i",
            videoList,
            "-f",
            "concat",
            "-safe",
            "1",
            "-i",
            audioList,
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            "-f",
            "mp4",
            out,
        )

    /** Samples of a segment of [frames] frames at [fps]; exact for every [fps] that divides 48000 evenly (30, 25, 24, 50, 60). */
    fun sampleCount(
        frames: Long,
        fps: Int,
    ): Long = (frames * AUDIO_SAMPLE_RATE.toLong() + fps / 2) / fps

    /** Locale-free, microsecond-precise seconds literal. */
    fun seconds(value: Double): String = String.format(Locale.ROOT, "%.6f", value.coerceAtLeast(0.0))

    private fun ratioText(value: Double): String = String.format(Locale.ROOT, "%.8f", value)

    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]+")

    /** A data stretch shorter than this inside one segment is not worth an input. */
    private const val MIN_AUDIO_PIECE_SECONDS = 0.001
}
