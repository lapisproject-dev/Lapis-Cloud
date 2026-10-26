package network.lapis.cloud.server.gallery

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AttachedImageDto
import network.lapis.cloud.shared.domain.AttachedImageGalleryDto
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.SocialPostImagePreviewDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.GalleryLockedException
import network.lapis.cloud.shared.rpc.GalleryOrderMismatchException
import network.lapis.cloud.shared.rpc.IAttachedImageService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.222 -- the RPC side of the gallery (everything but the bytes). Rights come from [GalleryOwnerPolicy] only, rows from
 * [AttachedImageStore]
 * only. Every write takes the OWNER row lock first ([GalleryOwnerPolicy.lockForWriteInTx]), then the picture rows, and writes the audit
 * entry
 * last. Files are deleted AFTER the commit, outside `transaction {}` (Exposed may repeat the block). Logs carry ids and counts, never a
 * caption or a
 * path.
 *
 * Existence is never revealed: an unknown id, a malformed id, and a picture whose owner the caller may not see are the SAME
 * [NotFoundException].
 */
class AttachedImageService internal constructor(
    private val call: ApplicationCall,
    private val storage: AttachedImageStorage,
    private val writeRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
) : IAttachedImageService {
    override suspend fun list(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
    ): AttachedImageGalleryDto {
        val current = resolveCurrentMember(call)
        throttle(limiter = readRateLimiter, current = current)
        val id = ownerId.toGalleryUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val access =
                GalleryOwnerPolicy.readInTx(type = ownerType, ownerId = id, now = now, viewer = current)
                    ?: throw NotFoundException("Not found")
            galleryDto(type = ownerType, access = access, caller = current)
        }
    }

    override suspend fun reorder(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        orderedIds: List<String>,
    ): AttachedImageGalleryDto {
        val current = resolveCurrentMember(call)
        throttle(limiter = writeRateLimiter, current = current)
        val id = ownerId.toGalleryUuid()
        val ordered = orderedIds.map { runCatching { Uuid.parse(it) }.getOrElse { throw GalleryOrderMismatchException() } }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val access = lockOrNotFound(type = ownerType, ownerId = id, now = now, viewer = current)
            requireEditable(access = access, caller = current)
            if (!GalleryRules.supportsCoverAndOrder(ownerType)) throw GalleryLockedException()
            val existing = AttachedImageStore.listInTx(type = ownerType, ownerId = id).map { it.id }
            if (ordered.size != existing.size || ordered.toSet() != existing.toSet()) throw GalleryOrderMismatchException()
            AttachedImageStore.renumberInTx(type = ownerType, ownerId = id, orderedIds = ordered)
            galleryDto(type = ownerType, access = access, caller = current)
        }
    }

    override suspend fun setCover(imageId: String): AttachedImageGalleryDto {
        val current = resolveCurrentMember(call)
        throttle(limiter = writeRateLimiter, current = current)
        val id = imageId.toGalleryUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row = AttachedImageStore.findInTx(id) ?: throw NotFoundException("Not found")
            val access = lockOrNotFound(type = row.ownerType, ownerId = row.ownerId, now = now, viewer = current)
            requireEditable(access = access, caller = current)
            if (!GalleryRules.supportsCoverAndOrder(row.ownerType)) throw GalleryLockedException()
            val ordered = AttachedImageStore.listInTx(type = row.ownerType, ownerId = row.ownerId).map { it.id }
            if (id !in ordered) throw NotFoundException("Not found")
            AttachedImageStore.renumberInTx(
                type = row.ownerType,
                ownerId = row.ownerId,
                orderedIds =
                    listOf(id) + ordered.filter { it != id },
            )
            galleryDto(type = row.ownerType, access = access, caller = current)
        }
    }

    override suspend fun updateCaption(
        imageId: String,
        caption: String?,
        altText: String?,
    ): AttachedImageGalleryDto {
        val current = resolveCurrentMember(call)
        throttle(limiter = writeRateLimiter, current = current)
        val id = imageId.toGalleryUuid()
        val cleanCaption = cleanText(raw = caption, max = GalleryRules.CAPTION_MAX)
        val cleanAlt = cleanText(raw = altText, max = GalleryRules.ALT_MAX)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row = AttachedImageStore.findInTx(id) ?: throw NotFoundException("Not found")
            val access = lockOrNotFound(type = row.ownerType, ownerId = row.ownerId, now = now, viewer = current)
            requireEditable(access = access, caller = current)
            if (!GalleryRules.supportsCaption(row.ownerType)) {
                // A social post has no visible caption, and its alt text can be set exactly once (the composer sets it after the upload).
                if (cleanCaption != null) throw ConflictException("Captions are not supported here")
                if (row.altText != null) throw GalleryLockedException()
            }
            AttachedImageStore.updateCaptionInTx(imageId = id, caption = cleanCaption, altText = cleanAlt)
            galleryDto(type = row.ownerType, access = access, caller = current)
        }
    }

    override suspend fun delete(imageId: String): AttachedImageGalleryDto {
        val current = resolveCurrentMember(call)
        throttle(limiter = writeRateLimiter, current = current)
        val id = imageId.toGalleryUuid()
        val now = DbClock.nowLocalDateTime()
        var removedKey: Uuid? = null
        val result =
            transaction {
                val row = AttachedImageStore.findInTx(id) ?: throw NotFoundException("Not found")
                val access = lockOrNotFound(type = row.ownerType, ownerId = row.ownerId, now = now, viewer = current)
                val asCreator = GalleryOwnerPolicy.canEdit(access = access, caller = current)
                if (!asCreator && !GalleryOwnerPolicy.canModerate(current)) {
                    throw if (access.creatorId == current.memberId) GalleryLockedException() else ForbiddenException()
                }
                val removed = AttachedImageStore.deleteInTx(id) ?: throw NotFoundException("Not found")
                GalleryAudit.recordRemoved(caller = current, row = removed, moderatedBy = if (asCreator) null else current.memberId)
                removedKey = removed.storageKey
                galleryDto(type = row.ownerType, access = access, caller = current)
            }
        removedKey?.let { storage.delete(it) }
        logger.info { "Gallery image removed: imageId=$id memberId=${current.memberId}" }
        return result
    }

    override suspend fun listSocialPostPreviews(postIds: List<String>): List<SocialPostImagePreviewDto> {
        val current = resolveCurrentMember(call)
        throttle(limiter = readRateLimiter, current = current)
        if (postIds.size > GalleryRules.SOCIAL_PREVIEW_MAX_IDS) throw ConflictException("Too many ids")
        val ids = postIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.distinct()
        return transaction {
            val readable = GalleryOwnerPolicy.readableSocialPostIdsInTx(ids = ids, viewer = current)
            AttachedImageStore
                .previewsFor(type = AttachedImageOwnerType.SOCIAL_POST, ownerIds = readable)
                .map { (ownerId, preview) ->
                    SocialPostImagePreviewDto(
                        postId = ownerId.toString(),
                        first = preview.first.toDto(callerId = current.memberId),
                        totalCount = preview.count,
                    )
                }.sortedBy { it.postId }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun throttle(
        limiter: FederationInboxRateLimiter,
        current: CurrentMember,
    ) {
        if (!limiter.checkAndRecord("member:${current.memberId}")) throw ServiceBusyException()
    }

    private fun lockOrNotFound(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        now: LocalDateTime,
        viewer: CurrentMember,
    ): GalleryOwnerAccess =
        GalleryOwnerPolicy.lockForWriteInTx(type = type, ownerId = ownerId, now = now, viewer = viewer)
            ?: throw NotFoundException("Not found")

    /** The creator while unlocked. A creator of a frozen gallery gets [GalleryLockedException]; anybody else who can see it gets
     * [ForbiddenException]. */
    private fun requireEditable(
        access: GalleryOwnerAccess,
        caller: CurrentMember,
    ) {
        if (access.creatorId != caller.memberId) throw ForbiddenException()
        if (access.lockReason != null) throw GalleryLockedException()
    }

    private fun galleryDto(
        type: AttachedImageOwnerType,
        access: GalleryOwnerAccess,
        caller: CurrentMember,
    ): AttachedImageGalleryDto =
        AttachedImageGalleryDto(
            ownerType = type,
            ownerId = access.ownerId.toString(),
            images = AttachedImageStore.listInTx(type = type, ownerId = access.ownerId).map { it.toDto(callerId = caller.memberId) },
            canEdit = GalleryOwnerPolicy.canEdit(access = access, caller = caller),
            canModerate = GalleryOwnerPolicy.canModerate(caller),
            lockReason = access.lockReason,
            maxImages = GalleryRules.maxImagesFor(type),
            maxTotalBytes = GalleryRules.MAX_TOTAL_BYTES_PER_OWNER,
        )

    private fun AttachedImageRow.toDto(callerId: Uuid) =
        AttachedImageDto(
            id = id.toString(),
            position = position,
            caption = caption,
            altText = altText,
            url = GalleryUrls.imageUrl(imageId = id, storageKey = storageKey),
            width = width,
            height = height,
            byteSize = byteSize,
            createdAt = createdAt,
            uploadedByMe = uploadedBy == callerId,
        )

    /** Trims, drops control characters, `null` for blank; text beyond [max] is a [ConflictException] (never silently cut). */
    private fun cleanText(
        raw: String?,
        max: Int,
    ): String? {
        val cleaned = raw?.filter { !it.isISOControl() }?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        if (cleaned.length > max) throw ConflictException("Text is longer than $max characters")
        return cleaned
    }

    private fun String.toGalleryUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Not found") }
}
