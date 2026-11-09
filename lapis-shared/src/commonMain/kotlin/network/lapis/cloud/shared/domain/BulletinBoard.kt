package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.297 "Schwarzes Brett" -- the kind of a bulletin-board notice. Literal order is load-bearing (`bulletin_notice.category` CHECK, kUML model
 * `81-bulletin-board.kuml.kts`); new literals go to the end.
 */
@Serializable
enum class NoticeCategory { SEARCH, OFFER, INFO, OTHER }

/**
 * Lifecycle of a notice. `EXPIRED` is only cosmetic bookkeeping of the retention poller: every read decides on `expires_at`, never on this
 * value. `WITHDRAWN` = the author took it down, `REMOVED` = the board removed it (with a reason).
 */
@Serializable
enum class NoticeStatus { ACTIVE, EXPIRED, WITHDRAWN, REMOVED }

/** Why the board removed a notice. A fixed list, so that no free-text accusation is stored; an optional short note comes on top. */
@Serializable
enum class NoticeRemovalReason { SPAM, RULES, DUPLICATE, LEGAL }

/** Every limit of the bulletin board. One source for server, client and the database CHECKs (which mirror them). */
object NoticeRules {
    const val TITLE_MIN: Int = 3
    const val TITLE_MAX: Int = 120
    const val BODY_MAX: Int = 2000
    const val REMOVAL_NOTE_MAX: Int = 500
    const val DEFAULT_DAYS: Int = 30
    const val MIN_DAYS: Int = 1
    const val MAX_DAYS: Int = 90

    /** Active (not expired, not withdrawn, not removed) notices one member may have at the same time. */
    const val MAX_ACTIVE_PER_MEMBER: Int = 5

    /** Pinned notices at the same time (protects the top area from being occupied for good). */
    const val MAX_PINNED: Int = 5
    const val PAGE_MAX: Int = 100
    const val PAGE_DEFAULT: Int = 50

    /** The reply text (a direct message body, the server prepends the notice title). */
    const val REPLY_MAX: Int = 2000

    /** A closed (expired, withdrawn, removed) notice is deleted for good this many days after it closed. */
    const val RETENTION_DAYS_AFTER_CLOSE: Int = 30

    /** First line of every reply direct message; the server writes it, always in German (a direct message has no subject field). */
    const val REPLY_PREFIX: String = "Schwarzes Brett: "
}

/**
 * Create a notice. [expiresOn] is the LAST day on which it is shown (organization calendar day, class D). [initialBoostLtr] is an optional LTR
 * stake spent together with the notice (same ledger entry as a later boost); `null` = no stake, the notice then sorts at the bottom.
 */
@Serializable
data class NoticeInput(
    val category: NoticeCategory,
    val title: String,
    val body: String,
    val expiresOn: LocalDate,
    val initialBoostLtr: Decimal? = null,
)

/** Edit a notice of one's own. The category cannot change once somebody else has boosted it. */
@Serializable
data class NoticeUpdateInput(
    val category: NoticeCategory,
    val title: String,
    val body: String,
    val expiresOn: LocalDate,
)

/** Page request of the board. [category] `null` = all. */
@Serializable
data class NoticeListQuery(
    val category: NoticeCategory? = null,
    val offset: Int = 0,
    val limit: Int = NoticeRules.PAGE_DEFAULT,
)

/** The board's removal of somebody else's notice. [reason] is mandatory. */
@Serializable
data class NoticeRemovalInput(
    val reason: NoticeRemovalReason,
    val note: String? = null,
)

/** The picture of a notice. [url] is the same-origin, authenticated delivery URL. */
@Serializable
data class NoticeImageRefDto(
    val imageId: String,
    val url: String,
    val altText: String?,
)

/**
 * One notice as the caller sees it. Deliberately carries NO author member id (the client must never be able to address a reply with an id it
 * read off a DTO: the server resolves the recipient from the notice id). [weightLtr] is the decayed LTR weight at the time of the request, never
 * a stored value. The removal reason and note are filled only for the author and for BOARD/ADMIN.
 *
 * [expiresOn] is the last day it is shown (class D), [daysLeft] the whole days from today to that day (0 = last day), [minExpiresOn] and
 * [maxExpiresOn] the dates the author may pick when editing it.
 */
@Serializable
data class NoticeDto(
    val id: String,
    val category: NoticeCategory,
    val title: String,
    val body: String,
    val authorDisplayName: String,
    val isMine: Boolean,
    val status: NoticeStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val expiresAt: LocalDateTime,
    val expiresOn: LocalDate,
    val daysLeft: Int,
    val minExpiresOn: LocalDate,
    val maxExpiresOn: LocalDate,
    val pinnedAt: LocalDateTime?,
    val weightLtr: Decimal,
    val canEdit: Boolean,
    val canWithdraw: Boolean,
    val canBoost: Boolean,
    val canReply: Boolean,
    val canModerate: Boolean,
    /** `true` once somebody other than the author boosted the notice: the category is then fixed. */
    val categoryLocked: Boolean,
    val removalReason: NoticeRemovalReason? = null,
    val removalNote: String? = null,
    val image: NoticeImageRefDto? = null,
)

/**
 * One page of the board. [pinned] are all pinned notices (at most [NoticeRules.MAX_PINNED], independent of the offset), [items] the rest in LTR
 * order. [myActiveCount] counts the caller's active notices (limit [NoticeRules.MAX_ACTIVE_PER_MEMBER]). The three dates are the picker bounds
 * for a NEW notice.
 */
@Serializable
data class NoticePageDto(
    val pinned: List<NoticeDto>,
    val items: List<NoticeDto>,
    val total: Int,
    val offset: Int,
    val myActiveCount: Int,
    val defaultExpiresOn: LocalDate,
    val minExpiresOn: LocalDate,
    val maxExpiresOn: LocalDate,
)

/**
 * After-snapshot of an [AuditEntityType.BULLETIN_NOTICE] entry. [op] is CREATE, EDIT, WITHDRAW, BOOST, PIN, UNPIN or REMOVE. Never a title, a text,
 * a removal note or a name.
 */
@Serializable
data class NoticeAuditSnapshot(
    val op: String,
    val reasonCode: String? = null,
    val amountLtr: Decimal? = null,
)
