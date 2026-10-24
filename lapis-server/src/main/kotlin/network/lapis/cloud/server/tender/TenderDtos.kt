package network.lapis.cloud.server.tender

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementMarkdown
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.TenderAcceptanceDto
import network.lapis.cloud.shared.domain.TenderAwardPublicDto
import network.lapis.cloud.shared.domain.TenderAwardReadiness
import network.lapis.cloud.shared.domain.TenderBidDto
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidView
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Upper bound of a description (characters, after trimming); also the render cap of the Markdown pipeline. */
internal const val TENDER_MAX_DESCRIPTION = 10_000

/**
 * V1.9.201 -- builds every tender DTO a reader receives. All visibility decisions are taken by [TenderVisibilityPolicy]'s functions; this file only
 * applies them and must never decide on a mode, a deadline or a role on its own. Must run inside an open `transaction {}`; never writes.
 */
internal object TenderDtos {
    /** The text a viewer outside the organisation gets instead of the binding resolution (a friend or guest does not see internal governance). */
    private const val HIDDEN = ""

    fun viewerOf(
        current: CurrentMember,
        hasBid: Boolean,
    ): TenderViewer = TenderViewer(memberId = current.memberId, role = current.role, status = current.status, hasBid = hasBid)

    /** Summaries of the tenders the viewer may see, in the order of [rows]. A tender the viewer may not see is simply absent. */
    fun summaries(
        rows: List<ResultRow>,
        current: CurrentMember,
        wallNow: LocalDateTime,
    ): List<TenderSummaryDto> {
        val ids = rows.map { it[TenderTable.id] }
        val mine = TenderStore.bidStatusesOf(tenderIds = ids, memberId = current.memberId)
        val counts = TenderStore.countedBidsByTender(ids)
        val awards = TenderStore.awardsByTender(ids)
        val winnerBids = TenderStore.bidsByIds(awards.values.map { it[TenderAwardTable.bidId] })
        val names = displayNames(winnerBids.values.map { it[TenderBidTable.bidderMemberId] })
        return rows.mapNotNull { row ->
            val id = row[TenderTable.id]
            val viewer = viewerOf(current = current, hasBid = id in mine)
            val effective = effectiveTenderStatus(stored = row[TenderTable.status], deadline = row[TenderTable.deadline], wallNow = wallNow)
            if (!tenderVisibleTo(
                    viewer = viewer,
                    status = effective,
                    everPublished = row[TenderTable.publishedAt] != null,
                )
            ) {
                return@mapNotNull null
            }
            summary(
                row = row,
                viewer = viewer,
                effective = effective,
                wallNow = wallNow,
                counted = counts[id] ?: 0,
                myStatus = mine[id],
                award = awards[id],
                winnerBid = awards[id]?.let { winnerBids[it[TenderAwardTable.bidId]] },
                names = names,
            )
        }
    }

