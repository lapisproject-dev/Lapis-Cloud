package network.lapis.cloud.server.economy

import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Seam originally introduced for the full LTR ledger V0.6 (LTR-Wirtschaft/Auktion/Price-Oracle)
 * would bring — [GovernanceService][network.lapis.cloud.server.rpc.GovernanceService] takes this
 * as a constructor parameter (default [LedgerBackedLtrBalanceProvider]; the V0.2.3-era
 * `PlaceholderLtrBalanceProvider` was retired by V0.6.1) so `Application.module`'s
 * `registerService(IGovernanceService::class) { call -> GovernanceService(call) }` stays
 * unchanged even though the LTR ledger backing it is now real. `freeBalance` never writes —
 * every write path (mint, project-stake bind) goes through
 * [network.lapis.cloud.server.rpc.LtrLedgerService]/[network.lapis.cloud.server.rpc.CrowdfundingService]
 * directly against [LtrLedgerEntryTable] instead. Implementations must run inside the caller's
 * already-open `transaction {}`, consistent with the rest of this codebase's "simple-transaction"
 * style.
 */
interface LtrBalanceProvider {
    /** The member's current free LTR balance, or [BigDecimal.ZERO] if the member has none. */
    fun freeBalance(memberId: Uuid): BigDecimal

    /**
     * Batched free-balance read for N members in one query -- introduced for V0.6.4
     * [network.lapis.cloud.server.rpc.PoliticianTrustWeightCalculator], which needs every distinct
     * rater's balance to compute the shared LTR-weight pool. Computing that pool via N sequential
     * [freeBalance] calls is an N+1 query pattern the single-member debit call sites never had to
     * care about (they only ever read one member's balance per RPC call). Default implementation
     * simply folds [freeBalance] over [memberIds] one at a time -- correct, but re-introduces the
     * N+1 pattern this method exists to avoid -- so it stays additive/non-breaking for any other
     * [LtrBalanceProvider] implementer, while [LedgerBackedLtrBalanceProvider] overrides it with a
     * real single `GROUP BY member_id` query. A member absent from [memberIds] is not present in
     * the result at all; a member present but with no ledger rows maps to [BigDecimal.ZERO].
     * Read-only -- does not lock any row, unlike [lockForDebit].
     */
    fun freeBalances(memberIds: Collection<Uuid>): Map<Uuid, BigDecimal> = memberIds.associateWith { freeBalance(it) }

    /**
     * Takes a row-level lock (`SELECT ... FOR UPDATE`) serializing concurrent debit-causing
     * writers against [memberId]'s free balance. `freeBalance` alone is a `SUM(amount_ltr)`
     * aggregate over [LtrLedgerEntryTable] — no single row can carry a DB-level
     * non-negativity CHECK for it, exactly the same aggregate-invariant gap
     * `AccountingService.requireNonNegativeCashBalances`'s KDoc documents for the EUR/cash-register
     * case. Every call site that reads `freeBalance` to decide whether a new debiting ledger row
     * may be inserted (`GovernanceService.castVoteBallot`'s stake,
     * `CrowdfundingService.submitProject`'s initial weight, and any future LTR-debiting write)
     * MUST call this — inside its own already-open transaction, BEFORE reading `freeBalance` —
     * so that a second concurrent transaction against the SAME member blocks until the first
     * commits (or rolls back) and therefore always observes the first transaction's debit,
     * closing the TOCTOU that would otherwise let a member spend more LTR than their free
     * balance across two overlapping calls. Locks the [MemberTable] row for [memberId] as the
     * per-member mutex (there is no dedicated LTR-balance/account row to lock, and every member
     * that can call an LTR-debiting RPC necessarily already has a row here via
     * `resolveCurrentMember`).
     */
    fun lockForDebit(memberId: Uuid)
}

/**
 * V0.6.1 (Internes Crowdfunding): derives a member's free LTR balance as `SUM(amount_ltr)` over
 * every [LtrLedgerEntryTable] row for that member — never a cached/persisted snapshot, same
 * "derive from the ledger" idiom this codebase's EUR-side `GeneralLedgerCalculator` already
 * uses. A member without any ledger rows is treated as having a zero balance, not an error: most
 * members will not have been minted any LTR yet.
 */
class LedgerBackedLtrBalanceProvider : LtrBalanceProvider {
    override fun freeBalance(memberId: Uuid): BigDecimal =
        LtrLedgerEntryTable
            .selectAll()
            .where { LtrLedgerEntryTable.memberId eq memberId }
            .fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[LtrLedgerEntryTable.amountLtr] }

    /**
     * One query for every requested member's ledger rows (`memberId inList memberIds`), summed in
     * Kotlin per member -- NOT one query per member. See [LtrBalanceProvider.freeBalances] KDoc.
     * A member with no ledger rows at all is simply absent from the returned map -- callers that
     * need a zero default for such a member must apply it themselves (mirrors
     * [network.lapis.cloud.server.rpc.CrowdfundingService.reactionCountsByProject]'s own
     * "absent means zero, caller defaults it" convention).
     */
    override fun freeBalances(memberIds: Collection<Uuid>): Map<Uuid, BigDecimal> {
        if (memberIds.isEmpty()) return emptyMap()
        val rows =
            LtrLedgerEntryTable
                .selectAll()
                .where { LtrLedgerEntryTable.memberId inList memberIds }
                .toList()
        return rows
            .groupBy { it[LtrLedgerEntryTable.memberId] }
            .mapValues { (_, entries) ->
                entries.fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[LtrLedgerEntryTable.amountLtr] }
            }
    }

    override fun lockForDebit(memberId: Uuid) {
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forUpdate()
            .singleOrNull()
            ?: error("Member $memberId not found while locking for LTR debit -- caller must resolve/validate the member first")
    }
}
