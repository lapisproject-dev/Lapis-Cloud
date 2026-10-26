package network.lapis.cloud.server.dashboard.sources

import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration.Companion.days

/** V1.9.224 -- "my crowdfunding project was approved or rejected" (the last 14 days). Own projects only; `reviewed_at` is class A. */
internal object CrowdfundingSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.CROWDFUNDING

    private const val MAX_PROJECTS = 50

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsMemberOnlyEconomy(ctx.status)) return emptyList()
        val since = ctx.systemPlus((-14).days)
        val decided =
            CrowdfundingProjectTable
                .select(CrowdfundingProjectTable.id)
                .where {
                    (CrowdfundingProjectTable.submitterMemberId eq ctx.memberId) and
                        (
                            CrowdfundingProjectTable.status inList
                                listOf(CrowdfundingProjectStatus.APPROVED, CrowdfundingProjectStatus.REJECTED)
                        ) and
                        (CrowdfundingProjectTable.reviewedAt greaterEq since)
                }.orderBy(CrowdfundingProjectTable.id to SortOrder.ASC)
                .limit(MAX_PROJECTS)
                .map { it[CrowdfundingProjectTable.id] }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.CROWDFUNDING_OWN_DECIDED,
                count = decided.size,
                priority = DashboardPriority.INFO,
                objectId = decided.firstOrNull(),
            ),
        )
    }
}
