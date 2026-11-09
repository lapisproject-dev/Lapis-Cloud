package network.lapis.cloud.server.bulletin

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.db.generated.DirectMessageBlockTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.rpc.NoticeBoardService
import network.lapis.cloud.server.rpc.ServiceCaller
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeInput
import network.lapis.cloud.shared.domain.NoticeRemovalReason
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Throwaway members, notices, boosts and LTR for the bulletin-board tests; removes exactly what it created. */
internal class NoticeFixtures {
    val memberIds = mutableListOf<Uuid>()
    val noticeIds = mutableListOf<Uuid>()
    private val grantedTo = mutableListOf<Uuid>()

    fun member(
        label: String = "m",
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
        anonymized: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Aushang-Test $label"
                it[email] = "notice-test-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                if (anonymized) it[anonymizedAt] = LocalDateTime(2026, 2, 1, 0, 0, 0)
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        memberIds += id
        return id
    }

    fun setStatus(
        member: Uuid,
        status: MemberStatus,
    ) {
        transaction { MemberTable.update({ MemberTable.id eq member }) { it[MemberTable.status] = status } }
    }

    fun grant(
        member: Uuid,
        amount: String,
    ) {
        grantedTo += member
        transaction {
            LtrLedgerEntryTable.insert {
                it[id] = Uuid.random()
                it[memberId] = member
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = BigDecimal(amount)
                it[referenceType] = null
                it[referenceId] = null
                it[note] = "notice test"
                it[createdBy] = null
                it[createdAt] = DbClock.nowLocalDateTime()
            }
        }
    }

    fun balance(member: Uuid): BigDecimal = transaction { LedgerBackedLtrBalanceProvider().freeBalance(member) }

    fun ledgerRows(member: Uuid) =
        transaction {
            LtrLedgerEntryTable
                .selectAll()
                .where { LtrLedgerEntryTable.memberId eq member }
                .toList()
        }

    /** A notice row written directly (the service rules are not applied): [createdHoursAgo] and [expiresInDays] are relative to now. */
    fun notice(
        author: Uuid,
        title: String = "Testaushang",
        body: String = "Ein Text.",
        category: NoticeCategory = NoticeCategory.OFFER,
        createdHoursAgo: Int = 1,
        expiresInDays: Int = 10,
        status: NoticeStatus = NoticeStatus.ACTIVE,
        pinned: Uuid? = null,
        removedBy: Uuid? = null,
        withdrawnAt: LocalDateTime? = null,
        removedAt: LocalDateTime? = null,
    ): Uuid {
        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        val created = shift(base = now, hours = -createdHoursAgo)
        transaction {
            BulletinNoticeTable.insert {
                it[BulletinNoticeTable.id] = id
                it[authorMemberId] = author
                it[BulletinNoticeTable.category] = category
                it[BulletinNoticeTable.title] = title
                it[BulletinNoticeTable.body] = body
                it[createdAt] = created
                it[updatedAt] = null
                it[expiresAt] = shift(base = now, days = expiresInDays)
                it[pinnedAt] = if (pinned != null) created else null
                it[pinnedBy] = pinned
                it[BulletinNoticeTable.status] = status
                it[BulletinNoticeTable.withdrawnAt] = if (status == NoticeStatus.WITHDRAWN) withdrawnAt ?: now else null
                if (status == NoticeStatus.REMOVED) {
                    it[BulletinNoticeTable.removedAt] = removedAt ?: now
                    it[BulletinNoticeTable.removedBy] = removedBy
                    it[removalReasonCode] = NoticeRemovalReason.SPAM
                    it[removalNote] = "Notiz"
                }
            }
        }
        noticeIds += id
        return id
    }

    /** A boost row written directly (no ledger row), for ranking scenarios. */
    fun boostRow(
        notice: Uuid,
        member: Uuid,
        amount: String,
        hoursAgo: Int = 0,
    ) {
        transaction {
            BulletinNoticeBoostTable.insert {
                it[id] = Uuid.random()
                it[noticeId] = notice
                it[memberId] = member
                it[amountLtr] = BigDecimal(amount)
                it[boostedAt] = shift(base = DbClock.nowLocalDateTime(), hours = -hoursAgo)
            }
        }
    }

    /** Moves the notice into the past so that its expiry has passed, without changing its status. */
    fun expire(notice: Uuid) {
        val now = DbClock.nowLocalDateTime()
        transaction {
            BulletinNoticeTable.update({ BulletinNoticeTable.id eq notice }) {
                it[createdAt] = shift(base = now, days = -5)
                it[expiresAt] = shift(base = now, hours = -1)
            }
        }
    }

    fun notices(author: Uuid) =
        transaction { BulletinNoticeTable.selectAll().where { BulletinNoticeTable.authorMemberId eq author }.toList() }

    fun audits(notice: Uuid) =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { AuditLogEntryTable.entityId eq notice }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .toList()
        }

    fun messagesTo(member: Uuid) =
        transaction { DirectMessageTable.selectAll().where { DirectMessageTable.recipientId eq member }.toList() }

    fun cleanUp() {
        transaction {
            val all =
                noticeIds.toList() +
                    BulletinNoticeTable
                        .selectAll()
                        .where {
                            BulletinNoticeTable.authorMemberId inList memberIds
                        }.map { it[BulletinNoticeTable.id] }
            val ids = all.distinct()
            AttachedImageTable.deleteWhere { AttachedImageTable.ownerId inList ids }
            BulletinNoticeBoostTable.deleteWhere {
                (BulletinNoticeBoostTable.noticeId inList ids) or
                    (BulletinNoticeBoostTable.memberId inList memberIds)
            }
            BulletinNoticeTable.deleteWhere { BulletinNoticeTable.id inList ids }
            DirectMessageTable.deleteWhere {
                (DirectMessageTable.senderId inList memberIds) or
                    (DirectMessageTable.recipientId inList memberIds)
            }
            DirectMessageBlockTable.deleteWhere {
                (DirectMessageBlockTable.blockerMemberId inList memberIds) or (DirectMessageBlockTable.blockedMemberId inList memberIds)
            }
            LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList (memberIds + grantedTo) }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}

internal fun shift(
    base: LocalDateTime,
    days: Int = 0,
    hours: Int = 0,
): LocalDateTime = (base.toInstant(TimeZone.UTC) + days.days + hours.hours).toLocalDateTime(TimeZone.UTC)

/** An organization calendar day [days] from today. */
internal fun inDays(days: Int): LocalDate = OrganizationTimeZone.today().plus(days, DateTimeUnit.DAY)

internal fun noticeInput(
    title: String = "Suche Fahrrad",
    body: String = "Ich suche ein gebrauchtes Fahrrad.",
    category: NoticeCategory = NoticeCategory.SEARCH,
    expiresInDays: Int = 14,
    stake: String? = null,
) = NoticeInput(
    category = category,
    title = title,
    body = body,
    expiresOn = inDays(expiresInDays),
    initialBoostLtr = stake?.let { BigDecimal(it) },
)

internal suspend fun <T> ServiceCaller.asBoard(
    who: Uuid,
    deps: NoticeBoardDeps = NoticeBoardDeps(),
    action: suspend (NoticeBoardService) -> T,
): Result<T> = call(caller = who) { action(NoticeBoardService(call = it, deps = deps)) }
