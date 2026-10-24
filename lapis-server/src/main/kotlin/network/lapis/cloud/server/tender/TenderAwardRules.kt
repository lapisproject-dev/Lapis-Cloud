package network.lapis.cloud.server.tender

import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardReadiness
import network.lapis.cloud.shared.domain.TenderBidStatus
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.uuid.Uuid

/*
 * V1.9.202 -- THE one place that knows which bids of a CLOSED tender can win and how far the award motion is. `awardTender` (enforcement) and
 * `TenderDtos` (the `awardable` mark and `awardReadiness` the screens show) both use it, so the screen can never offer something the server
 * refuses (or the other way round). Pure functions over already loaded rows; no database access, no locking, no logging.
 */
internal object TenderAwardRules {
    /**
     * The bids that are still in the running: SUBMITTED and held by a member who is still eligible for this currency. A bid of somebody who
     * left, was rejected, was erased or (for LTR) is a guest cannot win and must not block the cheapest eligible one.
     */
    fun runningBids(
        tenderRow: ResultRow,
        bids: List<ResultRow>,
        statuses: Map<Uuid, MemberStatus>,
    ): List<ResultRow> {
        val currency = tenderRow[TenderTable.currency]
        return bids.filter {
            val status = statuses[it[TenderBidTable.bidderMemberId]]
            it[TenderBidTable.status] == TenderBidStatus.SUBMITTED &&
                status != null &&
                tenderBidderEligible(status = status, currency = currency)
        }
    }

    /** LOWEST_PRICE: the running bids that carry the lowest price (several on a tie). BEST_OFFER: every running bid. */
    fun awardableBidIds(
        tenderRow: ResultRow,
        bids: List<ResultRow>,
        statuses: Map<Uuid, MemberStatus>,
    ): Set<Uuid> {
        val running = runningBids(tenderRow = tenderRow, bids = bids, statuses = statuses)
        if (running.isEmpty()) return emptySet()
        val candidates =
            if (tenderRow[TenderTable.awardCriterion] == TenderAwardCriterion.LOWEST_PRICE) {
                val lowest = running.minOf { it[TenderBidTable.price] }
                running.filter { it[TenderBidTable.price].compareTo(lowest) == 0 }
            } else {
                running
            }
        return candidates.map { it[TenderBidTable.id] }.toSet()
    }

    /** BEST_OFFER always needs a justification; LOWEST_PRICE only on a price tie. */
    fun justificationRequired(
        tenderRow: ResultRow,
        bids: List<ResultRow>,
        statuses: Map<Uuid, MemberStatus>,
    ): Boolean =
        tenderRow[TenderTable.awardCriterion] == TenderAwardCriterion.BEST_OFFER ||
            awardableBidIds(tenderRow = tenderRow, bids = bids, statuses = statuses).size > 1

    /** `true` if the resolution of the award motion was taken strictly after the deadline of the tender. */
    fun decidedAfterDeadline(
        resolution: ResultRow,
        tenderRow: ResultRow,
    ): Boolean {
        val decidedAt = ServerClock.systemToInstant(resolution[ResolutionTable.decidedAt])
        val deadlineAt = ServerClock.wallToInstant(wall = tenderRow[TenderTable.deadline], orgZone = OrganizationTimeZone.current())
        return decidedAt > deadlineAt
    }

    /**
     * How far a tender is from being awardable. [motionRow] / [resolutionRow] are the award motion and its resolution (both `null` when there is
     * none). LOWEST_PRICE is always READY (an act of the board, no vote).
     */
    fun awardReadiness(
        tenderRow: ResultRow,
        motionRow: ResultRow?,
        resolutionRow: ResultRow?,
    ): TenderAwardReadiness {
        if (tenderRow[TenderTable.awardCriterion] == TenderAwardCriterion.LOWEST_PRICE) return TenderAwardReadiness.READY
        if (motionRow == null) return TenderAwardReadiness.NEEDS_AWARD_MOTION
        val status = motionRow[MotionTable.status]
        if (status != MotionStatus.RESOLVED) {
            return if (status in TenderMotionBinding.REPLACEABLE_AWARD_MOTION_STATUSES) {
                TenderAwardReadiness.AWARD_MOTION_NOT_ADOPTED
            } else {
                TenderAwardReadiness.AWARD_MOTION_PENDING
            }
        }
        if (resolutionRow == null) return TenderAwardReadiness.AWARD_MOTION_PENDING
        if (resolutionRow[ResolutionTable.status] != ResolutionStatus.ADOPTED) return TenderAwardReadiness.AWARD_MOTION_NOT_ADOPTED
        if (!decidedAfterDeadline(resolution = resolutionRow, tenderRow = tenderRow)) return TenderAwardReadiness.AWARD_MOTION_TOO_EARLY
        return TenderAwardReadiness.READY
    }
}
