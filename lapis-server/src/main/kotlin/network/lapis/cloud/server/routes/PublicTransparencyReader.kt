package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.rpc.GeneralLedgerCalculator
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.times
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One row of the "Vorstand" section -- see [PublicTransparencyReader.loadBoard]. */
internal data class PublicBoardMemberRow(
    val displayName: String,
    val role: CommitteeRole,
    val since: LocalDate,
)

/** One row of an opt-in leaderboard -- amount already formatted (`setScale(2).toPlainString()`), the renderer never computes. */
internal data class PublicRankingRow(
    val displayName: String,
    val amount: String,
)

/**
 * [rows] is empty (never a placeholder row) below [PublicTransparencyRoutes]'s minimum-cohort
 * threshold -- [cohortSize] is what the caller checks to decide whether to render the section AT
 * ALL (including its jump-menu anchor), independent of [rows]' own size (a consenting member with
 * a zero/negative balance still counts towards [cohortSize] but never appears in [rows]).
 */
internal data class PublicRankingSection(
    val rows: List<PublicRankingRow>,
    val cohortSize: Long,
)

internal data class PublicTransparencyStats(
    val activeMemberCount: Long,
    val mintedLtrTotal: String,
    val publicPostCount: Long,
)

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" (`GET /transparenz`) -- all read-only queries the
 * page needs, gathered in ONE object so [PublicTransparencyRoutes] can load everything inside a
 * single short `transaction {}` and render OUTSIDE of it (same discipline `SocialPublicRoutes`'s
 * own class KDoc "Ablauf pro Handler" establishes -- never render while holding a pool
 * connection).
 *
 * **Determinismus**: every ordering here carries an explicit tiebreaker (`member_id`/`display_name`
 * ASC as the final `sortedWith`/`orderBy` key) so two calls with identical underlying data produce
 * byte-identical output -- the whole ETag/304 mechanism `PublicTransparencyRoutes` relies on
 * depends on this, same contract [SocialPublicHtml] KDoc point 4 establishes for `/s`.
 */
internal object PublicTransparencyReader {
    private val boardRolePriority: Map<CommitteeRole, Int> =
        mapOf(
            CommitteeRole.CHAIR to 0,
            CommitteeRole.DEPUTY_CHAIR to 1,
            CommitteeRole.SECRETARY to 2,
            CommitteeRole.ASSESSOR to 3,
            CommitteeRole.MEMBER to 4,
        )

    /**
     * *Insgesamt ausgegebene LTR* sums ONLY [LtrLedgerEntryType.MINT] rows -- deliberately NOT
     * `SUM(amount_ltr)` over every entry type. A full-table sum would be the CURRENT free-balance
     * total (stakes bound into votes/projects/posts/auctions have no release path yet and would
     * silently DEPRESS that number every time a member spends), which is a different, less honest
     * statement than "how much LTR has this organization minted in total". See implementation plan
     * § 3.3 O6.
     */
    fun loadStats(): PublicTransparencyStats {
        val activeMemberCount =
            MemberTable
                .selectAll()
                .where { (MemberTable.status eq MemberStatus.ACTIVE) and MemberTable.anonymizedAt.isNull() }
                .count()
        val mintTotal = LtrLedgerEntryTable.amountLtr.sum()
        val mintedLtrTotal =
            LtrLedgerEntryTable
                .select(mintTotal)
                .where { LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT }
                .singleOrNull()
                ?.get(mintTotal)
                ?.setScale(2)
                ?: BigDecimal.ZERO.setScale(2)
        val publicPostCount = SocialPublicSitemap.countPublicRoots()
        return PublicTransparencyStats(
            activeMemberCount = activeMemberCount,
            mintedLtrTotal = mintedLtrTotal.toPlainString(),
            publicPostCount = publicPostCount,
        )
    }

    /**
     * The current executive board -- `committee`(EXECUTIVE_BOARD, active) ⋈ `committee_membership`
     * (`until IS NULL`) ⋈ `member` (not anonymized). Deliberately reads `committee_membership`
     * directly rather than calling `GovernanceService.listCommitteeMembers` -- that method resolves
     * the CALLER via `resolveCurrentMember`, which has no meaning on an unauthenticated public
     * route; this is its own, narrower read, never an auth-gate weakening. NO opt-in (D6): board
     * members are office-holders whose names are already public record (the party's own
     * Transparenzregister).
     */
    fun loadBoard(): List<PublicBoardMemberRow> {
        val rows =
            (CommitteeMembershipTable innerJoin CommitteeTable innerJoin MemberTable)
                .select(MemberTable.displayName, CommitteeMembershipTable.role, CommitteeMembershipTable.since, MemberTable.id)
                .where {
                    (CommitteeTable.type eq CommitteeType.EXECUTIVE_BOARD) and
                        (CommitteeTable.active eq true) and
                        CommitteeMembershipTable.until.isNull() and
                        MemberTable.anonymizedAt.isNull() and
                        (MemberTable.status eq MemberStatus.ACTIVE)
                }.toList()
        return rows
            .sortedWith(
                compareBy<ResultRow> { boardRolePriority.getValue(it[CommitteeMembershipTable.role]) }
                    .thenBy { it[CommitteeMembershipTable.since] }
                    .thenBy { it[MemberTable.id].toString() },
            ).map { row ->
                PublicBoardMemberRow(
                    displayName = row[MemberTable.displayName],
                    role = row[CommitteeMembershipTable.role],
                    since = row[CommitteeMembershipTable.since],
                )
            }
    }

