package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- shared numeric/policy
 * constants for a member's own uploaded conference background images, mirrored server- (upload
 * validation, `ConferenceBackgroundImageProcessor`) and client-side (pre-normalization,
 * `ConferenceBackgroundUploads.kt`) so both sides agree on the SAME limits without duplicating
 * magic numbers. See `docs/architecture/video-background-effects.adoc` § "Custom backgrounds" for
 * the full rationale.
 *
 * The server is the AUTHORITATIVE enforcer of every one of these -- the client-side
 * pre-normalization is a courtesy (smaller upload, immediate feedback), never a security boundary.
 */
object ConferenceBackgroundRules {
    /** Maximum accepted upload size, AFTER the client's own JPEG re-encode (server enforces this at `Content-Length` and while streaming). */
    const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024

    /** Shorter side must be at least this many pixels (checked from the file header alone, before decoding). */
    const val MIN_SIDE_PX = 320

    /** Longer side must be at most this many pixels (checked from the file header alone, before decoding -- decompression-bomb guard). */
    const val MAX_SIDE_PX = 4096

    /** Stored main image is never larger than this on its long edge. */
    const val MAX_OUTPUT_LONG_EDGE_PX = 1920

    const val THUMB_WIDTH_PX = 320
    const val THUMB_HEIGHT_PX = 180

    /** How many custom background images one member may have stored at once. */
    const val MAX_PER_MEMBER = 3
}

/** Wire shape of one stored custom background image -- deliberately no filename/MIME/storageKey (see route KDoc "no PII leak beyond the pixels"). */
@Serializable
data class ConferenceBackgroundImageDto(
    val id: String,
    val width: Int,
    val height: Int,
)
