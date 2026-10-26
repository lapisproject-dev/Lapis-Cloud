package network.lapis.cloud.server.gallery

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.rpc.CrowdfundingWeightDecay
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.SocialVisibility
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.GalleryLockReason
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * What the policy knows about the owner of a gallery: who created it and why (if at all) the gallery is frozen. [lockReason] `null` =
 * the
 * creator
 * may still change the gallery.
 */
internal data class GalleryOwnerAccess(
    val ownerId: Uuid,
    val creatorId: Uuid,
    val lockReason: GalleryLockReason?,
)

/**
 * V1.9.222 -- THE one resolver from an owner type to rights. No other code decides whether a gallery is visible, editable or frozen.
 *
 * A `null` result means "does not exist OR the caller may not see it": the caller answers both with the SAME 404 (no existence oracle).
 *
 * - **Auction:** visible exactly when `auctionEnabled` is on (like `requireAuctionEnabled`; a disabled auction is a 404 for everybody,
 * the
 * seller
 *   and the moderation included) -- the session is already resolved by the caller. Frozen when the EFFECTIVE status is not OPEN
 * ([GalleryLockReason.AUCTION_CLOSED], also for an OPEN row whose `ends_at` has passed) or a bid exists
 * ([GalleryLockReason.AUCTION_HAS_BIDS]).
 * The lock is the same `FOR UPDATE` row `placeBid`/`buyNow`/`lockAndCloseIfDue` take, so "no bid yet" is race-free. This policy only
 * READS
 * the
 *   deadline: it never triggers the lazy close, i.e. no settlement side effect comes out of a picture upload.
 * - **Crowdfunding project:** visible to every resolved session (like `getProject`). Frozen as soon as the EFFECTIVE status is not
 * PENDING
 * ([GalleryLockReason.CROWDFUNDING_DECIDED]) -- that includes silence-is-approval after the review window, although the stored column
 * still
 * says
 *   PENDING. The project row lock makes `approveProject`'s compare-and-swap wait for an upload in flight.
 * - **Social post (V1.9.223):** the ONLY owner whose visibility depends on the viewer. Visible exactly when [socialImagesReadable]:
 *   a ROOT post (no comment), `state == VISIBLE` (stricter than `SocialVisibility.isReadable`, which also lets `HIDDEN_BY_AUTHOR`
 *   through), not content-erased, and readable for the viewer's member status. The creator is the author. The gallery is frozen
 *   ([GalleryLockReason.SOCIAL_POST_PUBLISHED]) [SOCIAL_POST_GALLERY_GRACE] after `published_at`. The `social_post` row is the lock;
 *   `hideOwnPost`/`removePostForLegalReason` lock the same row, so a state change and an upload in flight serialise. There is no
 *   social-level block/mute today (only MCP and member-finder blocks); `GalleryTripwireTest` forces this branch to be extended the
 *   day one appears.
 */