    /** The detail of one tender; an invisible tender reads exactly like a missing one. */
    fun detail(
        row: ResultRow,
        current: CurrentMember,
        wallNow: LocalDateTime,
    ): TenderDetailDto {
        val id = row[TenderTable.id]
        val bids = TenderStore.bids(id)
        val ownRow = bids.firstOrNull { it[TenderBidTable.bidderMemberId] == current.memberId }
        val viewer = viewerOf(current = current, hasBid = ownRow != null)
        val effective = effectiveTenderStatus(stored = row[TenderTable.status], deadline = row[TenderTable.deadline], wallNow = wallNow)
        if (!tenderVisibleTo(
                viewer = viewer,
                status = effective,
                everPublished = row[TenderTable.publishedAt] != null,
            )
        ) {
            throw NotFoundException("Not found")
        }

        val revealed = revealedOf(row = row, wallNow = wallNow)
        val visibility = row[TenderTable.bidVisibility]
        val view = bidViewFor(viewer = viewer, visibility = visibility, revealed = revealed)
        val countVisible = bidCountVisible(viewer = viewer, visibility = visibility, revealed = revealed)

        val award = TenderStore.award(id)
        val winnerBid = award?.let { TenderStore.bidsByIds(listOf(it[TenderAwardTable.bidId])).values.firstOrNull() }
        val shownBids =
            when (view) {
                BidView.NONE -> emptyList()
                BidView.OWN -> listOfNotNull(ownRow)
                BidView.ALL -> bids.filter { it[TenderBidTable.status] != TenderBidStatus.WITHDRAWN || it === ownRow }
            }
        val names =
            displayNames(
                shownBids.map { it[TenderBidTable.bidderMemberId] } + listOfNotNull(winnerBid?.get(TenderBidTable.bidderMemberId)),
            )
        val counted = bids.count { it[TenderBidTable.status] != TenderBidStatus.WITHDRAWN }
        val summary =
            summary(
                row = row,
                viewer = viewer,
                effective = effective,
                wallNow = wallNow,
                counted = if (countVisible) counted else 0,
                myStatus = ownRow?.get(TenderBidTable.status),
                award = award,
                winnerBid = winnerBid,
                names = names,
            )
        val organisationMember = current.status in MemberStatusSets.ORGANIZATION_MEMBER
        val readView = current.role in TENDER_READ_ROLES && organisationMember
        val canManage = current.role in TENDER_MANAGER_ROLES && organisationMember
        val isWinner = winnerBid != null && winnerBid[TenderBidTable.bidderMemberId] == current.memberId
        val bindingId = row[TenderTable.bindingMotionId]
        val conflicted = ownRow != null
        val descriptionFormat = row[TenderTable.descriptionFormat]

        // Award truth for the board (V1.9.202): only a non-conflicted manager, only while the tender is effectively CLOSED. Every other viewer
        // gets the neutral defaults, so a bidder learns nothing about the state of the decision from these fields.
        val awardView = canManage && !conflicted && effective == TenderStatus.CLOSED
        val bidderStatuses = if (awardView) memberStatuses(bids.map { it[TenderBidTable.bidderMemberId] }) else emptyMap()
        val awardableIds =
            if (awardView) {
                TenderAwardRules.awardableBidIds(
                    tenderRow = row,
                    bids = bids,
                    statuses = bidderStatuses,
                )
            } else {
                emptySet()
            }
        val awardMotionRow =
            row[TenderTable.awardMotionId]?.takeIf { awardView }?.let {
                MotionTable
                    .selectAll()
                    .where { MotionTable.id eq it }
                    .singleOrNull()
            }
        val awardResolutionRow =
            awardMotionRow
                ?.get(
                    MotionTable.resolutionId,
                )?.let { ResolutionTable.selectAll().where { ResolutionTable.id eq it }.singleOrNull() }
        val readiness: TenderAwardReadiness? =
            if (awardView) {
                TenderAwardRules.awardReadiness(tenderRow = row, motionRow = awardMotionRow, resolutionRow = awardResolutionRow)
            } else {
                null
            }
        val deadlineLeft = minutesUntil(deadline = row[TenderTable.deadline], wallNow = wallNow)
        return TenderDetailDto(
            tender = summary,
            description = row[TenderTable.description],
            expectedResult = row[TenderTable.expectedResult],
            bindingMotionTitle = if (organisationMember) motionTitle(bindingId) else HIDDEN,
            awardMotionId = if (readView) row[TenderTable.awardMotionId]?.toString() else null,
            bids =
                shownBids.map {
                    bidDto(
                        row = it,
                        names = names,
                        mine = it[TenderBidTable.bidderMemberId] == current.memberId,
                        awardable = it[TenderBidTable.id] in awardableIds,
                    )
                },
            myBid = ownRow?.let { bidDto(row = it, names = displayNames(listOf(current.memberId)), mine = true) },
            acceptances =
                if (readView || isWinner) {
                    TenderStore.acceptances(id).map {
                        TenderAcceptanceDto(
                            decision = it[TenderAcceptanceTable.decision],
                            reason = it[TenderAcceptanceTable.reason],
                            decidedAt = it[TenderAcceptanceTable.decidedAt],
                        )
                    }
                } else {
                    emptyList()
                },
            viewerIsConflicted = conflicted,
            canManage = canManage,
            descriptionFormat = descriptionFormat,
            descriptionHtml =
                if (descriptionFormat == TenderDescriptionFormat.MARKDOWN) {
                    AgreementMarkdown.renderOrNull(body = row[TenderTable.description], maxChars = TENDER_MAX_DESCRIPTION)
                } else {
                    null
                },
            bidBlockedReason =
                tenderBidBlockedReason(
                    status = current.status,
                    currency = row[TenderTable.currency],
                    effective = effective,
                    hasBid = conflicted,
                    bidCount = bids.size,
                    maxBids = TenderStore.MAX_BIDS_PER_TENDER,
                ),
            bidView =
                when (view) {
                    BidView.NONE -> TenderBidView.NONE
                    BidView.OWN -> TenderBidView.OWN
                    BidView.ALL -> TenderBidView.ALL
                },
            minutesUntilDeadline = deadlineLeft,
            cancelReason = row[TenderTable.cancelReason],
            awardMotionTitle = if (readView) row[TenderTable.awardMotionId]?.let { motionTitle(it) } else null,
            awardReadiness = readiness,
            awardJustificationRequired =
                awardView && TenderAwardRules.justificationRequired(tenderRow = row, bids = bids, statuses = bidderStatuses),
        )
    }

    private fun motionTitle(motionId: Uuid): String =
        MotionTable
            .select(MotionTable.title)
            .where { MotionTable.id eq motionId }
            .singleOrNull()
            ?.get(MotionTable.title)
            .orEmpty()

