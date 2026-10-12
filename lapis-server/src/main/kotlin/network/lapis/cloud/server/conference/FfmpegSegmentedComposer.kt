package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.109 -- the real, `ProcessBuilder`-backed [RecordingComposer]: composes the raw per-track files SEGMENT BY SEGMENT instead of with
 * one big filter graph (see [RecordingEditDecisionList] for why, [SegmentArgumentBuilder] for the filter construction).
 *
 * ## Steps of [compose]
 *
 * 1. **Work directory** `<output dir>/.work-<uuid>` -- on the same volume as the raw files; stale `.work-*` directories (a crash) are
 *    removed first; the directory is always removed in a `finally`.
 * 2. **Remux** every raw file once, with `-c copy -copyts`, into Matroska with an index (LiveKit's track egress writes WebM/OGG without
 *    cues, so every `-ss` of every segment would otherwise scan the file from its start: quadratic I/O over a long recording).
 *    `-copyts` is load-bearing: OGG is flagged as a format with timestamp discontinuities, and without it `ffmpeg` closes every gap of
 *    more than 10 s in the file's own timeline (a muted stretch of a microphone) by shifting all later packets -- the data ranges the
 *    layout and the audio inputs are built from would then no longer match the audio (found by the sync test with a 30 s hole).
 * 3. **Edit decision list** from the tracks' data ranges (wall clock = `offset + media time`, for audio with the optional `atempo`).
 * 4. **One video and one audio `ffmpeg` run per segment** -- sequential, each with its own timeout (`max(60 s, 4 x segment length)`,
 *    additionally bounded by what is left of [timeoutMinutes] for the whole composition).
 * 5. **Join without re-encoding** (`concat` demuxer, `-safe 1`, generated file names only) and one final mux: the video is copied, the
 *    audio is encoded to AAC once.
 * 6. **Verification** with `ffprobe`: a video and an audio stream, a duration within one second of the expected length. A file that
 *    fails this is never handed on.
 *
 * Every failure surfaces as a [RecordingComposeException] with a short generic message -- never `ffmpeg` output (see
 * [FfmpegProcessRunner]).
 */
