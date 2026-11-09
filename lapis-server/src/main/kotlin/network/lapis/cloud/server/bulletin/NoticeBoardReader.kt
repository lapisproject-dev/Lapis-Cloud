package network.lapis.cloud.server.bulletin

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.gallery.AttachedImageStore
import network.lapis.cloud.server.gallery.GalleryUrls
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.server.rpc.SocialPostWeight
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticeImageRefDto
import network.lapis.cloud.shared.domain.NoticeRemovalReason
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** A notice row joined with its author, without any computation. [authorEligible]: the author is still an organization member and not anonymized. */
internal data class NoticeRow(
    val id: Uuid,
    val authorId: Uuid,
    val authorName: String,
    val authorEligible: Boolean,
    val category: NoticeCategory,
    val title: String,
    val body: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val expiresAt: LocalDateTime,
    val pinnedAt: LocalDateTime?,
    val status: NoticeStatus,
    val removalReason: NoticeRemovalReason?,
    val removalNote: String?,
)

/** One boost of a notice. */
internal data class NoticeBoostRow(
    val memberId: Uuid,
    val amountLtr: BigDecimal,
    val boostedAt: LocalDateTime,
)

/**
 * V1.9.297 -- every READ of the bulletin board, inside the caller's transaction. One place decides what a viewer sees ([visibleTo]) and how a row
 * becomes a [NoticeDto]; the service never builds a DTO itself.
 *
 * **Visibility.** The AUTHOR sees their own notice in every state (until it is deleted). Everybody else sees a notice only while it is `ACTIVE`,
 * its `expires_at` has not passed (the status alone is never trusted, the retention poller is only bookkeeping) and its author is still an
 * organization member who is not anonymized. Anything else is the SAME `NotFoundException` as an unknown id.
 *
 * **Caps (DoS).** One listing looks at no more than [MAX_CANDIDATES] candidate notices and [MAX_BOOST_ROWS] boost rows. A boost overflow is cut
 * after `boosted_at DESC` (the youngest boosts carry the most weight), so the error only ever points DOWN; the pinned notices are always
 * complete. The decay and rounding are [NoticeWeight]'s (= the social network's), never computed here.
 */
internal object NoticeBoardReader {
    const val MAX_CANDIDATES = 5_000
    const val MAX_BOOST_ROWS = 20_000
    private const val CHUNK = 500

    private val authorJoin = BulletinNoticeTable.join(MemberTable, JoinType.INNER, BulletinNoticeTable.authorMemberId, MemberTable.id)

    /** `true` while the notice is on the board: `ACTIVE` and not yet expired. */
    fun isLive(
        row: NoticeRow,
        now: LocalDateTime,
    ): Boolean = row.status == NoticeStatus.ACTIVE && row.expiresAt > now

    fun visibleTo(
        row: NoticeRow,
        viewerId: Uuid,
        now: LocalDateTime,
    ): Boolean = row.authorId == viewerId || (isLive(row = row, now = now) && row.authorEligible)

    fun isModerator(viewer: CurrentMember): Boolean = viewer.role == AccountRole.BOARD || viewer.role == AccountRole.ADMIN

