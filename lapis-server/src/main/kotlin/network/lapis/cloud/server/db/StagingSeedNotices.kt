package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.bulletin.NoticeValidation
import network.lapis.cloud.server.db.StagingSeedLedger.covers
import network.lapis.cloud.server.db.StagingSeedLedger.insertRows
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Sample data for the bulletin board (V1.9.297): three fictitious notices -- a search (no stake, sorts last), an offer with one boost of another
 * member, and a pinned information notice of the board. Written like `NoticeBoardService.createNotice` / `boostNotice` / `pinNotice` leave it: the
 * notice row, the boost row plus its `NOTICE_BOOST` ledger row (note without any text of the notice). No audit entry (the live service audits; the seed
 * writes the result, like the other parts).
 *
 * A notice is written as a whole: its id is missing, its author is an ACTIVE organization member ([MemberStatusSets.NOTICE_BOARD]), and the free
 * balance of the booster covers the boost; otherwise that notice is skipped alone. Every expiry lies 20-30 days ahead. **After the expiry plus 30 days
 * the retention poller deletes the notice; the next top-up then writes it again, because its fixed id is missing -- that is intended.**
 */
internal object StagingSeedNotices : StagingSeedPart {
    override val name: String = "notices"

    private const val BOARD = 2

    data class PlannedNotice(
        val index: Int,
        val author: Int,
        val category: NoticeCategory,
        val title: String,
        val body: String,
        val createdDaysAgo: Int,
        val expiresInDays: Int,
        val pinned: Boolean,
    ) {
        val id: Uuid get() = SeedIds.notices(index)
    }

    data class PlannedBoost(
        val index: Int,
        val notice: Int,
        val member: Int,
        val amount: BigDecimal,
        val daysAgo: Int,
        val hour: Int,
    ) {
        val id: Uuid get() = SeedIds.notices(0x2000 + index)
    }

    /** Append-only: the index is the id. */
    val notices: List<PlannedNotice> =
        listOf(
            PlannedNotice(
                index = 1,
                author = 4,
                category = NoticeCategory.SEARCH,
                title = "Suche Mitfahrgelegenheit zum Landesparteitag (Demodaten)",
                body =
                    "Ich suche eine Mitfahrgelegenheit zum Landesparteitag und teile gern die Kosten. " +
                        "Alle Angaben sind erfundene Demodaten.",
                createdDaysAgo = 4,
                expiresInDays = 26,
                pinned = false,
            ),
            PlannedNotice(
                index = 2,
                author = 5,
                category = NoticeCategory.OFFER,
                title = "Biete Umzugskartons (Demodaten)",
                body = "Etwa zwanzig stabile Umzugskartons abzugeben, Abholung nach Absprache. Alle Angaben sind erfundene Demodaten.",
                createdDaysAgo = 6,
                expiresInDays = 24,
                pinned = false,
            ),
            PlannedNotice(
                index = 3,
                author = BOARD,
                category = NoticeCategory.INFO,
                title = "Neue Öffnungszeiten der Geschäftsstelle (Demodaten)",
                body = "Die Geschäftsstelle ist ab sofort dienstags und donnerstags von 10 bis 16 Uhr besetzt. Erfundene Demodaten.",
                createdDaysAgo = 2,
                expiresInDays = 29,
                pinned = true,
            ),
        )

    val boosts: List<PlannedBoost> =
        listOf(
            PlannedBoost(index = 1, notice = 2, member = 6, amount = BigDecimal("2.00"), daysAgo = 5, hour = 11),
        )

    fun createdStamp(
        notice: PlannedNotice,
        clock: SeedClock,
    ): LocalDateTime = clock.utcAt(daysAgo = notice.createdDaysAgo, hour = 10)

    fun boostStamp(
        boost: PlannedBoost,
        clock: SeedClock,
    ): LocalDateTime = clock.utcAt(daysAgo = boost.daysAgo, hour = boost.hour)

    private fun boostRow(
        boost: PlannedBoost,
        clock: SeedClock,
    ) = SeedLedgerRow(
        id = SeedIds.notices(0x1000 + boost.index),
        member = boost.member,
        type = LtrLedgerEntryType.NOTICE_BOOST,
        amount = boost.amount.negate(),
        referenceType = LtrLedgerReferenceType.NOTICE,
        referenceId = SeedIds.notices(boost.notice),
        // like the service: only the reference, no text of the notice
        note = StagingSeedLedger.note("Boost fuer Aushang ${SeedIds.notices(boost.notice)}"),
        at = boostStamp(boost = boost, clock = clock),
    )

    /** All ledger rows the plan writes, for the balance simulation of the plan test. */
    fun ledgerPlan(clock: SeedClock): List<SeedLedgerRow> = boosts.map { boostRow(boost = it, clock = clock) }

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val statuses = statusesOf(notices.map { it.author } + boosts.map { it.member })
        var inserted = 0
        var skipped = 0
        notices.forEach { notice ->
            val ok =
                BulletinNoticeTable.selectAll().where { BulletinNoticeTable.id eq notice.id }.empty() &&
                    statuses[notice.author] in MemberStatusSets.NOTICE_BOARD
            if (!ok) {
                skipped++
                return@forEach
            }
            val created = createdStamp(notice = notice, clock = clock)
            BulletinNoticeTable.insert {
                it[id] = notice.id
                it[authorMemberId] = SeedIds.member(notice.author)
                it[category] = notice.category
                it[title] = notice.title
                it[body] = notice.body
                it[createdAt] = created
                it[updatedAt] = null
                it[expiresAt] =
                    NoticeValidation.expiresAtFor(expiresOn = clock.dayAgo(-notice.expiresInDays), orgZone = clock.orgZone)
                it[pinnedAt] = if (notice.pinned) clock.utcAt(daysAgo = notice.createdDaysAgo - 1, hour = 11) else null
                it[pinnedBy] = if (notice.pinned) SeedIds.member(BOARD) else null
                it[status] = NoticeStatus.ACTIVE
            }
            inserted++
        }
        boosts.forEach { boost ->
            val target = BulletinNoticeTable.selectAll().where { BulletinNoticeTable.id eq SeedIds.notices(boost.notice) }.singleOrNull()
            val row = boostRow(boost = boost, clock = clock)
            val ok =
                BulletinNoticeBoostTable.selectAll().where { BulletinNoticeBoostTable.id eq boost.id }.empty() &&
                    target != null &&
                    target[BulletinNoticeTable.status] == NoticeStatus.ACTIVE &&
                    statuses[boost.member] in MemberStatusSets.NOTICE_BOARD &&
                    covers(listOf(row))
            if (!ok) {
                skipped++
                return@forEach
            }
            BulletinNoticeBoostTable.insert {
                it[id] = boost.id
                it[noticeId] = SeedIds.notices(boost.notice)
                it[memberId] = SeedIds.member(boost.member)
                it[amountLtr] = boost.amount
                it[boostedAt] = boostStamp(boost = boost, clock = clock)
            }
            insertRows(listOf(row))
            inserted++
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