internal object GalleryOwnerPolicy {
    /** Reads AND locks (`FOR UPDATE`) the owner row. Must be the first lock of a writing transaction. */
    fun lockForWriteInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        now: LocalDateTime,
        viewer: CurrentMember,
    ): GalleryOwnerAccess? = resolve(type = type, ownerId = ownerId, now = now, viewer = viewer, lock = true)

    /** Reads without locking. */
    fun readInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        now: LocalDateTime,
        viewer: CurrentMember,
    ): GalleryOwnerAccess? = resolve(type = type, ownerId = ownerId, now = now, viewer = viewer, lock = false)

    /** The creator of the owner object, while the gallery is not frozen. BOARD/ADMIN get NO edit right from their role. */
    fun canEdit(
        access: GalleryOwnerAccess,
        caller: CurrentMember,
    ): Boolean = access.creatorId == caller.memberId && access.lockReason == null

    /** BOARD/ADMIN may remove a picture at any time (and do nothing else). */
    fun canModerate(caller: CurrentMember): Boolean = caller.role == AccountRole.BOARD || caller.role == AccountRole.ADMIN

    private fun resolve(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        now: LocalDateTime,
        viewer: CurrentMember,
        lock: Boolean,
    ): GalleryOwnerAccess? =
        when (type) {
            AttachedImageOwnerType.AUCTION_LISTING -> resolveAuction(ownerId = ownerId, now = now, lock = lock)
            AttachedImageOwnerType.CROWDFUNDING_PROJECT -> resolveProject(ownerId = ownerId, now = now, lock = lock)
            AttachedImageOwnerType.SOCIAL_POST -> resolveSocialPost(ownerId = ownerId, now = now, viewer = viewer, lock = lock)
        }

    /** One query: the ROOT posts of [ids] whose pictures [viewer] may see (same rule as [socialImagesReadable]), sorted by id. */
    fun readableSocialPostIdsInTx(
        ids: Collection<Uuid>,
        viewer: CurrentMember,
    ): Set<Uuid> {
        if (ids.isEmpty()) return emptySet()
        val result = LinkedHashSet<Uuid>()
        ids.distinct().chunked(SOCIAL_ID_CHUNK).forEach { chunk ->
            SocialPostTable
                .select(SocialPostTable.id)
                .where {
                    (SocialPostTable.id inList chunk) and SocialPostTable.parentId.isNull() and
                        (SocialPostTable.state eq SocialPostState.VISIBLE) and SocialPostTable.contentErasedAt.isNull() and
                        SocialVisibility.readableByCondition(status = viewer.status)
                }.orderBy(SocialPostTable.id to SortOrder.ASC)
                .forEach { result += it[SocialPostTable.id] }
        }
        return result
    }

    private fun resolveSocialPost(
        ownerId: Uuid,
        now: LocalDateTime,
        viewer: CurrentMember,
        lock: Boolean,
    ): GalleryOwnerAccess? {
        val query = SocialPostTable.selectAll().where { SocialPostTable.id eq ownerId }
        val row = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        if (!socialImagesReadable(row = row, status = viewer.status)) return null
        val closesAt = row[SocialPostTable.publishedAt].toInstant(ServerClock.zone) + SOCIAL_POST_GALLERY_GRACE
        val reason = if (now.toInstant(ServerClock.zone) >= closesAt) GalleryLockReason.SOCIAL_POST_PUBLISHED else null
        return GalleryOwnerAccess(ownerId = ownerId, creatorId = row[SocialPostTable.authorMemberId], lockReason = reason)
    }

    private fun resolveAuction(
        ownerId: Uuid,
        now: LocalDateTime,
        lock: Boolean,
    ): GalleryOwnerAccess? {
        val enabled =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.auctionEnabled)
                ?: false
        if (!enabled) return null
        val query = AuctionTable.selectAll().where { AuctionTable.id eq ownerId }
        val row = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        val closed = row[AuctionTable.status] != AuctionStatus.OPEN || now >= row[AuctionTable.endsAt]
        val reason =
            when {
                closed -> GalleryLockReason.AUCTION_CLOSED
                AuctionBidTable
                    .selectAll()
                    .where { AuctionBidTable.auctionId eq ownerId }
                    .limit(
                        1,
                    ).any() -> GalleryLockReason.AUCTION_HAS_BIDS
                else -> null
            }
        return GalleryOwnerAccess(ownerId = ownerId, creatorId = row[AuctionTable.sellerMemberId], lockReason = reason)
    }

    private fun resolveProject(
        ownerId: Uuid,
        now: LocalDateTime,
        lock: Boolean,
    ): GalleryOwnerAccess? {
        val query = CrowdfundingProjectTable.selectAll().where { CrowdfundingProjectTable.id eq ownerId }
        val row = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        val decided =
            row[CrowdfundingProjectTable.status] != CrowdfundingProjectStatus.PENDING ||
                CrowdfundingWeightDecay.isAutoApproved(submittedAt = row[CrowdfundingProjectTable.submittedAt], now = now)
        return GalleryOwnerAccess(
            ownerId = ownerId,
            creatorId = row[CrowdfundingProjectTable.submitterMemberId],
            lockReason = if (decided) GalleryLockReason.CROWDFUNDING_DECIDED else null,
        )
    }

    /** Re-order and cover do not exist for a social post (its first picture is simply the first). */
    fun allowsReorder(type: AttachedImageOwnerType): Boolean = GalleryRules.supportsCoverAndOrder(type)

    private const val SOCIAL_ID_CHUNK = 500
}

/** After this long the author can no longer add or remove pictures of a published post. */
internal val SOCIAL_POST_GALLERY_GRACE = GalleryRules.SOCIAL_POST_GALLERY_GRACE_MINUTES.minutes

/**
 * THE image-visibility rule of a social post. Stricter than `SocialVisibility.isReadable`: ROOT posts only (a comment is no owner),
 * `VISIBLE` only (not `HIDDEN_BY_AUTHOR`, not `REMOVED_LEGAL`), not content-erased. Kept in step with the SQL twin in
 * [GalleryOwnerPolicy.readableSocialPostIdsInTx].
 */
internal fun socialImagesReadable(
    row: ResultRow,
    status: MemberStatus,
): Boolean =
    row[SocialPostTable.parentId] == null &&
        row[SocialPostTable.state] == SocialPostState.VISIBLE &&
        row[SocialPostTable.contentErasedAt] == null &&
        SocialVisibility.isReadable(visibility = row[SocialPostTable.visibility], state = row[SocialPostTable.state], status = status)
