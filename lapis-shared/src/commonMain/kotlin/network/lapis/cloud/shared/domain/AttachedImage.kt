package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.222 "Gemeinsame Bildergalerie" -- the object a gallery belongs to. Literal order is load-bearing:
 * `ImageGallerySchemaDriftTest` pins `ErmDataType.Enum.values` against this exact order (= `75-image-gallery.kuml.kts`).
 * V1.9.223: a ROOT social post joins as [SOCIAL_POST] (migration V120 widens the `owner_type` CHECK); new literals go to the end.
 */
@Serializable
enum class AttachedImageOwnerType { CROWDFUNDING_PROJECT, AUCTION_LISTING, SOCIAL_POST }

/** Why a gallery can no longer be changed by its creator. Absent (`null`) while it can. */
@Serializable
enum class GalleryLockReason { AUCTION_HAS_BIDS, AUCTION_CLOSED, CROWDFUNDING_DECIDED, SOCIAL_POST_PUBLISHED }

/**
 * The stable error codes of the HTTP upload route (`POST /api/images/{ownerType}/{ownerId}`). The client translates them; a server text
 * is
 * never shown (the route answers with JSON, never with free text).
 */
@Serializable
enum class GalleryUploadErrorCode {
    GALLERY_LOCKED,
    GALLERY_FULL,
    GALLERY_BYTES_EXCEEDED,
    IMAGE_TOO_LARGE,
    IMAGE_TYPE_NOT_ALLOWED,
    IMAGE_UNREADABLE,
    IMAGE_DIMENSIONS_EXCEEDED,
    RATE_LIMITED,
    NOT_FOUND,
    INVALID_ORIGIN,
    SERVER_BUSY,

    /** The caller can see the owner but is not its creator, or is no active member. */
    FORBIDDEN,

    /** V1.9.223: the author already uploaded [GalleryRules.SOCIAL_POST_IMAGES_PER_AUTHOR_PER_DAY] social-post pictures in 24 hours. */
    DAILY_LIMIT_REACHED,
}

/** One picture of a gallery. [url] is the same-origin, authenticated delivery URL (`/api/images/{id}?v=...`). */
@Serializable
data class AttachedImageDto(
    val id: String,
    val position: Int,
    val caption: String?,
    val altText: String?,
    val url: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val createdAt: LocalDateTime,
    val uploadedByMe: Boolean,
)

/**
 * A whole gallery plus what the caller may do with it. [canEdit]: the creator of the owner object while [lockReason] is `null`.
 * [canModerate]: BOARD/ADMIN, who may remove pictures even while locked (and nothing else).
 */
@Serializable
data class AttachedImageGalleryDto(
    val ownerType: AttachedImageOwnerType,
    val ownerId: String,
    val images: List<AttachedImageDto>,
    val canEdit: Boolean,
    val canModerate: Boolean,
    val lockReason: GalleryLockReason?,
    val maxImages: Int,
    val maxTotalBytes: Long,
)

/** V1.9.223 -- the first picture and the picture count of a readable ROOT social post (feed preview). */
@Serializable
data class SocialPostImagePreviewDto(
    val postId: String,
    val first: AttachedImageDto,
    val totalCount: Int,
)

/** Plain-JSON answer of the HTTP upload route: primitives only (no Decimal / LocalDateTime, see EventCoverHttp). */
@Serializable
data class AttachedImageUploadResultDto(
    val id: String? = null,
    val url: String? = null,
    val position: Int? = null,
    val error: GalleryUploadErrorCode? = null,
)

/**
 * After-snapshot of an [AuditEntityType.ATTACHED_IMAGE] entry: owner, position, size and type only -- never a caption, a file name or a
 * storage
 * key. [moderatedBy] is set when BOARD/ADMIN removed somebody else's picture.
 */
@Serializable
data class AttachedImageSnapshot(
    val ownerType: String,
    val ownerId: String,
    val position: Int,
    val byteSize: Long,
    val mimeType: String,
    val moderatedBy: String? = null,
)

/** The limits of a gallery. One source for server and client. */
object GalleryRules {
    const val MAX_UPLOAD_BYTES: Long = 5L * 1024 * 1024
    const val MAX_TOTAL_BYTES_PER_OWNER: Long = 30L * 1024 * 1024
    const val CAPTION_MAX: Int = 200
    const val ALT_MAX: Int = 200
    const val UPLOADS_PER_MEMBER_PER_HOUR: Int = 30

    /** Longest edge of a source picture; the server stores at most 1600 px. */
    const val MAX_SOURCE_EDGE_PX: Int = 8000

    /** The `accept` attribute of the file inputs. WebP is accepted as an INPUT only; it is stored as JPEG (PNG with alpha). */
    const val ACCEPT_ATTRIBUTE: String = "image/jpeg,image/png,image/webp"

    /** V1.9.223: a social post carries at most this many pictures. */
    const val SOCIAL_POST_MAX_IMAGES: Int = 4

    /** V1.9.223: pictures one author may attach to social posts per rolling 24 hours (in-memory counter, resets on restart). */
    const val SOCIAL_POST_IMAGES_PER_AUTHOR_PER_DAY: Int = 20

    /** V1.9.223: after publishing, the author may add/remove pictures of a post for this many minutes only. */
    const val SOCIAL_POST_GALLERY_GRACE_MINUTES: Int = 10

    /** V1.9.223: ids accepted by one `listSocialPostPreviews` call. */
    const val SOCIAL_PREVIEW_MAX_IDS: Int = 50

    fun maxImagesFor(type: AttachedImageOwnerType): Int =
        when (type) {
            AttachedImageOwnerType.CROWDFUNDING_PROJECT, AttachedImageOwnerType.AUCTION_LISTING -> 10
            AttachedImageOwnerType.SOCIAL_POST -> SOCIAL_POST_MAX_IMAGES
        }

    /** Cover image and re-ordering exist for every owner except a social post (its first picture is simply the first). */
    fun supportsCoverAndOrder(type: AttachedImageOwnerType): Boolean = type != AttachedImageOwnerType.SOCIAL_POST

    /** A visible caption exists for every owner except a social post (only the alt text, set once). */
    fun supportsCaption(type: AttachedImageOwnerType): Boolean = type != AttachedImageOwnerType.SOCIAL_POST
}