    /** Top [limit] free-LTR-balance holders among members with an EFFECTIVE opt-in -- see [PublicRankingConsentStore]. */
    fun loadTopLtrHolders(limit: Int): PublicRankingSection {
        val cohortSize = PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.LTR_HOLDINGS)
        val balances =
            LedgerBackedLtrBalanceProvider().topFreeBalances(
                consentCondition = PublicRankingConsentStore.effectiveGrantCondition(PublicRankingKind.LTR_HOLDINGS),
                limit = limit,
            )
        val rows = balances.toRankingRows { it.setScale(2).toPlainString() }
        return PublicRankingSection(rows = rows, cohortSize = cohortSize)
    }

    /**
     * Top [limit] donors of calendar year [year] among members with an EFFECTIVE opt-in. Mirrors
     * `AccountingService.loadDonationYearEntries`'s own `donor_category IS NOT NULL` + `status =
     * POSTED` "this is a real, gebuchte donation" signal (not `payment_transaction.intent =
     * DONATION`, which is an INTENT that can fail/be cancelled/be duplicated -- see plan D7) and its
     * posting-level signed-amount computation via [GeneralLedgerCalculator.normalBalanceSideOf] (the
     * ONE place that sign rule lives, never re-derived here). `donor_member_id IS NOT NULL`
     * structurally excludes every `external_donor_id` donation (D8) -- an external donor has no
     * login, no consent mechanism, and no DSGVO export/erasure coverage yet (see plan § 8 O3).
     *
     * A real `SUM(...) ... GROUP BY donor_member_id` in SQL -- same discipline
     * [network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider.topFreeBalances] KDoc
     * "Stolperfalle 4" establishes for the LTR ranking, and this method's own predecessor violated:
     * loading every matching [JournalEntryTable] row into Kotlin first and then re-querying
     * [PostingTable] via `inList(entryIds)` risks the ~65535 bind-parameter ceiling once a
     * calendar year's consented, member-attributed donation postings exceed it -- and unlike
     * `topFreeBalances`'s call sites, THIS route is unauthenticated and has no per-account budget,
     * so an oversized year would 500 for every visitor, not just one caller. The `CASE WHEN side =
     * normalSide THEN amount ELSE -amount END` signed-sum mirrors
     * [GeneralLedgerCalculator.normalBalanceSideOf]'s sign rule in SQL instead of folding it in
     * Kotlin -- `normalSide` for `INCOME` is a compile-time-fixed [PostingSide] (not itself a
     * per-row value), so it is safe to bake into the `CASE` as a literal comparison.
     */
    fun loadTopDonors(
        year: Int,
        limit: Int,
    ): PublicRankingSection {
        val cohortSize = PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.DONATIONS)
        val yearStart = LocalDate(year, 1, 1)
        val yearEnd = LocalDate(year, 12, 31)
        val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.INCOME)
        val signedAmount =
            Case()
                .When(PostingTable.side eq normalSide, PostingTable.amount)
                .Else(PostingTable.amount times BigDecimal(-1))
        val donorTotal = signedAmount.sum()
        val joined =
            JournalEntryTable
                .join(MemberTable, JoinType.INNER, JournalEntryTable.donorMemberId, MemberTable.id)
                .join(
                    PublicRankingConsentEventTable,
                    JoinType.INNER,
                    JournalEntryTable.donorMemberId,
                    PublicRankingConsentEventTable.memberId,
                ).join(PostingTable, JoinType.INNER, JournalEntryTable.id, PostingTable.journalEntryId)
                .join(LedgerAccountTable, JoinType.INNER, PostingTable.ledgerAccountId, LedgerAccountTable.id)
        val totalsByDonor =
            joined
                .select(JournalEntryTable.donorMemberId, donorTotal)
                .where {
                    (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                        JournalEntryTable.donorCategory.isNotNull() and
                        JournalEntryTable.donorMemberId.isNotNull() and
                        (JournalEntryTable.entryDate greaterEq yearStart) and
                        (JournalEntryTable.entryDate lessEq yearEnd) and
                        MemberTable.anonymizedAt.isNull() and
                        (MemberTable.status eq MemberStatus.ACTIVE) and
                        (LedgerAccountTable.type eq LedgerAccountType.INCOME) and
                        PublicRankingConsentStore.effectiveGrantCondition(PublicRankingKind.DONATIONS)
                }.groupBy(JournalEntryTable.donorMemberId)
                .mapNotNull { row ->
                    val donorId = row[JournalEntryTable.donorMemberId] ?: return@mapNotNull null
                    val sum = row[donorTotal]?.setScale(2) ?: return@mapNotNull null
                    if (sum <= BigDecimal.ZERO) return@mapNotNull null
                    donorId to sum
                }

        val rows = totalsByDonor.toRankingRows(limit = limit) { it.setScale(2).toPlainString() }
        return PublicRankingSection(rows = rows, cohortSize = cohortSize)
    }

    private fun List<Pair<Uuid, BigDecimal>>.toRankingRows(
        limit: Int = size,
        format: (BigDecimal) -> String,
    ): List<PublicRankingRow> {
        val top =
            this
                .sortedWith(compareByDescending<Pair<Uuid, BigDecimal>> { it.second }.thenBy { it.first.toString() })
                .take(limit)
        if (top.isEmpty()) return emptyList()
        val ids = top.map { it.first }
        val displayNameById =
            MemberTable
                .select(MemberTable.id, MemberTable.displayName)
                .where { MemberTable.id inList ids }
                .associate { it[MemberTable.id] to it[MemberTable.displayName] }
        return top.map { (memberId, amount) ->
            PublicRankingRow(displayName = displayNameById[memberId] ?: "", amount = format(amount))
        }
    }
}
