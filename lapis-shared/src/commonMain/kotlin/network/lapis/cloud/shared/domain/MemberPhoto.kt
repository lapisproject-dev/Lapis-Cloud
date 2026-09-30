package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- visibility of a member's own profile photo. A photo is
 * ALWAYS [PRIVATE] after an upload or replacement; only an explicit, versioned consent from the
 * member ([MemberPhotoRules.CONSENT_TEXT_VERSION]) moves it to [PUBLIC].
 */
@Serializable
enum class MemberPhotoVisibility {
    PRIVATE,
    PUBLIC,
}

/**
 * Shared numeric/policy constants for the member photo, mirrored server- (validation, processing)
 * and client-side (pre-checks, consent version) so both sides agree without duplicated magic
 * numbers. The server is the AUTHORITATIVE enforcer of every one of these.
 */
object MemberPhotoRules {
    /** Maximum accepted raw upload size (server enforces at `Content-Length` and while streaming). */
    const val MAX_UPLOAD_BYTES = 10L * 1024 * 1024

    /** Shorter edge of the source image must be at least this many pixels. */
    const val MIN_SHORT_EDGE_PX = 400

    /** Stored photo is a square of at most this edge length; never upscaled. */
    const val TARGET_EDGE_PX = 800

    /**
     * Version tag of the consent wording shown in the publish dialog (`MemberPhotoCard`). A change
     * of that wording REQUIRES a new version -- `MemberPhotoConsentTextPinTest` fails the build
     * otherwise.
     */
    const val CONSENT_TEXT_VERSION = "member-photo-public-v1"

    val ACCEPTED_MIME_TYPES: Set<String> = setOf("image/jpeg", "image/png")
}

/** The caller's OWN photo state. Never carries the public token, the storage key or image bytes. */
@Serializable
data class OwnMemberPhotoDto(
    val hasPhoto: Boolean,
    val visibility: MemberPhotoVisibility,
    val widthPx: Int?,
    val heightPx: Int?,
    val uploadedAt: LocalDateTime?,
    /** First 8 characters of the stored file's UUID -- cache buster for the `<img src>` preview. */
    val previewVersion: String?,
    /** Absolute public URL, only while [visibility] is [MemberPhotoVisibility.PUBLIC]. */
    val publicUrl: String?,
    /** The consent version the server currently requires for [MemberPhotoVisibility.PUBLIC]. */
    val requiredConsentTextVersion: String,
)

/** Machine-readable outcome of a failed upload. The browser maps each to a fixed, translated message -- server free text is never shown. */
@Serializable
enum class MemberPhotoUploadError {
    UNSUPPORTED_FORMAT,
    FILE_TOO_LARGE,
    TOO_SMALL,
    DIMENSIONS_TOO_LARGE,
    UNDECODABLE,
    RATE_LIMITED,
    NOT_ELIGIBLE,
    INVALID_REQUEST,
    BUSY,
}

/** JSON body of every upload response; [error] is `null` on success. */
@Serializable
data class MemberPhotoUploadResultDto(
    val error: MemberPhotoUploadError? = null,
)
