package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * What was measured on ONE per-person file (a `StartParticipantEgress` output with audio and video, V1.9.110). Every stream has ITS OWN
 * timeline: its start time inside the container ([videoStartSeconds]/[audioStartSeconds], `ffprobe stream.start_time`), its packet
 * ranges relative to its own first packet, and the stretches of black pictures ([blankVideoRanges], also relative to the first video
 * packet). `null` always means "could not be measured".
 *
 * [hasVideo]/[hasAudio] say whether the file HAS such a stream at all (default: assumed present, as long as nothing says otherwise).
 */
data class ParticipantStreamMeasurements(
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    val videoStartSeconds: Double? = null,
    val audioStartSeconds: Double? = null,
    val videoPacketRanges: PacketRanges? = null,
    val audioPacketRanges: PacketRanges? = null,
    val blankVideoRanges: List<TimeRange>? = null,
    val reportedDurationSeconds: Double? = null,
)

/**
 * V1.9.110 -- turns one per-person file into the composer inputs: one video input and one audio input that BOTH point at the same file.
 *
 * ## Why per-stream offsets
 *
 * [FfmpegSegmentedComposer] remuxes `0:v:0` and `0:a:0` of an input into separate files; after that, media time 0 of each stream is ITS
 * OWN first packet. In a per-person file the two streams usually do not start together (the first keyframe arrives after the first audio
 * packet): if both inputs simply got the file's offset, that gap would be lost and the person would be out of sync WITHIN the file --
 * exactly the kind of error this mode exists to remove. So `fileZero = min(videoStart, audioStart)` is the file's own time zero, and each
 * stream's offset is the file offset plus the distance of its start from `fileZero`.
 * (A start slightly below zero, e.g. AAC priming of about -0.02 s, is harmless: only the difference counts.)
 *
 * ## Conditional drift correction (V1.9.154)
 *
 * The audio input gets NO wall-clock duration. Opus DTX makes the egress write the audio ~0.34 % too long, though; [ParticipantAudioDrift]
 * compares the END POINTS of both streams inside the container (not the durations: a later video start is an offset, no drift) and, only
 * for a clear, bounded excess, sets [RecordingComposeAudioInput.driftTempo]. The composer then applies it per segment.
 */
object ParticipantFileInputs {
    /** The video and the audio input of [file]; a part is `null` when the file has no such stream (or, for video, the picture is black throughout). */
    fun build(
        file: File,
        trackId: String,
        fileOffsetSeconds: Double,
        m: ParticipantStreamMeasurements,
    ): Pair<RecordingComposeVideoInput?, RecordingComposeAudioInput?> {
        val videoStart = if (m.hasVideo) m.videoStartSeconds else null
        val audioStart = if (m.hasAudio) m.audioStartSeconds else null
        val fileZero = listOfNotNull(videoStart, audioStart).minOrNull()
        if (fileZero == null && m.hasVideo && m.hasAudio) {
            logger.warn {
                "ParticipantFileInputs: no stream start time measured for '${file.name}' -- assuming both streams start together"
            }
        }
        val zero = fileZero ?: 0.0
        val videoOffset = fileOffsetSeconds + ((videoStart ?: zero) - zero)
        val audioOffset = fileOffsetSeconds + ((audioStart ?: zero) - zero)

        val video =
            if (m.hasVideo) {
                val duration = m.videoPacketRanges?.mediaDurationSeconds ?: m.reportedDurationSeconds
                val packetRanges = m.videoPacketRanges?.ranges
                val blank = closeTailOfBlankRanges(blank = m.blankVideoRanges.orEmpty(), videoDurationSeconds = duration)
                val ranges =
                    when {
                        blank.isEmpty() -> packetRanges.orEmpty()
                        else -> {
                            val base =
                                packetRanges
                                    ?: duration?.takeIf { it > 0.0 }?.let { listOf(TimeRange(startSeconds = 0.0, endSeconds = it)) }
                            if (base == null) emptyList() else TimeRanges.subtract(from = base, remove = blank)
                        }
                    }
                // "Measured, but nothing visible" must not be handed over as an empty list (the composer reads that as "not measured,
                // visible throughout"): a picture that is black throughout simply contributes no video input.
                val everythingBlack = blank.isNotEmpty() && ranges.isEmpty() && (packetRanges != null || duration != null)
                if (everythingBlack) {
                    null
                } else {
                    RecordingComposeVideoInput(
                        file = file,
                        offsetSeconds = videoOffset,
                        isScreenShare = false,
                        trackId = trackId,
                        ranges = ranges,
                        durationSeconds = duration,
                    )
                }
            } else {
                null
            }
        val driftTempo =
            if (m.hasVideo && m.hasAudio) {
                ParticipantAudioDrift
                    .tempoFor(
                        audioMediaSeconds = m.audioPacketRanges?.mediaDurationSeconds,
                        audioStartSeconds = m.audioStartSeconds,
                        videoMediaSeconds = m.videoPacketRanges?.mediaDurationSeconds,
                        videoStartSeconds = m.videoStartSeconds,
                    )?.also { ratio ->
                        logger.info {
                            "ParticipantFileInputs: audio drift corrected, atempo ratio ${"%.6f".format(
                                java.util.Locale.ROOT,
                                ratio,
                            )}"
                        }
                    }
            } else {
                null
            }
        val audio =
            if (m.hasAudio) {
                RecordingComposeAudioInput(
                    file = file,
                    offsetSeconds = audioOffset,
                    ranges = m.audioPacketRanges?.ranges.orEmpty(),
                    mediaDurationSeconds = m.audioPacketRanges?.mediaDurationSeconds ?: m.reportedDurationSeconds,
                    wallDurationSeconds = null,
                    driftTempo = driftTempo,
                )
            } else {
                null
            }
        return video to audio
    }
}