    /** Whole minutes from [wallNow] to [deadline] (real elapsed time, DST-safe); `null` once the deadline has been reached. */
    private fun minutesUntil(
        deadline: LocalDateTime,
        wallNow: LocalDateTime,
    ): Long? {
        if (wallNow >= deadline) return null
        val zone = OrganizationTimeZone.current()
        return (
            ServerClock.wallToInstant(
                wall = deadline,
                orgZone = zone,
            ) - ServerClock.wallToInstant(wall = wallNow, orgZone = zone)
        ).inWholeMinutes
    }

    /** Current (unlocked) member statuses; read-only, for the display marks only. The enforcing path locks (see `TenderService.awardTender`). */
    fun memberStatuses(memberIds: Collection<Uuid>): Map<Uuid, MemberStatus> {
        val distinct = memberIds.toSet().toList()
        if (distinct.isEmpty()) return emptyMap()
        return MemberTable
            .select(MemberTable.id, MemberTable.status)
            .where { MemberTable.id inList distinct }
            .associate { it[MemberTable.id] to it[MemberTable.status] }
    }

    fun bidDto(
        row: ResultRow,
        names: Map<Uuid, String>,
        mine: Boolean,
        awardable: Boolean = false,
    ): TenderBidDto =
        TenderBidDto(
            id = row[TenderBidTable.id].toString(),
            tenderId = row[TenderBidTable.tenderId].toString(),
            bidderDisplayName = names[row[TenderBidTable.bidderMemberId]],
            price = row[TenderBidTable.price],
            currency = row[TenderBidTable.currency],
            text = row[TenderBidTable.text],
            status = row[TenderBidTable.status],
            submittedAt = row[TenderBidTable.submittedAt],
            priceSetAt = row[TenderBidTable.priceSetAt],
            isMine = mine,
            awardable = awardable,
        )

    fun displayNames(memberIds: Collection<Uuid>): Map<Uuid, String> {
        val distinct = memberIds.toSet().toList()
        if (distinct.isEmpty()) return emptyMap()
        return MemberTable
            .select(MemberTable.id, MemberTable.displayName)
            .where { MemberTable.id inList distinct }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }
    }

    /** `deadline <= wallNow` and not cancelled before the deadline; the stored cancellation instant (class A) is re-expressed as wall-clock first. */
    fun revealedOf(
        row: ResultRow,
        wallNow: LocalDateTime,
    ): Boolean {
        val cancelledAtWall = row[TenderTable.cancelledAt]?.let { OrganizationTimeZone.wallNowOf(it) }
        return tenderBidsRevealed(deadline = row[TenderTable.deadline], wallNow = wallNow, cancelledAtWall = cancelledAtWall)
    }

    private fun summary(
        row: ResultRow,
        viewer: TenderViewer,
        effective: TenderStatus,
        wallNow: LocalDateTime,
        counted: Int,
        myStatus: TenderBidStatus?,
        award: ResultRow?,
        winnerBid: ResultRow?,
        names: Map<Uuid, String>,
    ): TenderSummaryDto {
        val visibility = row[TenderTable.bidVisibility]
        val countVisible = bidCountVisible(viewer = viewer, visibility = visibility, revealed = revealedOf(row = row, wallNow = wallNow))
        val organisationMember = viewer.status in MemberStatusSets.ORGANIZATION_MEMBER
        val publicAward =
            if (award != null && winnerBid != null && effective in setOf(TenderStatus.AWARDED, TenderStatus.COMPLETED)) {
                TenderAwardPublicDto(
                    winnerDisplayName = names[winnerBid[TenderBidTable.bidderMemberId]].orEmpty(),
                    price = winnerBid[TenderBidTable.price],
                    currency = winnerBid[TenderBidTable.currency],
                    awardedAt = award[TenderAwardTable.awardedAt],
                )
            } else {
                null
            }
        return TenderSummaryDto(
            id = row[TenderTable.id].toString(),
            title = row[TenderTable.title],
            status = effective,
            deadline = row[TenderTable.deadline],
            awardCriterion = row[TenderTable.awardCriterion],
            currency = row[TenderTable.currency],
            bidVisibility = visibility,
            bindingMotionId = if (organisationMember) row[TenderTable.bindingMotionId].toString() else HIDDEN,
            visibleBidCount = if (countVisible) counted else null,
            myBidStatus = myStatus,
            award = publicAward,
        )
    }

    /** Used by `listMyBids`: the caller's own bids are their own data, whatever the tender's visibility mode says. */
    fun ownBids(
        memberId: Uuid,
        name: String,
    ): List<TenderBidDto> = TenderStore.bidsOf(memberId).map { bidDto(row = it, names = mapOf(memberId to name), mine = true) }
}
