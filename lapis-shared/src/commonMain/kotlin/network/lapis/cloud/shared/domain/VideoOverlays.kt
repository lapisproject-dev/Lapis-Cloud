package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.129 "Video-Overlays (Logos, Name, Rahmen) im eigenen Kamerabild" -- the two kinds of library element an ADMIN may upload.
 *
 * NOT to be confused with the DOM overlays of V1.9.127 (reactions, raised hands): those are client-side DOM and never part of the
 * recording. A video overlay is composited INTO the person's own camera frames before they are sent, so everyone sees it -- and it is
 * part of the recording and the livestream.
 */
@Serializable
enum class VideoOverlayKind {
    /** A logo (PNG with transparency or JPEG) that a member may place in one of the four corners. */
    LOGO,

    /** A 16:9 PNG with transparency that is laid over the whole camera frame. */
    FRAME,
}

/** Limits and rules of the video overlay library -- shared so the admin screen can pre-check what the server enforces. */
object VideoOverlayRules {
    const val MAX_ITEMS = 50
    const val NAME_MIN = 1
    const val NAME_MAX = 60
    const val LOGO_MAX_UPLOAD_BYTES = 2L * 1024 * 1024
    const val FRAME_MAX_UPLOAD_BYTES = 4L * 1024 * 1024

    /** A logo needs a long edge of at least this many pixels (a wide wordmark is fine) ... */
    const val LOGO_MIN_LONG_EDGE_PX = 256

    /** ... and a short edge of at least this many pixels. */
    const val LOGO_MIN_SHORT_EDGE_PX = 64

    /** Stored (re-encoded) logos are scaled down to this long edge; never upscaled. */
    const val LOGO_TARGET_LONG_EDGE_PX = 1024
    const val FRAME_MIN_WIDTH_PX = 1280
    const val FRAME_MIN_HEIGHT_PX = 720

    /** Stored (re-encoded) frames are scaled down to this long edge; never upscaled. */
    const val FRAME_TARGET_LONG_EDGE_PX = 1920
    const val FRAME_ASPECT = 16.0 / 9.0
    const val FRAME_ASPECT_TOLERANCE = 0.01

    /** The id of the synthetic library element that stands for the instance logo (`/api/branding/logo`). */
    const val INSTANCE_LOGO_ID = "instance-logo"

    /** Trims [raw]; `null` unless the result is 1..60 characters without control or format characters. */
    fun normalizeName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length < NAME_MIN || trimmed.length > NAME_MAX) return null
        if (trimmed.any { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT }) return null
        return trimmed
    }
}

/** Machine code of a refused overlay upload -- the client maps each to a fixed sentence, never to a server text. */
@Serializable
enum class VideoOverlayUploadError {
    INVALID_REQUEST,
    FORBIDDEN,
    RATE_LIMITED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    TOO_SMALL,
    DIMENSIONS_TOO_LARGE,
    UNDECODABLE,
    FRAME_NEEDS_ALPHA,
    FRAME_WRONG_ASPECT,
    FRAME_NOT_PNG,
    LIBRARY_FULL,
    INVALID_NAME,
}

@Serializable
data class VideoOverlayUploadResultDto(
    val ok: Boolean,
    val error: VideoOverlayUploadError? = null,
    val item: VideoOverlayItemDto? = null,
)

/**
 * One element of the overlay library as a member sees it. [imagePath] is always a same-origin path
 * (`/api/video-overlays/<token>` or, for the instance logo, `/api/branding/logo`) -- never another origin.
 */
@Serializable
data class VideoOverlayItemDto(
    /** A UUID, or [VideoOverlayRules.INSTANCE_LOGO_ID]. */
    val id: String,
    val kind: VideoOverlayKind,
    val name: String,
    /** `0` when unknown (the instance logo as WebP); the client then uses the decoded bitmap's size. */
    val width: Int,
    val height: Int,
    val imagePath: String,
    val instanceLogo: Boolean = false,
)

/** What a member may choose from. [offered] `false` => [items] is empty. */
@Serializable
data class VideoOverlayLibraryDto(
    val offered: Boolean,
    val items: List<VideoOverlayItemDto>,
)

@Serializable
data class VideoOverlayAdminItemDto(
    val item: VideoOverlayItemDto,
    val sizeBytes: Long,
    val createdAt: LocalDateTime,
)

/**
 * The admin's view of the library. [instanceLogoOffered] tells whether the instance logo is part of the member library;
 * [instanceLogoReason] is `"svg"` (the branding logo is an SVG, which is never offered) or `"none"` (no branding logo) when it is not.
 */
@Serializable
data class VideoOverlayAdminLibraryDto(
    val offered: Boolean,
    val items: List<VideoOverlayAdminItemDto>,
    val instanceLogoOffered: Boolean,
    val instanceLogoReason: String? = null,
)
