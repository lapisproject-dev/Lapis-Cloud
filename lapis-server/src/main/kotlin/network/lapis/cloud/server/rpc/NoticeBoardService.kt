package network.lapis.cloud.server.rpc

import dev.kilua.rpc.types.Decimal
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.bulletin.NoticeBoardAction
import network.lapis.cloud.server.bulletin.NoticeBoardDeps
import network.lapis.cloud.server.bulletin.NoticeBoardReader
import network.lapis.cloud.server.bulletin.NoticeRanking
import network.lapis.cloud.server.bulletin.NoticeRow
import network.lapis.cloud.server.bulletin.NoticeValidation
import network.lapis.cloud.server.bulletin.NoticeWeight
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.NoticeAuditSnapshot
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticeInput
import network.lapis.cloud.shared.domain.NoticeListQuery
import network.lapis.cloud.shared.domain.NoticePageDto
import network.lapis.cloud.shared.domain.NoticeRemovalInput
import network.lapis.cloud.shared.domain.NoticeRules
import network.lapis.cloud.shared.domain.NoticeStatus
import network.lapis.cloud.shared.domain.NoticeUpdateInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageRateLimitedException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.INoticeBoardService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.297 "Schwarzes Brett" -- the members' bulletin board (see `docs/features/bulletin-board.adoc`, `docs/architecture/bulletin-board.adoc`).
 *
 * **Access.** EVERY method starts with the [MemberStatusSets.NOTICE_BOARD] gate (ACTIVE only; narrower than the carpool centre, which lets GUEST
 * and FRIEND read). Pin, unpin and remove additionally need the role BOARD or ADMIN. An unknown, malformed, expired, withdrawn, removed or otherwise
 * invisible id is ONE answer, `NotFoundException("Notice not found")`, without echoing the id (no existence oracle). Visibility lives in
 * [NoticeBoardReader.visibleTo].
 *
 * **LTR.** The order depends on the LTR members spend ([boostNotice], or an initial stake at [createNotice]): the weight is `round2(sum of
 * boost * 0.9^days)` with every boost decaying from its OWN time, computed at read time by [NoticeWeight] (= `SocialPostWeight`), never stored. A
 * notice without a stake has weight 0 and sorts last. The ledger entry (`NOTICE_BOOST`, reference `NOTICE`, the notice id) is written in the SAME
 * transaction as the boost row, after `lockForDebit`; no refund. Spending on one's own notice is allowed (open question in the docs).
 *
 * **Locks.** Notice row (`FOR UPDATE`) -> member row (`lockForDebit` / `forMemberUpdate`) -> audit entry (last). [createNotice] locks the member
 * first (there is no notice row yet). [pinNotice] takes the organization-settings row first as the mutex of the pin cap. Rate limits are charged
 * BEFORE the transaction, never inside it. The returned DTO is loaded AFTER the commit (no aggregation under held locks).
 */
