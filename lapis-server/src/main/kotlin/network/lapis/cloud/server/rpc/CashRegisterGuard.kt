package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * GoBD "Kassenbestand darf nie negativ werden" + "kein Buchen ohne Beleg" cash-register guard
 * logic, extracted from [AccountingService] -- Security Round 1 (2026-08-19, MAJOR-1). Before this
 * extraction, [AccountingService.postJournalEntry]/[AccountingService.postDraftEntry] were the ONLY
 * enforcement point for these two GoBD invariants; [ContributionPostingBridge] bypassed
 * [AccountingService] entirely (see that object's own KDoc "Bewusst KEIN Aufruf von
 * [AccountingService.postJournalEntry]") and therefore silently bypassed these guards too -- an
 * ADMIN could map a cash-register [LedgerAccountTable] row as `paymentBankAccountId`/
 * `paymentFeeAccountId`/`contributionIncomeAccountId` and every `markContributionPaid` would then
 * drive that till negative with no application-level check and no DB-level backup (the "sum of
 * postings >= 0" invariant cannot be expressed as a single-row `CHECK`/`UNIQUE` constraint).
 *
 * Same "pure/DB logic extracted to a sibling file, reused rather than duplicated" idiom
 * [JournalEntryBalance] already established for the Sigma-debit = Sigma-credit invariant --
 * [AccountingService] itself now delegates to this object instead of carrying its own copy, so
 * there is exactly one implementation of "is this posting set allowed against these cash-register
 * accounts" in the whole codebase.
 *
 * See [AccountingService.postJournalEntry]'s guard preamble for the call order this must always be
 * used in: [loadCashRegisterAccountIds] first, then [requireVoucherForCashPostings], then
 * [requireNonNegativeCashBalances] (which itself takes the row lock via [lockCashRegisterAccounts]
 * before reading any balance) -- all three BEFORE the caller's own `JournalEntryTable`/
 * `PostingTable` inserts, and (per [network.lapis.cloud.server.audit.AuditLogRecorder]'s
 * deadlock-avoidance contract) strictly before that recorder's own chain-state row lock.
 */
internal object CashRegisterGuard {
    /** The subset of [ledgerAccountIds] whose [LedgerAccountTable.isCashRegister] is `true`. */
    fun loadCashRegisterAccountIds(ledgerAccountIds: Collection<Uuid>): Set<Uuid> {
        val distinctIds = ledgerAccountIds.distinct()
        if (distinctIds.isEmpty()) return emptySet()
        return LedgerAccountTable
            .selectAll()
            .where { (LedgerAccountTable.id inList distinctIds) and (LedgerAccountTable.isCashRegister eq true) }
            .map { it[LedgerAccountTable.id] }
            .toSet()
    }

    /**
     * GoBD "kein Buchen ohne Beleg": rejects with [ConflictException] if [cashAccountIds] is
     * non-empty (i.e. the entry references at least one cash-register [LedgerAccountTable] row) and
     * [voucherReference] is null/blank. [cashAccountIds] is computed once by the caller (shared with
     * [requireNonNegativeCashBalances]) via [loadCashRegisterAccountIds].
     */
    fun requireVoucherForCashPostings(
        voucherReference: String?,
        cashAccountIds: Set<Uuid>,
    ) {
        if (!voucherReference.isNullOrBlank()) return
        if (cashAccountIds.isNotEmpty()) {
            throw ConflictException(
                "Postings against a cash-register LedgerAccount require a non-blank voucherReference (kein Buchen ohne Beleg)",
            )
        }
    }

    /**
     * GoBD "Kassenbestand darf nie negativ werden": for every cash-register [LedgerAccountTable] row
     * in [cashAccountIds] ([postings] references, computed once by the caller and shared with
     * [requireVoucherForCashPostings]), aggregates this entry's own net signed delta for that account
     * (grouped once per account, not checked line-by-line -- a single entry may legitimately carry
     * offsetting lines against the same cash account) and rejects with [ConflictException] if that
     * account's pre-existing cumulative `POSTED` balance ([currentPostedBalance]) plus this delta
     * would be strictly negative. Draining a cash account to exactly `0` is allowed -- only a
     * strictly negative projected balance is rejected.
     *
     * Concurrency: this is a check-then-act (read balance, decide, then the caller's transaction
     * inserts the new postings) with no DB-level CHECK/UNIQUE constraint able to back it up.
     * [lockCashRegisterAccounts] takes a row-level lock (`SELECT ... FOR UPDATE`) on every account in
     * [cashAccountIds] before the balance read, so two concurrent transactions touching the same cash
     * account are serialized: the second transaction's [currentPostedBalance] read blocks until the
     * first commits (or rolls back) and therefore always sees the first transaction's postings,
     * preventing both from independently computing a non-negative projected balance that is jointly
     * negative. This serialization applies uniformly regardless of which caller
     * ([AccountingService] or [ContributionPostingBridge]) is racing which -- the lock is on the
     * [LedgerAccountTable] row itself, not on any per-caller state.
     */
    fun requireNonNegativeCashBalances(
        postings: List<PostingInput>,
        cashAccountIds: Set<Uuid>,
    ) {
        if (cashAccountIds.isEmpty()) return
        lockCashRegisterAccounts(cashAccountIds)
        val postingsByAccount = postings.groupBy { Uuid.parse(it.ledgerAccountId) }
        // isCashRegister implies ASSET (AccountingService.requireCashRegisterOnlyOnAsset), so the
        // normal-balance side is always DEBIT -- call normalBalanceSideOf rather than hardcoding
        // PostingSide.DEBIT, to avoid a second, drifting sign-convention source of truth.
        val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.ASSET)
        cashAccountIds.forEach { accountId ->
            val entryDelta =
                postingsByAccount.getValue(accountId).fold(BigDecimal.ZERO) { acc, posting ->
                    val signed = if (posting.side == normalSide) posting.amount else posting.amount.negate()
                    acc + signed
                }
            val projectedBalance = currentPostedBalance(accountId = accountId, normalSide = normalSide) + entryDelta
            if (projectedBalance.signum() < 0) {
                throw ConflictException(
                    "Posting would drive cash-register LedgerAccount $accountId negative (projected balance $projectedBalance)",
                )
            }
        }
    }

    /**
     * Takes a `SELECT ... FOR UPDATE` row lock on every [LedgerAccountTable] row in [accountIds],
     * held for the remainder of the caller's transaction -- the mutex [requireNonNegativeCashBalances]
     * relies on to serialize concurrent postings against the same cash-register account. See that
     * function's KDoc for the race it closes.
     */
    private fun lockCashRegisterAccounts(accountIds: Set<Uuid>) {
        // orderBy(id) gives every concurrent transaction the same lock-acquisition order for a
        // multi-cash-account entry -- without it, two transactions locking the same account set in
        // different physical scan order could each hold one lock and wait on the other, deadlocking.
        LedgerAccountTable
            .selectAll()
            .where { LedgerAccountTable.id inList accountIds }
            .orderBy(LedgerAccountTable.id)
            .forUpdate()
            .toList()
    }

    /** Cumulative net balance of every existing `POSTED` posting against [accountId], signed by [normalSide]. */
    private fun currentPostedBalance(
        accountId: Uuid,
        normalSide: PostingSide,
    ): BigDecimal {
        val rows =
            (PostingTable innerJoin JournalEntryTable)
                .selectAll()
                .where { (PostingTable.ledgerAccountId eq accountId) and (JournalEntryTable.status eq JournalEntryStatus.POSTED) }
                .toList()
        return rows.fold(BigDecimal.ZERO) { acc, row ->
            val amount = row[PostingTable.amount]
            val signed = if (row[PostingTable.side] == normalSide) amount else amount.negate()
            acc + signed
        }
    }
}
