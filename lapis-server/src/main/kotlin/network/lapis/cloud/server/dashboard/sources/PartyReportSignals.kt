package network.lapis.cloud.server.dashboard.sources

import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.partg.PartgVersionStore
import network.lapis.cloud.server.partg.PartgVersionView
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.PartgDeadlineLevel
import network.lapis.cloud.shared.domain.PartgVersionStatus

/**
 * V1.9.291 (R6) -- the party accountability report: the EARLIEST report year (among last year and the years of the last five that have a kept
 * version or a deadline) that is not internally final. One signal for the board and the admins of a political party, never for anyone else.
 *
 * The collector only lets digits and ISO dates through in `params`, so the status travels as a number (0 = no version, 1 = kept, 2 = declared)
 * and the deadline as an ISO date; the client chooses the words. The dashboard is cached with a TTL: the card can be a little stale after a
 * change (documented limit). No amount, name or comment ever leaves this source.
 */
internal object PartyReportSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.PARTG

    private const val LOOK_BACK_YEARS = 5
    private const val ACTION_DAYS = 30

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!ctx.flags.politicalParty || !SignalGates.isBoardOrAdmin(ctx.role)) return emptyList()
        val thisYear = ctx.today.year
        val years =
            (
                PartgVersionStore
                    .yearsWithActivity(
                        thisYear - LOOK_BACK_YEARS,
                    ).filter { it <= thisYear } + (thisYear - 1)
            ).toSortedSet()
        val statuses = PartgVersionStore.wholePartyStatuses(years)
        val year =
            years.firstOrNull { y -> PartgVersionView.yearStatus(year = y, statuses = statuses) != PartgVersionStatus.FINAL }
                ?: return emptyList()
        val status = PartgVersionView.yearStatus(year = year, statuses = statuses)
        val deadline = PartgVersionView.deadline(year = year, today = ctx.today, status = status)
        if (deadline.confirmed && deadline.level == PartgDeadlineLevel.NONE) return emptyList()
        val priority =
            when {
                deadline.daysUntil < 0 -> DashboardPriority.URGENT
                deadline.daysUntil <= ACTION_DAYS -> DashboardPriority.ACTION
                else -> DashboardPriority.INFO
            }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.PARTG_REPORT_DEADLINE,
                count = 1,
                priority = priority,
                params =
                    mapOf(
                        "year" to year.toString(),
                        "statusCode" to status.ordinal.toString(),
                        "confirmed" to if (deadline.confirmed) "1" else "0",
                        "deadline" to deadline.deadline.toString(),
                    ),
            ),
        )
    }
}