    /** The rows of [ids] (author joined), in the order of [ids]; unknown ids are absent. */
    fun rowsOf(ids: List<Uuid>): List<NoticeRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = LinkedHashMap<Uuid, NoticeRow>()
        ids.distinct().chunked(CHUNK).forEach { chunk ->
            authorJoin
                .selectAll()
                .where { BulletinNoticeTable.id inList chunk }
                .orderBy(BulletinNoticeTable.id to SortOrder.ASC)
                .forEach { byId[it[BulletinNoticeTable.id]] = it.toNoticeRow() }
        }
        return ids.distinct().mapNotNull { byId[it] }
    }

    fun rowOf(id: Uuid): NoticeRow? = rowsOf(listOf(id)).singleOrNull()

    /** All boosts of [ids], per notice, at most [MAX_BOOST_ROWS] in total (youngest first when cut). */
    fun boostsOf(ids: Collection<Uuid>): Map<Uuid, List<NoticeBoostRow>> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Uuid, MutableList<NoticeBoostRow>>()
        var remaining = MAX_BOOST_ROWS
        for (chunk in ids.distinct().chunked(CHUNK)) {
            if (remaining <= 0) break
            val rows =
                BulletinNoticeBoostTable
                    .selectAll()
                    .where { BulletinNoticeBoostTable.noticeId inList chunk }
                    .orderBy(BulletinNoticeBoostTable.boostedAt to SortOrder.DESC, BulletinNoticeBoostTable.id to SortOrder.ASC)
                    .limit(remaining)
                    .toList()
            remaining -= rows.size
            rows.forEach {
                result.getOrPut(it[BulletinNoticeBoostTable.noticeId]) { ArrayList() } +=
                    NoticeBoostRow(
                        memberId = it[BulletinNoticeBoostTable.memberId],
                        amountLtr = it[BulletinNoticeBoostTable.amountLtr],
                        boostedAt = it[BulletinNoticeBoostTable.boostedAt],
                    )
            }
        }
        return result
    }

    fun contributions(boosts: List<NoticeBoostRow>): List<SocialPostWeight.BoostContribution> =
        boosts.map { SocialPostWeight.BoostContribution(amountLtr = it.amountLtr, boostedAt = it.boostedAt) }

    /**
     * Builds the DTOs of [rows] for [viewer], in the order of [rows]. [canBoost] and [canReply] are false for a notice that is not live.
     * [canReply] uses the SAME block sets as the recipient rule of a direct message (direct-message block and member-finder block, either way),
     * without telling WHY it is false.
     */
    fun toDtos(
        rows: List<NoticeRow>,
        viewer: CurrentMember,
        now: LocalDateTime,
    ): List<NoticeDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val boosts = boostsOf(ids)
        val images = AttachedImageStore.previewsFor(type = AttachedImageOwnerType.NOTICE, ownerIds = ids)
        val blocked = DirectMessageBlockStore.blockedEitherWay(viewer.memberId) + MemberFinderBlockStore.blockedEitherWay(viewer.memberId)
        val today = OrganizationTimeZone.dateOf(now)
        val moderator = isModerator(viewer)
        return rows.map { row ->
            val mine = row.authorId == viewer.memberId
            val live = isLive(row = row, now = now)
            val own = boosts[row.id].orEmpty()
            val expiresOn = NoticeValidation.expiresOnOf(row.expiresAt)
            val image = images[row.id]?.first
            NoticeDto(
                id = row.id.toString(),
                category = row.category,
                title = row.title,
                body = row.body,
                authorDisplayName = row.authorName,
                isMine = mine,
                status = effectiveStatus(row = row, now = now),
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                expiresAt = row.expiresAt,
                expiresOn = expiresOn,
                daysLeft = daysLeft(today = today, expiresOn = expiresOn),
                minExpiresOn = NoticeValidation.minExpiresOn(now),
                maxExpiresOn = NoticeValidation.maxExpiresOn(row.createdAt),
                pinnedAt = row.pinnedAt,
                weightLtr = NoticeWeight.weightOf(boosts = contributions(own), createdAt = row.createdAt, now = now),
                canEdit = mine && live,
                canWithdraw = mine && live,
                canBoost = live,
                canReply = live && !mine && row.authorId !in blocked,
                canModerate = moderator && live,
                categoryLocked = own.any { it.memberId != row.authorId },
                removalReason = row.removalReason.takeIf { mine || moderator },
                removalNote = row.removalNote.takeIf { mine || moderator },
                image =
                    image?.let {
                        NoticeImageRefDto(
                            imageId = it.id.toString(),
                            url = GalleryUrls.imageUrl(imageId = it.id, storageKey = it.storageKey),
                            altText = it.altText,
                        )
                    },
            )
        }
    }

    /** `ACTIVE` with a passed `expires_at` is `EXPIRED` for every reader, whether or not the poller has run. */
    fun effectiveStatus(
        row: NoticeRow,
        now: LocalDateTime,
    ): NoticeStatus = if (row.status == NoticeStatus.ACTIVE && row.expiresAt <= now) NoticeStatus.EXPIRED else row.status

    private fun daysLeft(
        today: LocalDate,
        expiresOn: LocalDate,
    ): Int = today.daysUntil(expiresOn).coerceAtLeast(0)

    private fun ResultRow.toNoticeRow() =
        NoticeRow(
            id = this[BulletinNoticeTable.id],
            authorId = this[BulletinNoticeTable.authorMemberId],
            authorName = this[MemberTable.displayName],
            authorEligible = this[MemberTable.status] in MemberStatusSets.NOTICE_BOARD && this[MemberTable.anonymizedAt] == null,
            category = this[BulletinNoticeTable.category],
            title = this[BulletinNoticeTable.title],
            body = this[BulletinNoticeTable.body],
            createdAt = this[BulletinNoticeTable.createdAt],
            updatedAt = this[BulletinNoticeTable.updatedAt],
            expiresAt = this[BulletinNoticeTable.expiresAt],
            pinnedAt = this[BulletinNoticeTable.pinnedAt],
            status = this[BulletinNoticeTable.status],
            removalReason = this[BulletinNoticeTable.removalReasonCode],
            removalNote = this[BulletinNoticeTable.removalNote],
        )
}
