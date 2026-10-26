package network.lapis.cloud.server.gallery

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.rpc.requireActiveMembership
import network.lapis.cloud.server.rpc.requireLtrEligibleMembership
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AttachedImageSnapshot
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.uuid.Uuid

/** The per-owner limits. Production uses the defaults ([GalleryRules]); tests pass small values instead of storing 30 MiB. */
internal class GalleryLimits(
    val maxBytesPerOwner: Long = GalleryRules.MAX_TOTAL_BYTES_PER_OWNER,
    private val maxImagesOverride: Int? = null,
) {
    fun maxImages(type: AttachedImageOwnerType): Int = maxImagesOverride ?: GalleryRules.maxImagesFor(type)
}

/** The decision of [GalleryUploadGate.checkInTx]. */
internal sealed interface GalleryGateResult {
    class Allowed(
        val access: GalleryOwnerAccess,
        val count: Int,
        val bytes: Long,
    ) : GalleryGateResult

    /** The owner does not exist or the caller may not see it. */
    data object NotFound : GalleryGateResult

    /** The caller is the creator, but the gallery is frozen. */
    data object Locked : GalleryGateResult

    /** The caller may see the owner but is not its creator (or is no active member). */
    data object Forbidden : GalleryGateResult

    data object Full : GalleryGateResult

    data object BytesExceeded : GalleryGateResult
}

/**
 * V1.9.222 -- the ONE decision "may [caller] add a picture of [newBytes] bytes to this owner now?", used twice by the upload route: as
 * a
 * cheap
 * pre-check before any decode (so no decode is spent on a hopeless request) and again UNDER the owner row lock right before the insert
 * (the
 * pre-check
 * is not trustworthy: two uploads can both pass it -- TOCTOU).
 */
internal object GalleryUploadGate {
    fun checkInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        caller: CurrentMember,
        now: LocalDateTime,
        limits: GalleryLimits,
        newBytes: Long,
        lock: Boolean,
    ): GalleryGateResult {
        val access =
            (
                if (lock) {
                    GalleryOwnerPolicy.lockForWriteInTx(type = type, ownerId = ownerId, now = now, viewer = caller)
                } else {
                    GalleryOwnerPolicy.readInTx(type = type, ownerId = ownerId, now = now, viewer = caller)
                }
            ) ?: return GalleryGateResult.NotFound
        if (access.creatorId != caller.memberId) return GalleryGateResult.Forbidden
        if (access.lockReason != null) return GalleryGateResult.Locked
        try {
            // A social post may be written by a FRIEND too (createPost -> requireLtrEligibleMembership), so may its pictures.
            when (type) {
                AttachedImageOwnerType.SOCIAL_POST -> requireLtrEligibleMembership(memberId = caller.memberId)
                AttachedImageOwnerType.AUCTION_LISTING, AttachedImageOwnerType.CROWDFUNDING_PROJECT ->
                    requireActiveMembership(memberId = caller.memberId)
            }
        } catch (_: ForbiddenException) {
            return GalleryGateResult.Forbidden
        }
        val (count, bytes) = AttachedImageStore.countAndBytesInTx(type = type, ownerId = ownerId)
        if (count >= limits.maxImages(type)) return GalleryGateResult.Full
        if (bytes + newBytes > limits.maxBytesPerOwner) return GalleryGateResult.BytesExceeded
        return GalleryGateResult.Allowed(access = access, count = count, bytes = bytes)
    }

    /**
     * The write half of an upload, inside the caller's transaction: LOCKS the owner row, REPEATS the whole gate (the pre-check is no
     * guarantee),
     * inserts the picture at the end of the gallery and writes the audit entry as the LAST operation. A refusal changes nothing.
     */
    fun commitInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        caller: CurrentMember,
        now: LocalDateTime,
        limits: GalleryLimits,
        imageId: Uuid,
        storageKey: Uuid,
        mimeType: String,
        byteSize: Long,
        width: Int,
        height: Int,
    ): GalleryCommitResult {
        val gate = checkInTx(type = type, ownerId = ownerId, caller = caller, now = now, limits = limits, newBytes = byteSize, lock = true)
        if (gate !is GalleryGateResult.Allowed) return GalleryCommitResult.Refused(gate)
        val row =
            AttachedImageStore.insertInTx(
                id = imageId,
                type = type,
                ownerId = ownerId,
                storageKey = storageKey,
                mimeType = mimeType,
                byteSize = byteSize,
                width = width,
                height = height,
                uploadedBy = caller.memberId,
                createdAt = now,
            )
        GalleryAudit.recordAdded(caller = caller, row = row)
        return GalleryCommitResult.Added(row)
    }
}

/** The outcome of [GalleryUploadGate.commitInTx]. */
internal sealed interface GalleryCommitResult {
    class Added(
        val row: AttachedImageRow,
    ) : GalleryCommitResult

    class Refused(
        val gate: GalleryGateResult,
    ) : GalleryCommitResult
}

/** Audit entries of the gallery -- ids, enums and sizes only, never a caption, a file name or a storage key. Call as the LAST operation
 * of
 * a transaction. */
internal object GalleryAudit {
    fun recordAdded(
        caller: CurrentMember,
        row: AttachedImageRow,
    ) {
        AuditLogRecorder.record(
            actorMemberId = caller.memberId,
            actorRole = caller.role,
            entityType = AuditEntityType.ATTACHED_IMAGE,
            entityId = row.id,
            action = AuditAction.CREATE,
            before = null,
            after = Json.encodeToString(AttachedImageSnapshot.serializer(), snapshotOf(row = row, moderatedBy = null)),
        )
    }

    fun recordRemoved(
        caller: CurrentMember,
        row: AttachedImageRow,
        moderatedBy: Uuid?,
    ) {
        AuditLogRecorder.record(
            actorMemberId = caller.memberId,
            actorRole = caller.role,
            entityType = AuditEntityType.ATTACHED_IMAGE,
            entityId = row.id,
            action = AuditAction.VOID,
            before = Json.encodeToString(AttachedImageSnapshot.serializer(), snapshotOf(row = row, moderatedBy = moderatedBy)),
            after = null,
        )
    }

    private fun snapshotOf(
        row: AttachedImageRow,
        moderatedBy: Uuid?,
    ) = AttachedImageSnapshot(
        ownerType = row.ownerType.name,
        ownerId = row.ownerId.toString(),
        position = row.position,
        byteSize = row.byteSize,
        mimeType = row.mimeType,
        moderatedBy = moderatedBy?.toString(),
    )
}