class FfmpegSegmentedComposer(
    private val ffmpegPath: String,
    private val timeoutMinutes: Long,
    private val prober: RecordingMediaProber = RecordingMediaProber.None,
    private val runner: FfmpegProcessRunner = FfmpegProcessRunner(ffmpegPath),
) : RecordingComposer {
    override suspend fun compose(
        spec: RecordingComposeSpec,
        outputFile: File,
    ) {
        if (spec.videoInputs.isEmpty() ||
            !spec.outputDurationSeconds.isFinite() ||
            spec.outputDurationSeconds <= 0.0 ||
            spec.frameRate <= 0 ||
            spec.outputWidth < 2 ||
            spec.outputHeight < 2
        ) {
            throw RecordingComposeException(message = "invalid composition spec")
        }
        val parent = outputFile.absoluteFile.parentFile ?: throw RecordingComposeException(message = "invalid output location")
        val deadline = TimeSource.Monotonic.markNow() + timeoutMinutes.minutes
        var workDir: File? = null
        try {
            val dir =
                withContext(Dispatchers.IO) {
                    parent.mkdirs()
                    parent.listFiles { f -> f.isDirectory && f.name.startsWith(WORK_PREFIX) }?.forEach { it.deleteRecursively() }
                    File(parent, "$WORK_PREFIX${Uuid.random()}").also { Files.createDirectory(it.toPath()) }
                }
            workDir = dir
            composeInto(spec = spec, outputFile = outputFile, workDir = dir, deadline = deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RecordingComposeException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw RecordingComposeException(message = "invalid composition spec", cause = e)
        } catch (e: IOException) {
            throw RecordingComposeException(message = "composition I/O failed (${e::class.simpleName ?: "unknown error"})", cause = e)
        } finally {
            workDir?.let { dir -> withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() } }
        }
    }

    private suspend fun composeInto(
        spec: RecordingComposeSpec,
        outputFile: File,
        workDir: File,
        deadline: TimeMark,
    ) {
        val fps = spec.frameRate
        val canvas = RecordingCanvas(width = spec.outputWidth, height = spec.outputHeight)
        val total = spec.outputDurationSeconds

        // 2. remux
        val videoSources = LinkedHashMap<String, SegmentVideoSource>()
        spec.videoInputs.forEachIndexed { index, input ->
            val remuxed = File(workDir, "v$index.mkv")
            remux(source = input.file, map = "0:v:0", target = remuxed, deadline = deadline, label = "remux video ${index + 1}")
            videoSources[input.trackId] =
                SegmentVideoSource(trackId = input.trackId, path = remuxed.absolutePath, offsetSeconds = input.offsetSeconds)
        }
        val audioSources = ArrayList<SegmentAudioSource>()
        spec.audioInputs.forEachIndexed { index, input ->
            val remuxed = File(workDir, "a$index.mka")
            remux(source = input.file, map = "0:a:0", target = remuxed, deadline = deadline, label = "remux audio ${index + 1}")
            val tempo = AudioTempo.ratioFor(mediaSeconds = input.mediaDurationSeconds, wallSeconds = input.wallDurationSeconds)
            val mediaRanges =
                input.ranges.ifEmpty {
                    listOf(
                        TimeRange(
                            startSeconds = 0.0,
                            endSeconds =
                                input.mediaDurationSeconds ?: (total - input.offsetSeconds).coerceAtLeast(0.0),
                        ),
                    )
                }
            val wall =
                mediaRanges.mapNotNull {
                    clip(
                        range =
                            TimeRange(
                                startSeconds =
                                    AudioWallClock.toWallSeconds(
                                        offsetSeconds = input.offsetSeconds,
                                        mediaSeconds = it.startSeconds,
                                        tempo = tempo,
                                    ),
                                endSeconds =
                                    AudioWallClock.toWallSeconds(
                                        offsetSeconds = input.offsetSeconds,
                                        mediaSeconds = it.endSeconds,
                                        tempo = tempo,
                                    ),
                            ),
                        total = total,
                    )
                }
            if (tempo != null) {
                logger.info {
                    "FfmpegSegmentedComposer: audio track ${index + 1} stretched with atempo=$tempo (media ${input.mediaDurationSeconds}s vs wall ${input.wallDurationSeconds}s)"
                }
            }
            audioSources +=
                SegmentAudioSource(path = remuxed.absolutePath, offsetSeconds = input.offsetSeconds, wallRanges = wall, tempo = tempo)
        }

        // 3. edit decision list
        val edlTracks =
            spec.videoInputs.map { input ->
                val mediaRanges =
                    input.ranges.ifEmpty {
                        listOf(
                            TimeRange(
                                startSeconds = 0.0,
                                endSeconds = input.durationSeconds ?: (total - input.offsetSeconds).coerceAtLeast(0.0),
                            ),
                        )
                    }
                EdlVideoTrack(
                    trackId = input.trackId,
                    isScreenShare = input.isScreenShare,
                    firstSeenSeconds = input.offsetSeconds,
                    personKey = input.personKey,
                    ranges =
                        mediaRanges.mapNotNull {
                            clip(
                                range =
                                    TimeRange(
                                        startSeconds = input.offsetSeconds + it.startSeconds,
                                        endSeconds = input.offsetSeconds + it.endSeconds,
                                    ),
                                total = total,
                            )
                        },
                )
            }
        val segments =
            RecordingEditDecisionList.build(
                tracks = edlTracks,
                totalSeconds = total,
                fps = fps,
                canvasW = canvas.width,
                canvasH = canvas.height,
                layout = spec.layout,
                speakerSpans = spec.speakerSpans,
            )
        // Counts only (privacy): never a key, a file name or an identity.
        logger.info {
            "FfmpegSegmentedComposer: ${segments.size} segment(s) (${segments.count {
                it.speakerTrackId != null
            }} with a speaker layout) " +
                "for ${"%.1f".format(java.util.Locale.ROOT, total)}s of output"
        }

        // 4. per segment
        val videoEntries = ArrayList<Pair<String, Long>>()
        val audioEntries = ArrayList<Pair<String, Long>>()
        for (seg in segments) {
            val base = "seg-%05d".format(java.util.Locale.ROOT, seg.index)
            val segSeconds = seg.frames.toDouble() / fps
            val timeout = maxOf(MIN_STEP_TIMEOUT, (segSeconds * SEGMENT_TIMEOUT_FACTOR).seconds)
            val video = File(workDir, "$base.mp4")
            val wav = File(workDir, "$base.wav")
            runStep(
                args =
                    SegmentArgumentBuilder.buildVideoSegment(
                        seg = seg,
                        inputs = videoSources,
                        canvas = canvas,
                        fps = fps,
                        outVideo = video.absolutePath,
                    ),
                stepTimeout = timeout,
                deadline = deadline,
                label = "video segment ${seg.index + 1}/${segments.size}",
            )
            runStep(
                args = SegmentArgumentBuilder.buildAudioSegment(seg = seg, sources = audioSources, fps = fps, outWav = wav.absolutePath),
                stepTimeout = timeout,
                deadline = deadline,
                label = "audio segment ${seg.index + 1}/${segments.size}",
            )
            videoEntries += "$base.mp4" to seg.frames
            audioEntries += "$base.wav" to seg.frames
        }

        // 5. join + final mux
        val videoList = File(workDir, "video.txt")
        val audioList = File(workDir, "audio.txt")
        withContext(Dispatchers.IO) {
            videoList.writeText(SegmentArgumentBuilder.buildConcatList(entries = videoEntries, fps = fps))
            audioList.writeText(SegmentArgumentBuilder.buildConcatList(entries = audioEntries, fps = fps))
        }
        runStep(
            args =
                SegmentArgumentBuilder.buildFinalMux(
                    videoList = videoList.absolutePath,
                    audioList = audioList.absolutePath,
                    out = outputFile.absolutePath,
                ),
            stepTimeout = MIN_STEP_TIMEOUT.coerceAtLeast((total * SEGMENT_TIMEOUT_FACTOR / 8).seconds),
            deadline = deadline,
            label = "final mux",
        )

        // 6. verification
        verify(outputFile = outputFile, expectedSeconds = segments.last().endFrame.toDouble() / fps)
    }

    private suspend fun remux(
        source: File,
        map: String,
        target: File,
        deadline: TimeMark,
        label: String,
    ) {
        runStep(
            args =
                listOf(
                    "-y",
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-copyts",
                    "-i",
                    source.absolutePath,
                    "-map",
                    map,
                    "-c",
                    "copy",
                    "-f",
                    "matroska",
                    target.absolutePath,
                ),
            stepTimeout = MIN_STEP_TIMEOUT.coerceAtLeast(10.minutes),
            deadline = deadline,
            label = label,
        )
    }

    private suspend fun runStep(
        args: List<String>,
        stepTimeout: Duration,
        deadline: TimeMark,
        label: String,
    ) {
        val remaining = -deadline.elapsedNow()
        if (remaining <= Duration.ZERO) {
            throw RecordingComposeException(message = "composition exceeded $timeoutMinutes minute(s) and was stopped")
        }
        runner.run(args = args, timeout = minOf(stepTimeout, remaining), label = label)
    }

    private suspend fun verify(
        outputFile: File,
        expectedSeconds: Double,
    ) {
        val probe = prober.probeOutput(outputFile)
        if (probe == null) {
            logger.warn { "FfmpegSegmentedComposer: output could not be probed -- verification skipped" }
            return
        }
        val duration = probe.durationSeconds
        if (!probe.hasVideo || !probe.hasAudio || duration == null || abs(duration - expectedSeconds) > VERIFY_TOLERANCE_SECONDS) {
            logger.warn {
                "FfmpegSegmentedComposer: verification failed -- video=${probe.hasVideo} audio=${probe.hasAudio} duration=$duration expected=$expectedSeconds"
            }
            throw RecordingComposeException(message = "output verification failed")
        }
    }

    /** [range] limited to `[0, total]`, or `null` if nothing is left. */
    private fun clip(
        range: TimeRange,
        total: Double,
    ): TimeRange? {
        val start = range.startSeconds.coerceIn(0.0, total)
        val end = range.endSeconds.coerceIn(0.0, total)
        return if (end - start > MIN_RANGE_SECONDS &&
            start.isFinite() &&
            end.isFinite()
        ) {
            TimeRange(startSeconds = start, endSeconds = end)
        } else {
            null
        }
    }

    private companion object {
        const val WORK_PREFIX = ".work-"
        const val SEGMENT_TIMEOUT_FACTOR = 4.0
        const val VERIFY_TOLERANCE_SECONDS = 1.0
        const val MIN_RANGE_SECONDS = 1.0 / 1000
        val MIN_STEP_TIMEOUT: Duration = 60.seconds
    }
}