/**
 * The blank scan decodes keyframes only, so a black stretch that runs to the END of the picture is closed at the LAST KEYFRAME, up to one
 * keyframe interval (2 s, see [RECORDING_PARTICIPANT_ENCODING_OPTIONS]) before the stream really ends -- observed on a real egress file
 * (2026-10-11: black until 22.5 s of a 23.5 s stream). A stretch that ends within this slack of the end of the picture therefore runs to the end.
 */
internal const val BLANK_TAIL_SLACK_SECONDS = 2.5

private fun closeTailOfBlankRanges(
    blank: List<TimeRange>,
    videoDurationSeconds: Double?,
): List<TimeRange> {
    if (blank.isEmpty() || videoDurationSeconds == null || !videoDurationSeconds.isFinite()) return blank
    val last = blank.maxBy { it.endSeconds }
    if (last.endSeconds < videoDurationSeconds - BLANK_TAIL_SLACK_SECONDS || last.endSeconds >= videoDurationSeconds) return blank
    return blank.map { if (it === last) TimeRange(startSeconds = it.startSeconds, endSeconds = videoDurationSeconds) else it }
}

/** Pure arithmetic on [TimeRange] lists. */
object TimeRanges {
    /**
     * [from] without the stretches in [remove]; pieces shorter than [minLengthSeconds] are dropped. Neither list needs to be sorted or
     * free of overlaps; the result is sorted and keeps the order of [from]'s pieces.
     */
    fun subtract(
        from: List<TimeRange>,
        remove: List<TimeRange>,
        minLengthSeconds: Double = 0.001,
    ): List<TimeRange> {
        val cuts = remove.filter { it.endSeconds > it.startSeconds }.sortedBy { it.startSeconds }
        val result = ArrayList<TimeRange>()
        for (range in from.sortedBy { it.startSeconds }) {
            var cursor = range.startSeconds
            for (cut in cuts) {
                if (cut.endSeconds <= cursor) continue
                if (cut.startSeconds >= range.endSeconds) break
                if (cut.startSeconds >
                    cursor
                ) {
                    result += TimeRange(startSeconds = cursor, endSeconds = minOf(cut.startSeconds, range.endSeconds))
                }
                cursor = maxOf(cursor, cut.endSeconds)
                if (cursor >= range.endSeconds) break
            }
            if (cursor < range.endSeconds) result += TimeRange(startSeconds = cursor, endSeconds = range.endSeconds)
        }
        return result.filter { it.lengthSeconds >= minLengthSeconds }
    }
}
