package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.agreementtexts.AgreementMarkdown
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.tender.GUEST_LTR_REFUSAL
import network.lapis.cloud.server.tender.TENDER_MANAGER_ROLES
import network.lapis.cloud.server.tender.TENDER_MAX_DESCRIPTION
import network.lapis.cloud.server.tender.TENDER_READ_ROLES
import network.lapis.cloud.server.tender.TenderAwardRules
import network.lapis.cloud.server.tender.TenderDtos
import network.lapis.cloud.server.tender.TenderMotionBinding
import network.lapis.cloud.server.tender.TenderOptions
import network.lapis.cloud.server.tender.TenderStore
import network.lapis.cloud.server.tender.TenderViewer
import network.lapis.cloud.server.tender.effectiveTenderStatus
import network.lapis.cloud.server.tender.requireNotTenderBidder
import network.lapis.cloud.server.tender.tenderBidderEligible
import network.lapis.cloud.server.tender.tenderVisibleTo
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAuditSnapshot
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderAwardMotionOptionDto
import network.lapis.cloud.shared.domain.TenderBidDto
import network.lapis.cloud.shared.domain.TenderBidInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderCreateInput
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDefaultsDto
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderDraftUpdateInput
import network.lapis.cloud.shared.domain.TenderMotionOptionDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ITenderService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val MAX_TITLE = 200
private const val MAX_DESCRIPTION = TENDER_MAX_DESCRIPTION
private const val MAX_EXPECTED_RESULT = 4_000
private const val MAX_BID_TEXT = 4_000
private const val MAX_JUSTIFICATION = 4_000
private const val MAX_REASON = 2_000

/** A deadline must lie at least this far ahead when a tender is created or published, and at most [MAX_DEADLINE_DAYS] days ahead. */
private val MIN_DEADLINE_LEAD = 1.hours
private const val MAX_DEADLINE_DAYS = 366

/** The technical maximum of the `DECIMAL(18,2)` column. NOT a budget limit. */
private val MAX_PRICE = BigDecimal("9999999999999999.99")
private val MIN_PRICE = BigDecimal("0.01")

private const val NOT_AVAILABLE = "The tender is not available for this action"
private const val CONCURRENT_CHANGE = "The tender was changed concurrently, please reload"

/**
 * V1.9.201 -- tenders, wave 1 (see [ITenderService], `docs/features/tenders.adoc`, `docs/architecture/tenders.adoc`). Wave 1 moves no LTR and has no
 * external effect, so everything runs in one transaction per call.
 *
 * ## Lock order (every writing method, deadlock-free)
 *
 * **motion -> tender -> bids -> member -> audit.** A method that touches a motion locks it FIRST ([MotionDecisionLock.lockMotion], several motions in
 * ascending id order), exactly like `GovernanceService`; the tender row is locked next ([TenderStore.lock]), its clock read only AFTER the lock was
 * granted and the deadline closed lazily ([TenderStore.closeIfDue]); bid rows are written under the tender lock; a member row is only ever locked
 * with the `forMemberUpdate` helpers (never a plain `forUpdate`); the audit entry is the last database operation.
 *
 * ## Visibility
 *
 * Nothing in this class decides who sees a foreign bid: [TenderDtos] applies `TenderVisibilityPolicy` to every DTO. A tender the viewer may not see
 * reads as [NotFoundException] (no existence oracle). The bid visibility of a published tender has no writing path at all.
 *
 * ## Conflict of interest
 *
 * Whoever has ANY bid row on a tender (a withdrawn one too) may neither award, accept nor register an award motion ([requireNotTenderBidder]); on
 * the award motion the bidders are excluded from voting and quorum by `TenderConflicts` (hooks in `GovernanceService`).
 *
 * No log line carries a member, a price or a text -- only ids and counts.
 */
