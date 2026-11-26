package network.lapis.cloud.server.tender

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.rpc.CONCURRENT_CHANGE
import network.lapis.cloud.server.rpc.NOT_AVAILABLE
import network.lapis.cloud.server.rpc.lockedMemberStatuses
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.LtrLedgerEntryDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderPayoutFailureReason
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.insert
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.308 -- THE ONLY tender file that may touch the LTR ledger (`TenderPayoutLedgerTripwireTest` and `TenderAccessTripwireTest` pin this).
 * Everything else about a tender stays ledger-free.
 *
 * ## What a payout is
 *
 * An ISSUANCE by the organisation, like a MINT: exactly ONE credit row [LtrLedgerEntryType.TENDER_PAYOUT] on the winner, reference
 * [LtrLedgerReferenceType.TENDER] = the tender id. There is no debit row and no organisation account (the ledger knows member accounts only and a
 * system member is forbidden by doctrine), so "insufficient balance" cannot happen and a partial booking is structurally impossible.
 * The amount is EXACTLY the award price (taken from the AWARDED bid of THIS tender, never from client input).
 *
 * ## Idempotency in three layers (no partial index, so it holds on H2 and PostgreSQL)
 *
 *  a. the tender row is locked `FOR UPDATE` and moves COMPLETED/PAYOUT_FAILED -> PAID by a conditional `UPDATE ... WHERE status IN (...)`;
 *  b. `tender_payout.tender_id` is the primary key (one payout per tender);
 *  c. UNIQUE `ledger_entry_id` and `bid_id`.
 *
 * ## Lock order (same as every other tender path): tender -> bids -> member -> ledger -> audit.
 * The member row is locked only with the `forMemberUpdate` helper ([lockedMemberStatuses]). Must run inside an open `transaction {}`; opens none.
 * No log line carries an amount, a name or a text -- only ids and enum names.
 */
internal object TenderPayout {
    /**
     * The winner must never learn who paid out (tenders.adoc). In the member's OWN ledger view the actor of a payout row is
     * dropped; the board-side views keep it.
     */
    fun withoutPayoutActor(entry: LtrLedgerEntryDto): LtrLedgerEntryDto =
        if (entry.entryType == LtrLedgerEntryType.TENDER_PAYOUT) entry.copy(createdById = null, createdByDisplayName = null) else entry

    /** What a payout call did. The caller writes the audit entry for [Paid] and [Failed] (the last database operation of the transaction). */
    sealed interface Outcome {
        /** The tender was already PAID: nothing was written. */
        data object AlreadyPaid : Outcome

        data class Paid(
            val ledgerEntryId: Uuid,
            val bidId: Uuid,
            val fromStatus: TenderStatus,
        ) : Outcome

        /** The winner cannot receive LTR; the failure is recorded on the tender and NOTHING was booked. */
        data class Failed(
            val reason: TenderPayoutFailureReason,
            val bidId: Uuid,
            val fromStatus: TenderStatus,
        ) : Outcome
    }

    fun payOut(
        current: CurrentMember,
        tenderId: Uuid,
    ): Outcome {
        val row = TenderStore.lock(tenderId)
        // The currency is checked BEFORE any ledger access (EUR is unreachable; the database CHECK refuses it as well).
        requireLtrPayout(row[TenderTable.currency])
        // The winner and every other bidder never pay out (any bid row, a withdrawn one too), and the member who recorded the acceptance does
        // not either: the payout is a second pair of eyes on the acceptance.
        requireNotTenderBidder(tenderId = tenderId, memberId = current.memberId)
        val acceptance = TenderStore.acceptedAcceptance(tenderId)
        if (acceptance != null && acceptance[TenderAcceptanceTable.decidedBy] == current.memberId) throw ForbiddenException()

        val from = row[TenderTable.status]
        if (from == TenderStatus.PAID) return Outcome.AlreadyPaid
        if (from !in PAYABLE_STATUSES) throw ConflictException(NOT_AVAILABLE)

        val award = TenderStore.award(tenderId) ?: error("tender $tenderId is $from without an award")
        val bid =
            TenderStore.bidById(tenderId = tenderId, bidId = award[TenderAwardTable.bidId])
                ?: error("tender $tenderId lost its awarded bid")
        check(bid[TenderBidTable.status] == TenderBidStatus.AWARDED) { "tender $tenderId: the awarded bid is not AWARDED" }
        requireLtrPayout(bid[TenderBidTable.currency])
        val amount = tenderPayoutAmount(bid[TenderBidTable.price])
        val bidId = bid[TenderBidTable.id]
        val winnerId = bid[TenderBidTable.bidderMemberId]

        // The winner row is locked (forMemberUpdate) so a status change cannot slip in between the check and the credit.
        val winnerStatus = lockedMemberStatuses(listOf(winnerId))[winnerId]
        val failure = if (winnerStatus == null) TenderPayoutFailureReason.WINNER_NOT_LTR_ELIGIBLE else payoutFailureFor(winnerStatus)
        if (failure != null) {
            val changed =
                TenderStore.markPayoutFailed(
                    tenderId = tenderId,
                    reason = failure,
                    by = current.memberId,
                    at = DbClock.nowLocalDateTime(),
                )
            if (changed != 1) throw ConflictException(CONCURRENT_CHANGE)
            logger.info { "tender $tenderId payout failed: $failure" }
            return Outcome.Failed(reason = failure, bidId = bidId, fromStatus = from)
        }

        val now = DbClock.nowLocalDateTime()
        val entryId = Uuid.random()
        LtrLedgerEntryTable.insert {
            it[LtrLedgerEntryTable.id] = entryId
            it[memberId] = winnerId
            it[entryType] = LtrLedgerEntryType.TENDER_PAYOUT
            it[amountLtr] = amount
            it[referenceType] = LtrLedgerReferenceType.TENDER
            it[referenceId] = tenderId
            it[note] = null
            it[createdBy] = current.memberId
            it[createdAt] = now
        }
        // Layer (b)/(c): a unique violation can only mean that somebody else paid first. ONLY that is translated; every other failure propagates and
        // rolls the whole transaction back (the savepoint keeps PostgreSQL's transaction alive for the translation).
        try {
            withSavepoint(name = "tender_payout_insert") {
                TenderStore.insertPayout(
                    tenderId = tenderId,
                    ledgerEntryId = entryId,
                    bidId = bidId,
                    amount = amount,
                    executedBy = current.memberId,
                    executedAt = now,
                )
            }
        } catch (e: Exception) {
            if (e.isUniqueViolation()) throw ConflictException(CONCURRENT_CHANGE)
            throw e
        }
        val changed = TenderStore.markPaid(tenderId = tenderId, ledgerEntryId = entryId)
        check(changed == 1) { "tender $tenderId could not be marked PAID" }
        logger.info { "tender $tenderId paid out" }
        return Outcome.Paid(ledgerEntryId = entryId, bidId = bidId, fromStatus = from)
    }
}
