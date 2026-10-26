package network.lapis.cloud.server.dashboard.sources

import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.tender.TenderStore
import network.lapis.cloud.server.tender.tenderBidderEligible
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * V1.9.224 -- tender signals. "A tender I may bid on closes within seven days and I have no bid on it" and "my bid was decided".
 * Whether the caller may bid uses [tenderBidderEligible], the definition `submitBid` and the screens use (so an LTR tender never nudges a
 * guest). Deadline is class B (organization wall-clock); the award stamp is class A.
 */
internal object TenderSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.TENDER

    private const val MAX_TENDERS = 50

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsTenders(ctx.status)) return emptyList()
        return listOfNotNull(deadlineSoon(ctx), ownBidDecided(ctx))
    }

    private fun deadlineSoon(ctx: SignalContext): DashboardSignalDto? {
        val closing =
            TenderStore
                .closingBetween(from = ctx.nowWall, until = ctx.wallPlus(7.days), limit = MAX_TENDERS)
                .filter { tenderBidderEligible(status = ctx.status, currency = it.currency) }
        if (closing.isEmpty()) return null
        val mine = TenderStore.tendersWithLiveBidOf(memberId = ctx.memberId, tenderIds = closing.map { it.id })
        val open = closing.filter { it.id !in mine }
        val soon = ctx.wallPlus(48.hours)
        return signal(
            module = moduleKey,
            reason = DashboardReasonKeys.TENDER_DEADLINE_SOON,
            count = open.size,
            priority = if (open.any { it.deadline <= soon }) DashboardPriority.URGENT else DashboardPriority.ACTION,
            objectId = open.firstOrNull()?.id,
            deadlineWall = open.minOfOrNull { it.deadline },
        )
    }

    private fun ownBidDecided(ctx: SignalContext): DashboardSignalDto? {
        val decided = TenderStore.tendersDecidedForSince(memberId = ctx.memberId, since = ctx.systemPlus((-14).days), limit = MAX_TENDERS)
        return signal(
            module = moduleKey,
            reason = DashboardReasonKeys.TENDER_OWN_BID_DECIDED,
            count = decided.size,
            priority = DashboardPriority.INFO,
            objectId = decided.firstOrNull(),
        )
    }
}
