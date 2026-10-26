package network.lapis.cloud.server.tender

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.201 -- the ONLY reader and writer of `tender`, `tender_bid`, `tender_award` and `tender_acceptance` (`TenderAccessTripwireTest` pins this;
 * `TenderPersonalData` is the one other file that touches them, for the data-subject export/erasure). Every method must run inside an open
 * `transaction {}`; none of them opens one. Every query that returns a list has an explicit `ORDER BY` (PostgreSQL returns unordered rows
 * otherwise) and a hard cap (DoS guard).
 *
 * No audit entry and no clock reading decisions are made here: the caller passes the instants in.
 */
internal object TenderStore {
    /** Upper bound of every list a caller can trigger. */
    const val MAX_LIST_RESULTS = 500

    /** Upper bound of the bids on ONE tender (also the cap on creating a new bid). */
    const val MAX_BIDS_PER_TENDER = 1000

    // ── tender ──────────────────────────────────────────────────────────────────────────────────────────────

    fun find(tenderId: Uuid): ResultRow? = TenderTable.selectAll().where { TenderTable.id eq tenderId }.singleOrNull()

    /** Locks the tender row `FOR UPDATE` and returns it as it is NOW (no lazy close). */
    fun lock(tenderId: Uuid): ResultRow =
        TenderTable
            .selectAll()
            .where { TenderTable.id eq tenderId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Not found")

    /**
     * THE choke point of every writing path: locks the row and, if it is stored as PUBLISHED but its deadline has been reached, persists CLOSED
     * (`closed_at` = the class A instant of now) before the caller proceeds. Reading paths never call this. [wallNow] is the class B "now".
     */
    fun lockAndCloseIfDue(
        tenderId: Uuid,
        wallNow: LocalDateTime,
    ): ResultRow = closeIfDue(row = lock(tenderId), wallNow = wallNow)

    /**
     * The second half of [lockAndCloseIfDue], for a caller that wants to read the clock only AFTER the lock was granted (a bid that waited for the
     * lock must be judged against the time it actually got it). [row] must be the locked row.
     */
    fun closeIfDue(
        row: ResultRow,
        wallNow: LocalDateTime,
    ): ResultRow {
        if (row[TenderTable.status] == TenderStatus.PUBLISHED && wallNow >= row[TenderTable.deadline]) {
            val tenderId = row[TenderTable.id]
            TenderTable.update({ TenderTable.id eq tenderId }) {
                it[status] = TenderStatus.CLOSED
                it[closedAt] = DbClock.nowLocalDateTime()
            }
            return TenderTable.selectAll().where { TenderTable.id eq tenderId }.single()
        }
        return row
    }

    fun insertTender(
        id: Uuid,
        bindingMotionId: Uuid,
        title: String,
        description: String,
        descriptionFormat: TenderDescriptionFormat,
        expectedResult: String,
        deadlineWall: LocalDateTime,
        criterion: TenderAwardCriterion,
        visibility: TenderBidVisibility,
        createdBy: Uuid,
        createdAt: LocalDateTime,
    ) {
        TenderTable.insert {
            it[TenderTable.id] = id
            it[TenderTable.title] = title
            it[TenderTable.description] = description
            it[TenderTable.descriptionFormat] = descriptionFormat
            it[TenderTable.expectedResult] = expectedResult
            it[TenderTable.bindingMotionId] = bindingMotionId
            it[awardCriterion] = criterion
            it[currency] = TenderCurrency.LTR
            it[bidVisibility] = visibility
            it[status] = TenderStatus.DRAFT
            it[deadline] = deadlineWall
            it[TenderTable.createdBy] = createdBy
            it[TenderTable.createdAt] = createdAt
        }
    }

    fun updateDraft(
        tenderId: Uuid,
        bindingMotionId: Uuid,
        title: String,
        description: String,
        descriptionFormat: TenderDescriptionFormat,
        expectedResult: String,
        deadlineWall: LocalDateTime,
        criterion: TenderAwardCriterion,
        visibility: TenderBidVisibility,
    ) {
        TenderTable.update({ (TenderTable.id eq tenderId) and (TenderTable.status eq TenderStatus.DRAFT) }) {
            it[TenderTable.title] = title
            it[TenderTable.description] = description
            it[TenderTable.descriptionFormat] = descriptionFormat
            it[TenderTable.expectedResult] = expectedResult
            it[TenderTable.bindingMotionId] = bindingMotionId
            it[awardCriterion] = criterion
            it[bidVisibility] = visibility
            it[deadline] = deadlineWall
        }
    }

    fun markPublished(
        tenderId: Uuid,
        by: Uuid,
        at: LocalDateTime,
    ) {
        TenderTable.update({ TenderTable.id eq tenderId }) {
            it[status] = TenderStatus.PUBLISHED
            it[publishedBy] = by
            it[publishedAt] = at
        }
    }

    fun markCancelled(
        tenderId: Uuid,
        by: Uuid,
        at: LocalDateTime,
        reasonText: String,
    ) {
        TenderTable.update({ TenderTable.id eq tenderId }) {
            it[status] = TenderStatus.CANCELLED
            it[cancelledBy] = by
            it[cancelledAt] = at
            it[cancelReason] = reasonText
        }
    }

    fun setAwardMotion(
        tenderId: Uuid,
        motionId: Uuid,
    ) {
        TenderTable.update({ TenderTable.id eq tenderId }) { it[awardMotionId] = motionId }
    }

    fun setStatus(
        tenderId: Uuid,
        newStatus: TenderStatus,
    ) {
        TenderTable.update({ TenderTable.id eq tenderId }) { it[status] = newStatus }
    }

    /**
     * Tenders ordered by deadline (newest first), then id. [statusFilter] applies to the EFFECTIVE status: CLOSED also matches a PUBLISHED row whose
     * deadline has been reached, PUBLISHED only matches one that is still running.
     */
    fun list(
        statusFilter: TenderStatus?,
        wallNow: LocalDateTime,
        includeInternal: Boolean = true,
    ): List<ResultRow> {
        val statusCondition: Op<Boolean> =
            when (statusFilter) {
                null -> Op.TRUE
                TenderStatus.PUBLISHED -> (TenderTable.status eq TenderStatus.PUBLISHED) and (TenderTable.deadline greater wallNow)
                TenderStatus.CLOSED ->
                    (TenderTable.status eq TenderStatus.CLOSED) or
                        ((TenderTable.status eq TenderStatus.PUBLISHED) and (TenderTable.deadline lessEq wallNow))
                else -> TenderTable.status eq statusFilter
            }
        // Rows only the board may see (drafts, tenders cancelled before publication) are excluded in SQL for everybody else, so they cannot
        // consume the result limit and push visible tenders out of the list.
        val condition: Op<Boolean> =
            if (includeInternal) {
                statusCondition
            } else {
                statusCondition and
                    (TenderTable.status neq TenderStatus.DRAFT) and
                    ((TenderTable.status neq TenderStatus.CANCELLED) or TenderTable.publishedAt.isNotNull())
            }
        return TenderTable
            .selectAll()
            .where { condition }
            .orderBy(TenderTable.deadline to SortOrder.DESC, TenderTable.id to SortOrder.ASC)
            .limit(MAX_LIST_RESULTS)
            .toList()
    }

    /** Tenders that carry the given binding motion (several tenders per resolution are allowed). */
    fun idsByBindingMotion(motionId: Uuid): List<Uuid> =
        TenderTable
            .select(TenderTable.id)
            .where { TenderTable.bindingMotionId eq motionId }
            .orderBy(TenderTable.id to SortOrder.ASC)
            .limit(MAX_LIST_RESULTS)
            .map { it[TenderTable.id] }

    fun idByAwardMotion(motionId: Uuid): Uuid? =
        TenderTable
            .select(TenderTable.id)
            .where { TenderTable.awardMotionId eq motionId }
            .singleOrNull()
            ?.get(TenderTable.id)

    // ── bids ────────────────────────────────────────────────────────────────────────────────────────────────

    /** All bid rows of a tender in the deterministic order price, price_set_at, id (withdrawn ones included; the caller filters). */
    fun bids(tenderId: Uuid): List<ResultRow> =
        TenderBidTable
            .selectAll()
            .where { TenderBidTable.tenderId eq tenderId }
            .orderBy(
                TenderBidTable.price to SortOrder.ASC,
                TenderBidTable.priceSetAt to SortOrder.ASC,
                TenderBidTable.id to SortOrder.ASC,
            ).limit(MAX_BIDS_PER_TENDER)
            .toList()

    fun bid(
        tenderId: Uuid,
        bidderId: Uuid,
    ): ResultRow? =
        TenderBidTable
            .selectAll()
            .where { (TenderBidTable.tenderId eq tenderId) and (TenderBidTable.bidderMemberId eq bidderId) }
            .singleOrNull()

    fun bidById(
        tenderId: Uuid,
        bidId: Uuid,
    ): ResultRow? =
        TenderBidTable
            .selectAll()
            .where { (TenderBidTable.id eq bidId) and (TenderBidTable.tenderId eq tenderId) }
            .singleOrNull()

    fun bidsByIds(bidIds: Collection<Uuid>): Map<Uuid, ResultRow> {
        if (bidIds.isEmpty()) return emptyMap()
        return TenderBidTable
            .selectAll()
            .where { TenderBidTable.id inList bidIds.toList() }
            .orderBy(TenderBidTable.id to SortOrder.ASC)
            .associateBy { it[TenderBidTable.id] }
    }

    fun hasAnyBid(
        tenderId: Uuid,
        memberId: Uuid,
    ): Boolean = bid(tenderId = tenderId, bidderId = memberId) != null

    /** Every member with ANY bid row on the tender (a withdrawn bid included): the conflicted parties. */
    fun bidderIds(tenderId: Uuid): Set<Uuid> =
        TenderBidTable
            .select(TenderBidTable.bidderMemberId)
            .where { TenderBidTable.tenderId eq tenderId }
            .limit(MAX_BIDS_PER_TENDER)
            .map { it[TenderBidTable.bidderMemberId] }
            .toSet()

    fun bidCount(tenderId: Uuid): Long = TenderBidTable.selectAll().where { TenderBidTable.tenderId eq tenderId }.count()

    /** Number of bids that count (not withdrawn) per tender id. */
    fun countedBidsByTender(tenderIds: Collection<Uuid>): Map<Uuid, Int> {
        if (tenderIds.isEmpty()) return emptyMap()
        return TenderBidTable
            .select(TenderBidTable.tenderId)
            .where { (TenderBidTable.tenderId inList tenderIds.toList()) and (TenderBidTable.status neq TenderBidStatus.WITHDRAWN) }
            .groupingBy { it[TenderBidTable.tenderId] }
            .eachCount()
    }

    /** The caller's own bid status per tender id (a bid row of any status). */
    fun bidStatusesOf(
        tenderIds: Collection<Uuid>,
        memberId: Uuid,
    ): Map<Uuid, TenderBidStatus> {
        if (tenderIds.isEmpty()) return emptyMap()
        return TenderBidTable
            .select(TenderBidTable.tenderId, TenderBidTable.status)
            .where { (TenderBidTable.tenderId inList tenderIds.toList()) and (TenderBidTable.bidderMemberId eq memberId) }
            .associate { it[TenderBidTable.tenderId] to it[TenderBidTable.status] }
    }

    /** The caller's own bids, newest first, ties broken by id. */
    fun bidsOf(memberId: Uuid): List<ResultRow> =
        TenderBidTable
            .selectAll()
            .where { TenderBidTable.bidderMemberId eq memberId }
            .orderBy(TenderBidTable.submittedAt to SortOrder.DESC, TenderBidTable.id to SortOrder.ASC)
            .limit(MAX_LIST_RESULTS)
            .toList()

    fun insertBid(
        id: Uuid,
        tenderId: Uuid,
        bidderId: Uuid,
        amount: BigDecimal,
        bidText: String,
        at: LocalDateTime,
    ) {
        TenderBidTable.insert {
            it[TenderBidTable.id] = id
            it[TenderBidTable.tenderId] = tenderId
            it[bidderMemberId] = bidderId
            it[price] = amount
            it[currency] = TenderCurrency.LTR
            it[text] = bidText
            it[status] = TenderBidStatus.SUBMITTED
            it[submittedAt] = at
            it[priceSetAt] = at
            it[withdrawnAt] = null
        }
    }

    /** Replaces the content of the caller's own bid. [newSubmittedAt] / [newPriceSetAt] `null` = keep the stored value. */
    fun replaceBid(
        bidId: Uuid,
        amount: BigDecimal,
        bidText: String,
        newSubmittedAt: LocalDateTime?,
        newPriceSetAt: LocalDateTime?,
    ) {
        TenderBidTable.update({ TenderBidTable.id eq bidId }) {
            it[price] = amount
            it[text] = bidText
            it[status] = TenderBidStatus.SUBMITTED
            it[withdrawnAt] = null
            if (newSubmittedAt != null) it[submittedAt] = newSubmittedAt
            if (newPriceSetAt != null) it[priceSetAt] = newPriceSetAt
        }
    }

    fun markBidWithdrawn(
        bidId: Uuid,
        at: LocalDateTime,
    ) {
        TenderBidTable.update({ TenderBidTable.id eq bidId }) {
            it[status] = TenderBidStatus.WITHDRAWN
            it[withdrawnAt] = at
        }
    }

    fun markBidAwarded(bidId: Uuid) {
        TenderBidTable.update({ TenderBidTable.id eq bidId }) { it[status] = TenderBidStatus.AWARDED }
    }

    /** All other bids that are still SUBMITTED lose; withdrawn ones stay withdrawn. */
    fun rejectOtherSubmittedBids(
        tenderId: Uuid,
        winningBidId: Uuid,
    ) {
        TenderBidTable.update({
            (TenderBidTable.tenderId eq tenderId) and
                (TenderBidTable.id neq winningBidId) and
                (TenderBidTable.status eq TenderBidStatus.SUBMITTED)
        }) {
            it[status] = TenderBidStatus.REJECTED
        }
    }

    // ── award / acceptance ──────────────────────────────────────────────────────────────────────────────────

    fun award(tenderId: Uuid): ResultRow? = TenderAwardTable.selectAll().where { TenderAwardTable.tenderId eq tenderId }.singleOrNull()

    fun awardsByTender(tenderIds: Collection<Uuid>): Map<Uuid, ResultRow> {
        if (tenderIds.isEmpty()) return emptyMap()
        return TenderAwardTable
            .selectAll()
            .where { TenderAwardTable.tenderId inList tenderIds.toList() }
            .orderBy(TenderAwardTable.id to SortOrder.ASC)
            .associateBy { it[TenderAwardTable.tenderId] }
    }

    fun insertAward(
        id: Uuid,
        tenderId: Uuid,
        bidId: Uuid,
        awardedBy: Uuid,
        awardedAt: LocalDateTime,
        awardMotionId: Uuid?,
        resolutionId: Uuid?,
        justification: String?,
        conflictedCount: Int,
        quorumEligibleCount: Int?,
    ) {
        TenderAwardTable.insert {
            it[TenderAwardTable.id] = id
            it[TenderAwardTable.tenderId] = tenderId
            it[TenderAwardTable.bidId] = bidId
            it[TenderAwardTable.awardedBy] = awardedBy
            it[TenderAwardTable.awardedAt] = awardedAt
            it[TenderAwardTable.awardMotionId] = awardMotionId
            it[TenderAwardTable.resolutionId] = resolutionId
            it[TenderAwardTable.justification] = justification
            it[TenderAwardTable.conflictedCount] = conflictedCount
            it[TenderAwardTable.quorumEligibleCount] = quorumEligibleCount
        }
    }

    fun acceptances(tenderId: Uuid): List<ResultRow> =
        TenderAcceptanceTable
            .selectAll()
            .where { TenderAcceptanceTable.tenderId eq tenderId }
            .orderBy(TenderAcceptanceTable.decidedAt to SortOrder.ASC, TenderAcceptanceTable.id to SortOrder.ASC)
            .limit(MAX_LIST_RESULTS)
            .toList()

    fun insertAcceptance(
        id: Uuid,
        tenderId: Uuid,
        accepted: Boolean,
        reasonText: String?,
        decidedBy: Uuid,
        decidedAt: LocalDateTime,
    ) {
        TenderAcceptanceTable.insert {
            it[TenderAcceptanceTable.id] = id
            it[TenderAcceptanceTable.tenderId] = tenderId
            it[decision] =
                if (accepted) {
                    TenderAcceptanceDecision.ACCEPTED
                } else {
                    TenderAcceptanceDecision.REJECTED
                }
            it[reason] = reasonText
            it[TenderAcceptanceTable.decidedBy] = decidedBy
            it[TenderAcceptanceTable.decidedAt] = decidedAt
        }
    }

    // ── organisation default ────────────────────────────────────────────────────────────────────────────────

    /** The pre-selected bid visibility of a new tender; a missing row (never happens, V1 seeds it) means the factory default. */
    fun defaultVisibility(): TenderBidVisibility =
        OrganizationSettingsTable
            .select(OrganizationSettingsTable.tenderDefaultVisibility)
            .limit(1)
            .singleOrNull()
            ?.get(OrganizationSettingsTable.tenderDefaultVisibility)
            ?: TenderBidVisibility.HIDDEN_UNTIL_DEADLINE

    /** A published tender that closes inside a window, for the dashboard signal (id, deadline, currency only). */
    data class ClosingTender(
        val id: Uuid,
        val deadline: LocalDateTime,
        val currency: TenderCurrency,
    )

    /**
     * V1.9.224 -- PUBLISHED tenders whose deadline lies after [from] (organization wall clock, strict: a bid is valid only while `wallNow < deadline`)
     * and not after [until], earliest first. Never writes.
     */
    fun closingBetween(
        from: LocalDateTime,
        until: LocalDateTime,
        limit: Int,
    ): List<ClosingTender> =
        TenderTable
            .select(TenderTable.id, TenderTable.deadline, TenderTable.currency)
            .where {
                (TenderTable.status eq TenderStatus.PUBLISHED) and (TenderTable.deadline greater from) and
                    (TenderTable.deadline lessEq until)
            }.orderBy(TenderTable.deadline to SortOrder.ASC, TenderTable.id to SortOrder.ASC)
            .limit(limit)
            .map { ClosingTender(id = it[TenderTable.id], deadline = it[TenderTable.deadline], currency = it[TenderTable.currency]) }

    /** V1.9.224 -- of [tenderIds], those on which [memberId] has a bid that is not withdrawn. */
    fun tendersWithLiveBidOf(
        memberId: Uuid,
        tenderIds: List<Uuid>,
    ): Set<Uuid> =
        TenderBidTable
            .select(TenderBidTable.tenderId)
            .where {
                (TenderBidTable.bidderMemberId eq memberId) and
                    (TenderBidTable.tenderId inList tenderIds) and
                    (TenderBidTable.status neq TenderBidStatus.WITHDRAWN)
            }.map { it[TenderBidTable.tenderId] }
            .toSet()

    /** V1.9.224 -- tenders on which [memberId]'s bid was awarded or rejected by an award stamped at or after [since] (class A). */
    fun tendersDecidedForSince(
        memberId: Uuid,
        since: LocalDateTime,
        limit: Int,
    ): List<Uuid> =
        TenderBidTable
            .join(TenderAwardTable, JoinType.INNER, additionalConstraint = { TenderBidTable.tenderId eq TenderAwardTable.tenderId })
            .select(TenderBidTable.tenderId)
            .where {
                (TenderBidTable.bidderMemberId eq memberId) and
                    (TenderBidTable.status inList listOf(TenderBidStatus.AWARDED, TenderBidStatus.REJECTED)) and
                    (TenderAwardTable.awardedAt greaterEq since)
            }.orderBy(TenderBidTable.tenderId to SortOrder.ASC)
            .limit(limit)
            .map { it[TenderBidTable.tenderId] }
}
