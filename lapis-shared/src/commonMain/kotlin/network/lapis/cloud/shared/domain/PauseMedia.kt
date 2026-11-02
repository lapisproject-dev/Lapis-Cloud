package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.255 -- limits of the pause picture and the pause music of the live stream, shared so the admin screen can show what the server
 * enforces. The picture and the music are heard and seen ONLY in the live stream while the room pause holds it -- never in the recording,
 * never in the room.
 */
object PauseMediaRules {
    /** Upload cap of the picture (JPEG, PNG or WebP). */
    const val IMAGE_MAX_UPLOAD_BYTES = 10L * 1024 * 1024

    /** Smallest accepted picture (after the EXIF rotation). */
    const val IMAGE_MIN_WIDTH_PX = 640
    const val IMAGE_MIN_HEIGHT_PX = 360

    /** Every picture is stored letterboxed to this frame. */
    const val IMAGE_FRAME_WIDTH_PX = 1280
    const val IMAGE_FRAME_HEIGHT_PX = 720

    /** Upload cap of the music (MP3, M4A, OGG, FLAC or WAV). */
    const val MUSIC_MAX_UPLOAD_BYTES = 15L * 1024 * 1024
    const val MUSIC_MIN_SECONDS = 5
    const val MUSIC_MAX_SECONDS = 600
    const val MUSIC_LABEL_MAX = 80
}

/** The fixed vocabulary of a refused pause-media upload -- an enum code only, never a server text. */
@Serializable
enum class PauseMediaUploadError {
    INVALID_REQUEST,
    FORBIDDEN,
    RATE_LIMITED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    TOO_SMALL,
    DIMENSIONS_TOO_LARGE,
    UNDECODABLE,
    TOO_SHORT,
    TOO_LONG,
    HAS_VIDEO,
    PROCESSING_UNAVAILABLE,
    BUSY,
}

/** Answer of the pause-media upload and delete routes. */
@Serializable
data class PauseMediaResultDto(
    val ok: Boolean,
    val error: PauseMediaUploadError? = null,
    val imageCustom: Boolean = false,
    val imageVersion: String? = null,
    val musicLabel: String? = null,
    val musicDurationSeconds: Int? = null,
    val musicVersion: String? = null,
)