class NoticeBoardService internal constructor(
    private val call: ApplicationCall,
    private val deps: NoticeBoardDeps,
) : INoticeBoardService {
    constructor(call: ApplicationCall) : this(call = call, deps = NoticeBoardDeps())

    override suspend fun listNotices(query: NoticeListQuery): NoticePageDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.READ)
        val limit = query.limit.coerceIn(1, NoticeRules.PAGE_MAX)
        val offset = query.offset.coerceAtLeast(0)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            gate(current = current)
            val eligibleStatuses = MemberStatusSets.NOTICE_BOARD.toList()
            val liveAuthors =
                BulletinNoticeTable
                    .join(MemberTable, JoinType.INNER, BulletinNoticeTable.authorMemberId, MemberTable.id)
            val pinnedIds =
                liveAuthors
                    .select(BulletinNoticeTable.id)
                    .where {
                        (BulletinNoticeTable.status eq NoticeStatus.ACTIVE) and (BulletinNoticeTable.expiresAt greater now) and
                            BulletinNoticeTable.pinnedAt.isNotNull() and (MemberTable.status inList eligibleStatuses) and
                            MemberTable.anonymizedAt.isNull()
                    }.orderBy(BulletinNoticeTable.pinnedAt to SortOrder.DESC, BulletinNoticeTable.id to SortOrder.ASC)
                    .limit(NoticeRules.MAX_PINNED * 2)
                    .map { it[BulletinNoticeTable.id] }
            val candidates =
                liveAuthors
                    .select(BulletinNoticeTable.id, BulletinNoticeTable.createdAt, BulletinNoticeTable.pinnedAt)
                    .where {
                        (BulletinNoticeTable.status eq NoticeStatus.ACTIVE) and (BulletinNoticeTable.expiresAt greater now) and
                            BulletinNoticeTable.pinnedAt.isNull() and (MemberTable.status inList eligibleStatuses) and
                            MemberTable.anonymizedAt.isNull() and
                            (query.category?.let { BulletinNoticeTable.category eq it } ?: Op.TRUE)
                    }.orderBy(BulletinNoticeTable.createdAt to SortOrder.DESC, BulletinNoticeTable.id to SortOrder.ASC)
                    .limit(NoticeBoardReader.MAX_CANDIDATES)
                    .map {
                        NoticeRanking.RankRow(
                            id = it[BulletinNoticeTable.id],
                            createdAt = it[BulletinNoticeTable.createdAt],
                            pinnedAt = it[BulletinNoticeTable.pinnedAt],
                        )
                    }
            val boosts =
                NoticeBoardReader
                    .boostsOf(candidates.map { it.id })
                    .mapValues { (_, rows) -> NoticeBoardReader.contributions(rows) }
            val ranked = NoticeRanking.rank(rows = candidates, boosts = boosts, now = now)
            val pageIds = ranked.drop(offset).take(limit).map { it.id }
            val rowsById = NoticeBoardReader.rowsOf(pinnedIds + pageIds).associateBy { it.id }
            val dtos = NoticeBoardReader.toDtos(rows = (pinnedIds + pageIds).mapNotNull { rowsById[it] }, viewer = current, now = now)
            val dtoById = dtos.associateBy { it.id }
            NoticePageDto(
                pinned = pinnedIds.mapNotNull { dtoById[it.toString()] },
                items = pageIds.mapNotNull { dtoById[it.toString()] },
                total = ranked.size,
                offset = offset,
                myActiveCount = activeCountOf(memberId = current.memberId, now = now),
                defaultExpiresOn = NoticeValidation.defaultExpiresOn(now),
                minExpiresOn = NoticeValidation.minExpiresOn(now),
                maxExpiresOn = NoticeValidation.maxExpiresOn(now),
            )
        }
    }

    override suspend fun listMyNotices(): List<NoticeDto> {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.READ)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            gate(current = current)
            val ids =
                BulletinNoticeTable
                    .select(BulletinNoticeTable.id)
                    .where { BulletinNoticeTable.authorMemberId eq current.memberId }
                    .orderBy(BulletinNoticeTable.createdAt to SortOrder.DESC, BulletinNoticeTable.id to SortOrder.ASC)
                    .limit(MAX_OWN_LISTED)
                    .map { it[BulletinNoticeTable.id] }
            NoticeBoardReader.toDtos(rows = NoticeBoardReader.rowsOf(ids), viewer = current, now = now)
        }
    }

    override suspend fun getNotice(id: String): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.READ)
        val noticeId = parseId(id)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            gate(current = current)
            dtoOrNotFound(noticeId = noticeId, viewer = current, now = now, enforceVisibility = true)
        }
    }

    override suspend fun createNotice(input: NoticeInput): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.WRITE)
        val title = NoticeValidation.title(input.title)
        val body = NoticeValidation.body(input.body)
        val stake = input.initialBoostLtr?.let { NoticeWeight.normalizeAmount(amount = it, field = "initialBoostLtr") }
        val now = DbClock.nowLocalDateTime()
        val expiresAt = NoticeValidation.checkedExpiresAt(expiresOn = input.expiresOn, createdAt = now, now = now)
        val noticeId = Uuid.random()
        transaction {
            // The member row is the per-member mutex (it serialises the cap and the free balance); there is no notice row to lock yet.
            gate(current = current, forUpdate = true)
            if (stake != null) requireLtrEligibleMembership(memberId = current.memberId)
            if (activeCountOf(memberId = current.memberId, now = now) >= NoticeRules.MAX_ACTIVE_PER_MEMBER) {
                throw ConflictException("Sie haben bereits ${NoticeRules.MAX_ACTIVE_PER_MEMBER} aktive Aushänge.")
            }
            val windowStart = (now.toInstant(ServerClock.zone) - SocialPostWeight.BOOST_DUPLICATE_WINDOW).toLocalDateTime(ServerClock.zone)
            val duplicate =
                BulletinNoticeTable
                    .selectAll()
                    .where {
                        (BulletinNoticeTable.authorMemberId eq current.memberId) and (BulletinNoticeTable.title eq title) and
                            (BulletinNoticeTable.createdAt greaterEq windowStart)
                    }.limit(1)
                    .any()
            if (duplicate) throw ConflictException("Dieser Aushang wurde soeben schon aufgegeben -- bitte kurz warten.")
            BulletinNoticeTable.insert {
                it[id] = noticeId
                it[authorMemberId] = current.memberId
                it[category] = input.category
                it[BulletinNoticeTable.title] = title
                it[BulletinNoticeTable.body] = body
                it[createdAt] = now
                it[updatedAt] = null
                it[BulletinNoticeTable.expiresAt] = expiresAt
                it[status] = NoticeStatus.ACTIVE
            }
            if (stake != null) bookBoost(current = current, noticeId = noticeId, amount = stake, now = now)
            audit(
                current = current,
                noticeId = noticeId,
                action = AuditAction.CREATE,
                snapshot = NoticeAuditSnapshot(op = "CREATE", amountLtr = stake),
            )
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    override suspend fun updateNotice(
        id: String,
        input: NoticeUpdateInput,
    ): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.WRITE)
        val noticeId = parseId(id)
        val title = NoticeValidation.title(input.title)
        val body = NoticeValidation.body(input.body)
        val now = DbClock.nowLocalDateTime()
        transaction {
            gate(current = current)
            val stored = lockNotice(noticeId)
            val row = ownLiveNoticeOrThrow(stored = stored, viewer = current, now = now)
            val expiresAt =
                NoticeValidation.checkedExpiresAt(
                    expiresOn = input.expiresOn,
                    createdAt = row.createdAt,
                    now = now,
                    currentExpiresAt = row.expiresAt,
                )
            if (input.category != row.category) {
                val foreignBoost =
                    BulletinNoticeBoostTable
                        .selectAll()
                        .where {
                            (BulletinNoticeBoostTable.noticeId eq noticeId) and
                                (BulletinNoticeBoostTable.memberId neq current.memberId)
                        }.limit(1)
                        .any()
                if (foreignBoost) {
                    throw ConflictException(
                        "Die Kategorie kann nicht mehr geändert werden, weil andere LTR eingesetzt haben.",
                    )
                }
            }
            BulletinNoticeTable.update({ BulletinNoticeTable.id eq noticeId }) {
                it[category] = input.category
                it[BulletinNoticeTable.title] = title
                it[BulletinNoticeTable.body] = body
                it[BulletinNoticeTable.expiresAt] = expiresAt
                it[updatedAt] = now
            }
            audit(current = current, noticeId = noticeId, action = AuditAction.UPDATE, snapshot = NoticeAuditSnapshot(op = "EDIT"))
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    override suspend fun withdrawNotice(id: String): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.WRITE)
        val noticeId = parseId(id)
        val now = DbClock.nowLocalDateTime()
        transaction {
            gate(current = current)
            ownLiveNoticeOrThrow(stored = lockNotice(noticeId), viewer = current, now = now)
            BulletinNoticeTable.update({ BulletinNoticeTable.id eq noticeId }) {
                it[status] = NoticeStatus.WITHDRAWN
                it[withdrawnAt] = now
                it[pinnedAt] = null
                it[pinnedBy] = null
            }
            audit(current = current, noticeId = noticeId, action = AuditAction.UPDATE, snapshot = NoticeAuditSnapshot(op = "WITHDRAW"))
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    override suspend fun boostNotice(
        id: String,
        amountLtr: Decimal,
    ): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.BOOST)
        val noticeId = parseId(id)
        val amount = NoticeWeight.normalizeAmount(amount = amountLtr, field = "amountLtr")
        val now = DbClock.nowLocalDateTime()
        transaction {
            gate(current = current)
            requireLtrEligibleMembership(memberId = current.memberId)
            val row = lockNotice(noticeId)
            if (row == null || !NoticeBoardReader.isLive(row = row, now = now) || !row.authorEligible) throw notFound()
            // Double-click window: the same member, the same amount, the same notice within five seconds is a double submit.
            val windowStart = (now.toInstant(ServerClock.zone) - SocialPostWeight.BOOST_DUPLICATE_WINDOW).toLocalDateTime(ServerClock.zone)
            val duplicate =
                BulletinNoticeBoostTable
                    .selectAll()
                    .where {
                        (BulletinNoticeBoostTable.noticeId eq noticeId) and (BulletinNoticeBoostTable.memberId eq current.memberId) and
                            (BulletinNoticeBoostTable.amountLtr eq amount) and (BulletinNoticeBoostTable.boostedAt greaterEq windowStart)
                    }.limit(1)
                    .any()
            if (duplicate) throw ConflictException("Identischer Boost wurde soeben schon gebucht -- bitte kurz warten.")
            bookBoost(current = current, noticeId = noticeId, amount = amount, now = now)
            audit(
                current = current,
                noticeId = noticeId,
                action = AuditAction.UPDATE,
                snapshot = NoticeAuditSnapshot(op = "BOOST", amountLtr = amount),
            )
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    override suspend fun replyToNotice(
        id: String,
        message: String,
    ): DirectMessageDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.REPLY)
        // The direct-message budget is shared with every other message; charged here, outside the transaction.
        if (!deps.directMessageRateLimiter.checkAndRecordSend(memberId = current.memberId, recipients = 1)) {
            throw DirectMessageRateLimitedException()
        }
        val noticeId = parseId(id)
        val text = NoticeValidation.replyText(message)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            gate(current = current)
            val row = NoticeBoardReader.rowOf(noticeId)
            if (row == null || !NoticeBoardReader.isLive(row = row, now = now) || !row.authorEligible) throw notFound()
            if (row.authorId == current.memberId) throw ConflictException("Sie können nicht auf Ihren eigenen Aushang antworten.")
            // The title comes from the database, never from the client. The recipient rule (blocks either way, member finder block, not a
            // member any more, anonymized) is the one of every direct message.
            insertDirectMessage(
                senderId = current.memberId,
                recipientId = row.authorId,
                body = "${NoticeRules.REPLY_PREFIX}${row.title}\n\n$text",
            )
        }
    }

    override suspend fun pinNotice(id: String): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.MODERATE)
        val noticeId = parseId(id)
        val now = DbClock.nowLocalDateTime()
        transaction {
            gate(current = current)
            requireModerator(current)
            // The settings row is the mutex of the pin cap (two concurrent pins would both see "4 pinned").
            lockPinMutex()
            val row = lockNotice(noticeId)
            if (row == null || !NoticeBoardReader.isLive(row = row, now = now) || !row.authorEligible) throw notFound()
            if (row.pinnedAt == null) {
                // Same visibility rules as the pinned list in listNotices: a pin whose author is no longer
                // eligible is invisible on the board and must not block a slot.
                val pinned =
                    BulletinNoticeTable
                        .join(MemberTable, JoinType.INNER, BulletinNoticeTable.authorMemberId, MemberTable.id)
                        .selectAll()
                        .where {
                            (BulletinNoticeTable.status eq NoticeStatus.ACTIVE) and (BulletinNoticeTable.expiresAt greater now) and
                                BulletinNoticeTable.pinnedAt.isNotNull() and
                                (MemberTable.status inList MemberStatusSets.NOTICE_BOARD.toList()) and
                                MemberTable.anonymizedAt.isNull()
                        }.count()
                if (pinned >=
                    NoticeRules.MAX_PINNED
                ) {
                    throw ConflictException("Es können höchstens ${NoticeRules.MAX_PINNED} Aushänge gepinnt sein.")
                }
                BulletinNoticeTable.update({ BulletinNoticeTable.id eq noticeId }) {
                    it[pinnedAt] = now
                    it[pinnedBy] = current.memberId
                }
            }
            audit(current = current, noticeId = noticeId, action = AuditAction.UPDATE, snapshot = NoticeAuditSnapshot(op = "PIN"))
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    override suspend fun unpinNotice(id: String): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.MODERATE)
        val noticeId = parseId(id)
        val now = DbClock.nowLocalDateTime()
        transaction {
            gate(current = current)
            requireModerator(current)
            val row = lockNotice(noticeId)
            if (row == null || !NoticeBoardReader.isLive(row = row, now = now)) throw notFound()
            BulletinNoticeTable.update({ BulletinNoticeTable.id eq noticeId }) {
                it[pinnedAt] = null
                it[pinnedBy] = null
            }
            audit(current = current, noticeId = noticeId, action = AuditAction.UPDATE, snapshot = NoticeAuditSnapshot(op = "UNPIN"))
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    override suspend fun removeNotice(
        id: String,
        input: NoticeRemovalInput,
    ): NoticeDto {
        val current = resolveCurrentMember(call)
        deps.limiter.checkAndRecord(memberId = current.memberId, action = NoticeBoardAction.MODERATE)
        val noticeId = parseId(id)
        val note = NoticeValidation.removalNote(input.note)
        val now = DbClock.nowLocalDateTime()
        transaction {
            gate(current = current)
            requireModerator(current)
            val row = lockNotice(noticeId)
            if (row == null || !NoticeBoardReader.isLive(row = row, now = now)) throw notFound()
            if (row.authorId == current.memberId) throw ConflictException("Eigene Aushänge ziehen Sie bitte zurück.")
            BulletinNoticeTable.update({ BulletinNoticeTable.id eq noticeId }) {
                it[status] = NoticeStatus.REMOVED
                it[removedAt] = now
                it[removedBy] = current.memberId
                it[removalReasonCode] = input.reason
                it[removalNote] = note
                it[pinnedAt] = null
                it[pinnedBy] = null
            }
            audit(
                current = current,
                noticeId = noticeId,
                action = AuditAction.UPDATE,
                snapshot = NoticeAuditSnapshot(op = "REMOVE", reasonCode = input.reason.name),
            )
        }
        return afterCommit(noticeId = noticeId, viewer = current, now = now)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------

    /** The access rule of the whole service: ACTIVE members only. */
    private fun gate(
        current: CurrentMember,
        forUpdate: Boolean = false,
    ): MemberStatus = requireMembershipStatusIn(memberId = current.memberId, allowed = MemberStatusSets.NOTICE_BOARD, forUpdate = forUpdate)

    private fun requireModerator(current: CurrentMember) {
        if (!NoticeBoardReader.isModerator(current)) throw ForbiddenException()
    }

    private fun notFound() = NotFoundException("Notice not found")

    /** Malformed and unknown are the same answer; the input is never echoed. */
    private fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw notFound() }

    /** Locks the notice row (`FOR UPDATE`) and reads it joined with its author. `null` when it does not exist. */
    private fun lockNotice(noticeId: Uuid): NoticeRow? {
        BulletinNoticeTable
            .selectAll()
            .where { BulletinNoticeTable.id eq noticeId }
            .forUpdate()
            .singleOrNull() ?: return null
        return NoticeBoardReader.rowOf(noticeId)
    }

    /** The author's own notice while it is live; anybody else's, an unknown or a closed one is the uniform not-found. */
    private fun ownLiveNoticeOrThrow(
        stored: NoticeRow?,
        viewer: CurrentMember,
        now: LocalDateTime,
    ): NoticeRow {
        if (stored == null || stored.authorId != viewer.memberId) throw notFound()
        if (!NoticeBoardReader.isLive(row = stored, now = now)) throw notFound()
        return stored
    }

    private fun lockPinMutex() {
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .toList()
    }

    private fun activeCountOf(
        memberId: Uuid,
        now: LocalDateTime,
    ): Int =
        BulletinNoticeTable
            .selectAll()
            .where {
                (BulletinNoticeTable.authorMemberId eq memberId) and (BulletinNoticeTable.status eq NoticeStatus.ACTIVE) and
                    (BulletinNoticeTable.expiresAt greater now)
            }.count()
            .toInt()

    /**
     * The boost row plus the ledger entry, atomically inside the caller's transaction. `lockForDebit` BEFORE `freeBalance`, so two concurrent
     * debits of the same member cannot both pass the balance check. Throws [ConflictException] when the free balance is too small.
     */
    private fun bookBoost(
        current: CurrentMember,
        noticeId: Uuid,
        amount: java.math.BigDecimal,
        now: LocalDateTime,
    ) {
        // The membership was read without a lock at the gate; it is read AGAIN under the member lock, so a member who was withdrawn in between
        // cannot spend (the lock order stays notice -> member, `createNotice` already holds the member row).
        gate(current = current, forUpdate = true)
        deps.ltrBalanceProvider.lockForDebit(current.memberId)
        val freeBalance = deps.ltrBalanceProvider.freeBalance(current.memberId)
        if (amount > freeBalance) throw ConflictException("amountLtr $amount exceeds free LTR balance $freeBalance")
        BulletinNoticeBoostTable.insert {
            it[id] = Uuid.random()
            it[BulletinNoticeBoostTable.noticeId] = noticeId
            it[memberId] = current.memberId
            it[amountLtr] = amount
            it[boostedAt] = now
        }
        LtrLedgerEntryTable.insert {
            it[id] = Uuid.random()
            it[memberId] = current.memberId
            it[entryType] = LtrLedgerEntryType.NOTICE_BOOST
            it[amountLtr] = amount.negate()
            it[referenceType] = LtrLedgerReferenceType.NOTICE
            // The NOTICE id (not the boost id): a deep link shows the notice. No text of the notice in the note.
            it[referenceId] = noticeId
            it[note] = "Boost fuer Aushang $noticeId"
            it[createdBy] = null
            it[createdAt] = now
        }
    }

    /** Ids, enums and an amount only: never a title, a text or a note. LAST operation of a transaction. */
    private fun audit(
        current: CurrentMember,
        noticeId: Uuid,
        action: AuditAction,
        snapshot: NoticeAuditSnapshot,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.BULLETIN_NOTICE,
            entityId = noticeId,
            action = action,
            before = null,
            after = Json.encodeToString(NoticeAuditSnapshot.serializer(), snapshot),
        )
    }

    private fun afterCommit(
        noticeId: Uuid,
        viewer: CurrentMember,
        now: LocalDateTime,
    ): NoticeDto = transaction { dtoOrNotFound(noticeId = noticeId, viewer = viewer, now = now, enforceVisibility = false) }

    private fun dtoOrNotFound(
        noticeId: Uuid,
        viewer: CurrentMember,
        now: LocalDateTime,
        enforceVisibility: Boolean,
    ): NoticeDto {
        val row = NoticeBoardReader.rowOf(noticeId) ?: throw notFound()
        if (enforceVisibility && !NoticeBoardReader.visibleTo(row = row, viewerId = viewer.memberId, now = now)) throw notFound()
        return NoticeBoardReader.toDtos(rows = listOf(row), viewer = viewer, now = now).single()
    }

    private companion object {
        const val MAX_OWN_LISTED = 200
    }
}