class TenderService(
    private val call: ApplicationCall,
    /** V1.9.202: budget of [previewTenderDescription]; Application passes ONE shared instance (the service itself is created per call). */
    private val previewRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
) : ITenderService {
    override suspend fun createTender(input: TenderCreateInput): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val motionId = input.bindingMotionId.toTenderUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val wallNow = OrganizationTimeZone.wallNow()
            val fields =
                validateFields(
                    title = input.title,
                    description = input.description,
                    descriptionFormat = input.descriptionFormat,
                    expectedResult = input.expectedResult,
                    deadline = input.deadline,
                    currency = input.currency,
                    wallNow = wallNow,
                )
            val motionRow = MotionDecisionLock.lockMotion(motionId)
            TenderMotionBinding.requireApprovedBindingMotion(motionRow)
            val id = Uuid.random()
            TenderStore.insertTender(
                id = id,
                bindingMotionId = motionId,
                title = fields.title,
                description = fields.description,
                descriptionFormat = input.descriptionFormat,
                expectedResult = fields.expectedResult,
                deadlineWall = input.deadline,
                criterion = input.awardCriterion,
                visibility = input.bidVisibility ?: TenderStore.defaultVisibility(),
                createdBy = current.memberId,
                createdAt = DbClock.nowLocalDateTime(),
            )
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun updateTenderDraft(
        tenderId: String,
        input: TenderDraftUpdateInput,
    ): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = tenderId.toTenderUuid()
        val motionId = input.bindingMotionId.toTenderUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val wallNow = OrganizationTimeZone.wallNow()
            val fields =
                validateFields(
                    title = input.title,
                    description = input.description,
                    descriptionFormat = input.descriptionFormat,
                    expectedResult = input.expectedResult,
                    deadline = input.deadline,
                    currency = input.currency,
                    wallNow = wallNow,
                )
            val motionRow = MotionDecisionLock.lockMotion(motionId)
            val row = TenderStore.lock(id)
            if (row[TenderTable.status] != TenderStatus.DRAFT) throw ConflictException("Only a draft can be changed")
            TenderMotionBinding.requireApprovedBindingMotion(motionRow)
            TenderStore.updateDraft(
                tenderId = id,
                bindingMotionId = motionId,
                title = fields.title,
                description = fields.description,
                descriptionFormat = input.descriptionFormat,
                expectedResult = fields.expectedResult,
                deadlineWall = input.deadline,
                criterion = input.awardCriterion,
                visibility = input.bidVisibility ?: row[TenderTable.bidVisibility],
            )
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun publishTender(tenderId: String): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = tenderId.toTenderUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val peek = TenderStore.find(id) ?: throw NotFoundException("Not found")
            val motionId = peek[TenderTable.bindingMotionId]
            val motionRow = MotionDecisionLock.lockMotion(motionId)
            val locked = TenderStore.lock(id)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            if (row[TenderTable.bindingMotionId] != motionId) throw ConflictException(CONCURRENT_CHANGE)
            if (row[TenderTable.status] != TenderStatus.DRAFT) throw ConflictException("Only a draft can be published")
            TenderMotionBinding.requireApprovedBindingMotion(motionRow)
            requireRenderable(format = row[TenderTable.descriptionFormat], description = row[TenderTable.description])
            if (row[TenderTable.deadline] < wallNow.plusLead(MIN_DEADLINE_LEAD)) {
                throw ConflictException("The deadline must be at least one hour in the future")
            }
            TenderStore.markPublished(tenderId = id, by = current.memberId, at = DbClock.nowLocalDateTime())
            audit(
                current = current,
                tenderId = id,
                snapshot =
                    TenderAuditSnapshot(
                        event = "PUBLISH",
                        status = TenderStatus.PUBLISHED.name,
                        visibility = row[TenderTable.bidVisibility].name,
                        criterion = row[TenderTable.awardCriterion].name,
                        currency = row[TenderTable.currency].name,
                        deadline = row[TenderTable.deadline].toString(),
                        bindingMotionId = motionId.toString(),
                    ),
            )
            logger.info { "tender $id published" }
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun cancelTender(
        tenderId: String,
        reason: String,
    ): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = tenderId.toTenderUuid()
        val text = reason.trim()
        if (text.isEmpty() || text.length > MAX_REASON) throw ConflictException("The reason must have 1 to $MAX_REASON characters")
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val locked = TenderStore.lock(id)
            requireNotTenderBidder(tenderId = id, memberId = current.memberId)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            val from = row[TenderTable.status]
            if (from !in CANCELLABLE) throw ConflictException(NOT_AVAILABLE)
            TenderStore.markCancelled(tenderId = id, by = current.memberId, at = DbClock.nowLocalDateTime(), reasonText = text)
            audit(
                current = current,
                tenderId = id,
                snapshot = TenderAuditSnapshot(event = "CANCEL", status = TenderStatus.CANCELLED.name, fromStatus = from.name),
            )
            logger.info { "tender $id cancelled" }
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun getTender(tenderId: String): TenderDetailDto {
        val current = resolveCurrentMember(call)
        val id = tenderId.toTenderUuid()
        return transaction {
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.find(id) ?: throw NotFoundException("Not found")
            TenderDtos.detail(row = row, current = current, wallNow = wallNow)
        }
    }

    /** Never locks and never writes: the status filter and the status shown are the EFFECTIVE status. */
    override suspend fun listTenders(statusFilter: TenderStatus?): List<TenderSummaryDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            val wallNow = OrganizationTimeZone.wallNow()
            TenderDtos.summaries(
                rows =
                    TenderStore.list(
                        statusFilter = statusFilter,
                        wallNow = wallNow,
                        includeInternal = current.role in TENDER_READ_ROLES && current.status in MemberStatusSets.ORGANIZATION_MEMBER,
                    ),
                current = current,
                wallNow = wallNow,
            )
        }
    }

    override suspend fun submitBid(
        tenderId: String,
        input: TenderBidInput,
    ): TenderBidDto {
        val current = resolveCurrentMember(call)
        val id = tenderId.toTenderUuid()
        val price = validatePrice(input.price)
        val text = input.text.trim()
        if (text.isEmpty() || text.length > MAX_BID_TEXT) throw ConflictException("The bid text must have 1 to $MAX_BID_TEXT characters")
        requireLtr(input.currency)
        return transaction {
            val locked = TenderStore.lock(id)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            // The member row is locked AFTER the tender (lock order), with the helper that never takes a plain FOR UPDATE.
            val lockedStatus =
                requireMembershipStatusIn(memberId = current.memberId, allowed = MemberStatusSets.TENDER_PARTICIPANT, forUpdate = true)
            // V1.9.202: a guest never bids on an LTR tender (decided from the LOCKED member row, not from the session).
            if (!tenderBidderEligible(
                    status = lockedStatus,
                    currency = row[TenderTable.currency],
                )
            ) {
                throw ConflictException(GUEST_LTR_REFUSAL)
            }
            val existing = TenderStore.bid(tenderId = id, bidderId = current.memberId)
            val viewer = TenderDtos.viewerOf(current = current, hasBid = existing != null)
            requireVisible(row = row, viewer = viewer, wallNow = wallNow)
            if (row[TenderTable.status] != TenderStatus.PUBLISHED) throw ConflictException(NOT_AVAILABLE)
            val now = DbClock.nowLocalDateTime()
            val bidId =
                if (existing == null) {
                    if (TenderStore.bidCount(id) >=
                        TenderStore.MAX_BIDS_PER_TENDER
                    ) {
                        throw ConflictException("The tender cannot take more bids")
                    }
                    val newId = Uuid.random()
                    try {
                        withSavepoint(name = "tender_bid_insert") {
                            TenderStore.insertBid(
                                id = newId,
                                tenderId = id,
                                bidderId = current.memberId,
                                amount = price,
                                bidText = text,
                                at = now,
                            )
                        }
                    } catch (e: Exception) {
                        if (e.isUniqueViolation()) throw ConflictException(CONCURRENT_CHANGE)
                        throw e
                    }
                    newId
                } else {
                    val wasWithdrawn = existing[TenderBidTable.status] == TenderBidStatus.WITHDRAWN
                    val priceChanged = existing[TenderBidTable.price].compareTo(price) != 0
                    TenderStore.replaceBid(
                        bidId = existing[TenderBidTable.id],
                        amount = price,
                        bidText = text,
                        newSubmittedAt = if (wasWithdrawn) now else null,
                        newPriceSetAt = if (priceChanged || wasWithdrawn) now else null,
                    )
                    existing[TenderBidTable.id]
                }
            val saved = TenderStore.bid(tenderId = id, bidderId = current.memberId) ?: error("bid $bidId vanished inside its transaction")
            TenderDtos.bidDto(row = saved, names = TenderDtos.displayNames(listOf(current.memberId)), mine = true)
        }
    }

    override suspend fun withdrawBid(tenderId: String): TenderBidDto {
        val current = resolveCurrentMember(call)
        val id = tenderId.toTenderUuid()
        return transaction {
            val locked = TenderStore.lock(id)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            requireMembershipStatusIn(memberId = current.memberId, allowed = MemberStatusSets.TENDER_PARTICIPANT, forUpdate = true)
            val existing = TenderStore.bid(tenderId = id, bidderId = current.memberId)
            requireVisible(row = row, viewer = TenderDtos.viewerOf(current = current, hasBid = existing != null), wallNow = wallNow)
            if (row[TenderTable.status] != TenderStatus.PUBLISHED) throw ConflictException(NOT_AVAILABLE)
            if (existing == null ||
                existing[TenderBidTable.status] != TenderBidStatus.SUBMITTED
            ) {
                throw ConflictException("There is no bid to withdraw")
            }
            TenderStore.markBidWithdrawn(bidId = existing[TenderBidTable.id], at = DbClock.nowLocalDateTime())
            val saved = TenderStore.bid(tenderId = id, bidderId = current.memberId) ?: error("bid vanished inside its transaction")
            TenderDtos.bidDto(row = saved, names = TenderDtos.displayNames(listOf(current.memberId)), mine = true)
        }
    }

    override suspend fun listMyBids(): List<TenderBidDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireMembershipStatusIn(memberId = current.memberId, allowed = MemberStatusSets.TENDER_PARTICIPANT)
            TenderDtos.ownBids(
                memberId = current.memberId,
                name = TenderDtos.displayNames(listOf(current.memberId))[current.memberId].orEmpty(),
            )
        }
    }

    override suspend fun registerAwardMotion(
        tenderId: String,
        motionId: String,
    ): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = tenderId.toTenderUuid()
        val newMotionId = motionId.toTenderUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val peek = TenderStore.find(id) ?: throw NotFoundException("Not found")
            val oldMotionId = peek[TenderTable.awardMotionId]
            // motion first (ascending id order for two motions), then the tender
            val motionRows =
                listOfNotNull(newMotionId, oldMotionId)
                    .distinct()
                    .sortedBy { it.toString() }
                    .associateWith { MotionDecisionLock.lockMotion(it) }
            val locked = TenderStore.lock(id)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            if (row[TenderTable.awardMotionId] != oldMotionId) throw ConflictException(CONCURRENT_CHANGE)
            requireNotTenderBidder(tenderId = id, memberId = current.memberId)
            if (row[TenderTable.awardCriterion] != TenderAwardCriterion.BEST_OFFER) {
                throw ConflictException("An award motion exists only for the criterion BEST_OFFER")
            }
            if (row[TenderTable.status] != TenderStatus.CLOSED) throw ConflictException("The tender must be closed first")
            if (TenderStore.bids(id).none { it[TenderBidTable.status] == TenderBidStatus.SUBMITTED }) {
                throw ConflictException("The tender has no bid to decide on")
            }
            if (oldMotionId == newMotionId) return@transaction TenderDtos.detail(row = row, current = current, wallNow = wallNow)
            val boundElsewhere = TenderStore.idByAwardMotion(newMotionId)
            if (boundElsewhere != null && boundElsewhere != id) throw ConflictException(TenderMotionBinding.NOT_ELIGIBLE_AWARD_MOTION)
            if (oldMotionId != null) {
                val oldStatus = motionRows.getValue(oldMotionId)[MotionTable.status]
                if (oldStatus !in TenderMotionBinding.REPLACEABLE_AWARD_MOTION_STATUSES) {
                    throw ConflictException("The registered award motion cannot be replaced")
                }
            }
            TenderMotionBinding.requireEligibleAwardMotion(motionRow = motionRows.getValue(newMotionId), tenderRow = row)
            TenderStore.setAwardMotion(tenderId = id, motionId = newMotionId)
            audit(
                current = current,
                tenderId = id,
                snapshot = TenderAuditSnapshot(event = "AWARD_MOTION_REGISTERED", motionId = newMotionId.toString()),
            )
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun awardTender(input: TenderAwardInput): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = input.tenderId.toTenderUuid()
        val bidId = input.bidId.toTenderUuid()
        val justification = input.justification?.trim()?.takeIf { it.isNotEmpty() }
        if (justification != null && justification.length > MAX_JUSTIFICATION) {
            throw ConflictException("The justification must have at most $MAX_JUSTIFICATION characters")
        }
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val peek = TenderStore.find(id) ?: throw NotFoundException("Not found")
            val peekedAwardMotion = peek[TenderTable.awardMotionId]
            val criterionPeek = peek[TenderTable.awardCriterion]
            val motionRow =
                if (criterionPeek == TenderAwardCriterion.BEST_OFFER &&
                    peekedAwardMotion != null
                ) {
                    MotionDecisionLock.lockMotion(peekedAwardMotion)
                } else {
                    null
                }
            val locked = TenderStore.lock(id)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            if (row[TenderTable.awardMotionId] != peekedAwardMotion) throw ConflictException(CONCURRENT_CHANGE)
            requireNotTenderBidder(tenderId = id, memberId = current.memberId)
            if (row[TenderTable.status] != TenderStatus.CLOSED) throw ConflictException(NOT_AVAILABLE)
            val bids = TenderStore.bids(id)
            val bid = bids.firstOrNull { it[TenderBidTable.id] == bidId } ?: throw NotFoundException("Not found")
            if (bid[TenderBidTable.status] != TenderBidStatus.SUBMITTED) throw ConflictException("The bid is not in the running")
            val bidderId = bid[TenderBidTable.bidderMemberId]
            // One sorted lock query for all bidders (consistent lock order across concurrent awards, no deadlock).
            val bidderStatuses = lockedMemberStatuses(bids.map { it[TenderBidTable.bidderMemberId] })
            val bidderStatus = bidderStatuses[bidderId]
            if (bidderStatus == null || !tenderBidderEligible(status = bidderStatus, currency = row[TenderTable.currency])) {
                throw ConflictException("The bidder is no longer eligible")
            }
            val conflictedCount = TenderStore.bidderIds(id).size
            var quorumEligibleCount: Int? = null
            var resolutionId: Uuid? = null
            when (row[TenderTable.awardCriterion]) {
                TenderAwardCriterion.LOWEST_PRICE -> {
                    // Bids of members who are no longer eligible (left, rejected, erased) cannot win and must not block the cheapest eligible one.
                    val running = TenderAwardRules.runningBids(tenderRow = row, bids = bids, statuses = bidderStatuses)
                    val lowest = running.minOf { it[TenderBidTable.price] }
                    if (bid[TenderBidTable.price].compareTo(lowest) != 0) throw ConflictException("The cheapest bid must be chosen")
                    val tied = running.count { it[TenderBidTable.price].compareTo(lowest) == 0 }
                    if (tied > 1 && justification == null) throw ConflictException("A justification is required for a price tie")
                }
                TenderAwardCriterion.BEST_OFFER -> {
                    if (justification == null) throw ConflictException("A justification is required")
                    val award = motionRow ?: throw ConflictException("No award motion is registered")
                    if (award[MotionTable.status] != MotionStatus.RESOLVED) throw ConflictException("The award motion is not decided yet")
                    val resolution =
                        award[MotionTable.resolutionId]?.let {
                            ResolutionTable.selectAll().where { ResolutionTable.id eq it }.singleOrNull()
                        }
                            ?: throw ConflictException("The award motion is not decided yet")
                    if (resolution[ResolutionTable.status] !=
                        ResolutionStatus.ADOPTED
                    ) {
                        throw ConflictException("The award motion was not adopted")
                    }
                    if (!TenderAwardRules.decidedAfterDeadline(resolution = resolution, tenderRow = row)) {
                        throw ConflictException("The award motion was decided before the deadline")
                    }
                    resolutionId = resolution[ResolutionTable.id]
                    quorumEligibleCount =
                        quorumEligibleAfterExclusion(resolution = resolution, motion = award, excluded = TenderStore.bidderIds(id))
                }
            }
            val now = DbClock.nowLocalDateTime()
            TenderStore.markBidAwarded(bidId)
            TenderStore.rejectOtherSubmittedBids(tenderId = id, winningBidId = bidId)
            TenderStore.insertAward(
                id = Uuid.random(),
                tenderId = id,
                bidId = bidId,
                awardedBy = current.memberId,
                awardedAt = now,
                awardMotionId = if (row[TenderTable.awardCriterion] == TenderAwardCriterion.BEST_OFFER) peekedAwardMotion else null,
                resolutionId = resolutionId,
                justification = justification,
                conflictedCount = conflictedCount,
                quorumEligibleCount = quorumEligibleCount,
            )
            TenderStore.setStatus(tenderId = id, newStatus = TenderStatus.AWARDED)
            audit(
                current = current,
                tenderId = id,
                snapshot =
                    TenderAuditSnapshot(
                        event = "AWARD",
                        status = TenderStatus.AWARDED.name,
                        criterion = row[TenderTable.awardCriterion].name,
                        bidId = bidId.toString(),
                        conflictedCount = conflictedCount,
                    ),
            )
            logger.info { "tender $id awarded" }
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun recordAcceptance(input: TenderAcceptanceInput): TenderDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = input.tenderId.toTenderUuid()
        val reason = input.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (reason != null &&
            reason.length > MAX_JUSTIFICATION
        ) {
            throw ConflictException("The reason must have at most $MAX_JUSTIFICATION characters")
        }
        if (input.decision == TenderAcceptanceDecision.REJECTED && reason == null) throw ConflictException("A rejection needs a reason")
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val locked = TenderStore.lock(id)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.closeIfDue(row = locked, wallNow = wallNow)
            requireNotTenderBidder(tenderId = id, memberId = current.memberId)
            if (row[TenderTable.status] != TenderStatus.AWARDED) throw ConflictException(NOT_AVAILABLE)
            TenderStore.insertAcceptance(
                id = Uuid.random(),
                tenderId = id,
                accepted = input.decision == TenderAcceptanceDecision.ACCEPTED,
                reasonText = reason,
                decidedBy = current.memberId,
                decidedAt = DbClock.nowLocalDateTime(),
            )
            // Wave 1: ACCEPTED completes the tender, nothing is booked. The payout hook (payout_ledger_entry_id) belongs to wave 3.
            if (input.decision ==
                TenderAcceptanceDecision.ACCEPTED
            ) {
                TenderStore.setStatus(tenderId = id, newStatus = TenderStatus.COMPLETED)
            }
            audit(current = current, tenderId = id, snapshot = TenderAuditSnapshot(event = "ACCEPTANCE", decision = input.decision.name))
            TenderDtos.detail(row = TenderStore.lock(id), current = current, wallNow = wallNow)
        }
    }

    override suspend fun getTenderDefaults(): TenderDefaultsDto {
        val current = resolveCurrentMember(call)
        if (current.role !in TENDER_READ_ROLES) throw ForbiddenException()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            TenderDefaultsDto(
                defaultVisibility = TenderStore.defaultVisibility(),
                canManage = current.role in TENDER_MANAGER_ROLES,
                canReadInternal = true,
            )
        }
    }

    /**
     * V1.9.202 -- the motions the board can bind a NEW tender to, each with the reason if it cannot be. Resolved motions come first (newest
     * first, id as tie-break), then the ones not decided yet. At most [MAX_OPTIONS]; resolutions and committee names are loaded in bulk.
     */
    override suspend fun listTenderMotionOptions(): List<TenderMotionOptionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            TenderOptions.bindingMotionOptions(limit = MAX_OPTIONS)
        }
    }

    /**
     * V1.9.202 -- candidates for the award motion of a BEST_OFFER tender. A bidder (any bid row) gets the very refusal of `registerAwardMotion`
     * and never sees a list: the committee and motion titles are not for somebody who has a stake in the award.
     */
    override suspend fun listAwardMotionOptions(tenderId: String): List<TenderAwardMotionOptionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        val id = tenderId.toTenderUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val wallNow = OrganizationTimeZone.wallNow()
            val row = TenderStore.find(id) ?: throw NotFoundException("Not found")
            requireNotTenderBidder(tenderId = id, memberId = current.memberId)
            requireVisible(row = row, viewer = TenderDtos.viewerOf(current = current, hasBid = false), wallNow = wallNow)
            if (row[TenderTable.awardCriterion] != TenderAwardCriterion.BEST_OFFER) throw ConflictException(NOT_AVAILABLE)
            TenderOptions.awardMotionOptions(tenderRow = row, limit = MAX_OPTIONS)
        }
    }

    override suspend fun previewTenderDescription(markdown: String): String {
        val current = resolveCurrentMember(call)
        current.requireRole(*TENDER_MANAGER_ROLES.toTypedArray())
        if (!previewRateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
        val normalized = markdown.replace("\r\n", "\n")
        if (normalized.length > MAX_DESCRIPTION) throw BadRequestException("text exceeds $MAX_DESCRIPTION characters")
        return AgreementMarkdown.renderOrNull(body = normalized, maxChars = MAX_DESCRIPTION)
            ?: throw BadRequestException("text is too deeply nested")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private class Fields(
        val title: String,
        val description: String,
        val expectedResult: String,
    )

    private fun validateFields(
        title: String,
        description: String,
        descriptionFormat: TenderDescriptionFormat,
        expectedResult: String,
        deadline: LocalDateTime,
        currency: TenderCurrency,
        wallNow: LocalDateTime,
    ): Fields {
        val t = title.trim()
        // Markdown is normalised like the preview does (\r\n -> \n) BEFORE the length check, so what was previewed is what is stored.
        val d = description.replace("\r\n", "\n").trim()
        val e = expectedResult.trim()
        if (t.isEmpty() || t.length > MAX_TITLE) throw ConflictException("The title must have 1 to $MAX_TITLE characters")
        if (d.isEmpty() || d.length > MAX_DESCRIPTION) throw ConflictException("The description must have 1 to $MAX_DESCRIPTION characters")
        if (e.isEmpty() ||
            e.length > MAX_EXPECTED_RESULT
        ) {
            throw ConflictException("The expected result must have 1 to $MAX_EXPECTED_RESULT characters")
        }
        requireRenderable(format = descriptionFormat, description = d)
        requireLtr(currency)
        if (deadline < wallNow.plusLead(MIN_DEADLINE_LEAD)) throw ConflictException("The deadline must be at least one hour in the future")
        if (deadline >
            wallNow.plusLead(MAX_DEADLINE_DAYS.days)
        ) {
            throw ConflictException("The deadline must be within $MAX_DEADLINE_DAYS days")
        }
        return Fields(title = t, description = d, expectedResult = e)
    }

    /** A MARKDOWN description that cannot be rendered (absurd nesting) is never stored or published. */
    private fun requireRenderable(
        format: TenderDescriptionFormat,
        description: String,
    ) {
        if (format == TenderDescriptionFormat.MARKDOWN &&
            AgreementMarkdown.renderOrNull(body = description, maxChars = MAX_DESCRIPTION) == null
        ) {
            throw ConflictException("The description cannot be rendered")
        }
    }

    /** EUR is reserved and unreachable in wave 1 (the database CHECK refuses it as well). */
    private fun requireLtr(currency: TenderCurrency) {
        if (currency != TenderCurrency.LTR) throw ConflictException("Only LTR is available for tenders")
    }

    /** At most two decimal places (never rounded), at least 0.01, at most the technical maximum of the column. */
    private fun validatePrice(price: BigDecimal): BigDecimal {
        if (price.stripTrailingZeros().scale() > 2) throw ConflictException("The price must have at most 2 decimal places")
        val normalized = price.setScale(2)
        if (normalized < MIN_PRICE) throw ConflictException("The price must be at least $MIN_PRICE")
        if (normalized > MAX_PRICE) throw ConflictException("The price is too large")
        return normalized
    }

    /** A tender the viewer may not see reads exactly like a missing one. */
    private fun requireVisible(
        row: ResultRow,
        viewer: TenderViewer,
        wallNow: LocalDateTime,
    ) {
        val effective = effectiveTenderStatus(stored = row[TenderTable.status], deadline = row[TenderTable.deadline], wallNow = wallNow)
        if (!tenderVisibleTo(
                viewer = viewer,
                status = effective,
                everPublished = row[TenderTable.publishedAt] != null,
            )
        ) {
            throw NotFoundException("Not found")
        }
    }

    /**
     * The number of eligible voters of the award resolution after the bidders were excluded, recomputed at award time from the same electorate
     * the quorum used (the frozen snapshot of the vote if there is one, else the live set).
     */
    private fun quorumEligibleAfterExclusion(
        resolution: ResultRow,
        motion: ResultRow,
        excluded: Set<Uuid>,
    ): Int {
        val meeting = MeetingTable.selectAll().where { MeetingTable.id eq resolution[ResolutionTable.meetingId] }.single()
        return computeQuorum(
            meetingId = resolution[ResolutionTable.meetingId],
            committeeId = motion[MotionTable.targetCommitteeId],
            scheduledDate = meeting[MeetingTable.scheduledAt].date,
            voteId = resolution[ResolutionTable.voteId],
            electionId = resolution[ResolutionTable.electionId],
            systemicConsensusId = resolution[ResolutionTable.systemicConsensusId],
            excludedMemberIds = excluded,
        ).eligibleMemberCount
    }

    private fun audit(
        current: CurrentMember,
        tenderId: Uuid,
        snapshot: TenderAuditSnapshot,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.TENDER,
            entityId = tenderId,
            action = AuditAction.UPDATE,
            before = null,
            after = Json.encodeToString(TenderAuditSnapshot.serializer(), snapshot),
        )
    }

    private fun LocalDateTime.plusLead(lead: Duration): LocalDateTime = toInstant(TimeZone.UTC).plus(lead).toLocalDateTime(TimeZone.UTC)

    private fun String.toTenderUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Not found") }

    private companion object {
        const val MAX_OPTIONS = 200
        val CANCELLABLE: Set<TenderStatus> = setOf(TenderStatus.DRAFT, TenderStatus.PUBLISHED, TenderStatus.CLOSED, TenderStatus.AWARDED)
    }
}
