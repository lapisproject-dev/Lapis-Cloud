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
 * [FfmpegGalleryComposer] directly.
 */
interface RecordingComposer {
    /**
     * Composes [spec] into [outputFile] (created, or overwritten if it already exists). Throws
     * [RecordingComposeException] on ANY failure -- process could not start, non-zero exit, or
     * wall-clock timeout (the implementer's responsibility to enforce and to
     * `Process.destroyForcibly()` on timeout, see [FfmpegGalleryComposer] KDoc) -- never a raw
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
 * raw ffmpeg stderr (see [FfmpegGalleryComposer] KDoc "stderr never reaches the exception
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
 * regardless of when the underlying meeting actually began. [isScreenShare] switches
 * [FfmpegArgumentBuilder]'s layout from the default gallery grid to a presentation layout (this
 * track full-frame, the remaining video inputs rendered as a camera strip below) -- see that
 * object's KDoc "Gallery grid vs. presentation layout".
 */
data class RecordingComposeVideoInput(
    val file: File,
    val offsetSeconds: Double,
    val isScreenShare: Boolean,
)

/** One decoded audio track -- same [offsetSeconds] semantics as [RecordingComposeVideoInput]. */
data class RecordingComposeAudioInput(
    val file: File,
    val offsetSeconds: Double,
)

/**
 * The full input to one composition -- see [FfmpegArgumentBuilder.build] for how this becomes an
 * actual `ffmpeg` argument list.
 *
 * [outputDurationSeconds] bounds the two synthetic `lavfi` sources
 * ([FfmpegArgumentBuilder]'s black-canvas base, and the silent-audio fallback used when
 * [audioInputs] is empty) -- both are otherwise INFINITE ffmpeg sources (`color=`/`anullsrc=` with
 * no fixed duration), and an infinite base overlaid with finite real tracks stays infinite
 * (`overlay` simply passes the base stream through unchanged once the finite second input ends,
 * per ffmpeg's own documented `overlay` semantics) -- `-shortest` alone does not reliably truncate
 * that for a filtered, multi-input graph like this one. [outputDurationSeconds] is therefore
 * computed up front by [network.lapis.cloud.server.conference.RecordingPoller] from the real
 * tracks' own known offsets+durations, rather than left for ffmpeg to infer.
 */
data class RecordingComposeSpec(
    val videoInputs: List<RecordingComposeVideoInput>,
    val audioInputs: List<RecordingComposeAudioInput>,
    val outputDurationSeconds: Double,
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val frameRate: Int = 30,
)
