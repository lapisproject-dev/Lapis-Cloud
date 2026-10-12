package network.lapis.cloud.server.conference

import java.io.File

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the pluggable boundary around
 * actually running `ffmpeg` to compose N raw per-track files into one gallery/presentation-layout
 * MP4. Kept behind this interface (in-memory fakes for tests) for the SAME "no hermetic test
 * depends on the real binary" reasoning
 * [network.lapis.cloud.server.conference.ConferenceRecordingConfig] KDoc "Why an external ffmpeg
 * binary" already gives -- mirrors [LiveKitAdminClient]/[LiveKitEgressClient]/
 * [network.lapis.cloud.server.postal.PostalMailProvider]'s own pluggable-boundary pattern.
 * [network.lapis.cloud.server.conference.RecordingPoller] depends on this interface, never on
 * [FfmpegSegmentedComposer] directly.
 */
interface RecordingComposer {
    /**
     * Composes [spec] into [outputFile] (created, or overwritten if it already exists). Throws
     * [RecordingComposeException] on ANY failure -- process could not start, non-zero exit, or
     * wall-clock timeout (the implementer's responsibility to enforce and to
     * `Process.destroyForcibly()` on timeout, see [FfmpegSegmentedComposer] KDoc) -- never a raw
     * process/IO exception, so [network.lapis.cloud.server.conference.RecordingPoller] has exactly
     * one exception type to catch.
     */
    suspend fun compose(
        spec: RecordingComposeSpec,
        outputFile: File,
    )
}

/**
 * See [RecordingComposer.compose] KDoc. [message] is always a short, generic description -- NEVER
 * raw ffmpeg stderr (see [FfmpegSegmentedComposer] KDoc "stderr never reaches the exception
 * message"), matching the same "never trust that a subprocess's own output cannot contain
 * something sensitive" discipline
 * [network.lapis.cloud.server.conference.LiveKitAdminException] KDoc establishes for its own
 * outbound-HTTP error surface.
 */
class RecordingComposeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * One decoded video track contributing to the composed output. [offsetSeconds] is this track's
 * start time relative to the recording's own earliest track (`t0`, computed by
 * [network.lapis.cloud.server.conference.RecordingPoller] from
 * [network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable.startedAtEpochNanos]) --
 * NEVER relative to wall-clock time directly, so the composed output always starts at `t=0`
 * regardless of when the underlying meeting actually began. [isScreenShare] switches the layout of
 * the segments this track is visible in from the gallery grid to the presentation layout (this track
 * large, the other visible tracks as a row below) -- see [RecordingEditDecisionList].
 *
 * [ranges] are the stretches of the track's OWN media timeline (start 0 = its first packet) in which it carries pictures
 * ([RecordingMediaProber.probePacketRanges]); a camera that was switched off leaves a gap. Empty means "not measured": the whole
 * [durationSeconds] counts as one range. [durationSeconds] is the media or, failing that, the reported duration; `null` and no ranges
 * mean the track is visible until the end of the recording.
 */
data class RecordingComposeVideoInput(
    val file: File,
    val offsetSeconds: Double,
    val isScreenShare: Boolean,
    val trackId: String = file.name,
    val ranges: List<TimeRange> = emptyList(),
    val durationSeconds: Double? = null,
    /**
     * V1.9.115: an OPAQUE key of the person whose camera this is (the id of the person's first track row) -- never an identity or a name.
     * Matches [RecordingSpeakerSpan.personKey]; `null` for screen shares and when the speaker layout is not used.
     */
    val personKey: String? = null,
)

/**
 * One decoded audio track -- same [offsetSeconds] semantics as [RecordingComposeVideoInput]. [ranges] are the data stretches of the
 * file's own media timeline (empty = not measured, the whole [mediaDurationSeconds] counts), [mediaDurationSeconds] the measured media
 * length and [wallDurationSeconds] the duration the egress reported (start to end on the wall clock); the pair decides about `atempo`
 * ([AudioTempo]).
 */
data class RecordingComposeAudioInput(
    val file: File,
    val offsetSeconds: Double,
    val ranges: List<TimeRange> = emptyList(),
    val mediaDurationSeconds: Double? = null,
    val wallDurationSeconds: Double? = null,
)

/**
 * The full input to one composition -- see [FfmpegSegmentedComposer] for how this becomes a series of `ffmpeg` runs.
 *
 * [outputDurationSeconds] is the length of the composed output: it is computed up front by
 * [network.lapis.cloud.server.conference.RecordingPoller] from the real tracks' own known offsets and durations, rather than left for
 * ffmpeg to infer from a graph with infinite synthetic sources.
 */
data class RecordingComposeSpec(
    val videoInputs: List<RecordingComposeVideoInput>,
    val audioInputs: List<RecordingComposeAudioInput>,
    val outputDurationSeconds: Double,
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val frameRate: Int = 30,
    /** V1.9.115: the layout of the stretches without a screen share: the tile grid or the current speaker large. */
    val layout: RecordingLayout = RecordingLayout.GRID,
    /** V1.9.115: who is the large tile when (smoothed, wall-clock seconds); only read when [layout] is [RecordingLayout.SPEAKER]. */
    val speakerSpans: List<RecordingSpeakerSpan> = emptyList(),
)

/** V1.9.115 -- how the people are laid out in a recording (a screen share is always shown large, whatever the layout). */
enum class RecordingLayout { GRID, SPEAKER }

/** V1.9.115 -- [personKey] was the large tile from [startSeconds] to [endSeconds] of the recording's wall clock. */
data class RecordingSpeakerSpan(
    val personKey: String,
    val startSeconds: Double,
    val endSeconds: Double,
)
