package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AttachedImageGalleryDto
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.SocialPostImagePreviewDto

/**
 * V1.9.222 "Gemeinsame Bildergalerie" -- everything about a gallery EXCEPT the bytes (upload and delivery are HTTP routes,
 * `AttachedImageRoutes`).
 * New methods go to the end (Kilua numbers routes by declaration order). Since V1.9.224 this service is the second to last `registerService`
 * in `Application.kt` (`IDashboardService` is the last).
 *
 * Errors: an unknown id, or an owner the caller may not see, is [NotFoundException] (never a different answer -- no existence oracle).
 * A
 * gallery locked for its creator is [GalleryLockedException]; a visible gallery of somebody else is [ForbiddenException]; a re-order
 * that
 * is not
 * a permutation of ALL pictures is [GalleryOrderMismatchException]; a caption beyond the limit is [ConflictException]; a rate-limit hit
 * is
 * [ServiceBusyException].
 */
@RpcService
interface IAttachedImageService {
    suspend fun list(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
    ): AttachedImageGalleryDto

    /** [orderedIds] must be exactly the ids of all pictures of the owner, in the new order. The first one is the cover image. */
    suspend fun reorder(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        orderedIds: List<String>,
    ): AttachedImageGalleryDto

    /** Moves the picture to position 0; the others shift down without a gap. */
    suspend fun setCover(imageId: String): AttachedImageGalleryDto

    suspend fun updateCaption(
        imageId: String,
        caption: String?,
        altText: String?,
    ): AttachedImageGalleryDto

    /** The creator (while unlocked) or BOARD/ADMIN (always). */
    suspend fun delete(imageId: String): AttachedImageGalleryDto

    /**
     * V1.9.223 -- first picture + count per readable ROOT social post; at most `GalleryRules.SOCIAL_PREVIEW_MAX_IDS` ids (more is a
     * [ConflictException]). Unknown, malformed and invisible ids are simply absent from the result (the caller got the ids from its own
     * feed, a missing id means "no pictures"). Sorted by post id.
     */
    suspend fun listSocialPostPreviews(postIds: List<String>): List<SocialPostImagePreviewDto>
}
