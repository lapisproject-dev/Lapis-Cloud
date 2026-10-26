package network.lapis.cloud.server.dashboard.sources

import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.days

private const val MAX_ROWS = 50

/**
 * V1.9.224 -- my next confirmed event (within 14 days). Replaces the "Naechste Veranstaltung" card of the old dashboard. `event.starts_at`
 * is class B (typed in the event form): the date is shown as it is, never converted.
 */
internal object EventSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.EVENTS

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsMembershipSection(ctx.status)) return emptyList()
        val next =
            EventRegistrationTable
                .innerJoin(EventTable)
                .select(EventTable.id, EventTable.startsAt)
                .where {
                    (EventRegistrationTable.memberId eq ctx.memberId) and
                        (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED) and
                        (EventTable.status eq EventStatus.PUBLISHED) and
                        (EventTable.endsAt greaterEq ctx.nowWall) and
                        (EventTable.startsAt lessEq ctx.wallPlus(14.days))
                }.orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.id to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
        return listOfNotNull(
            next?.let {
                signal(
                    module = moduleKey,
                    reason = DashboardReasonKeys.EVENTS_NEXT_REGISTERED,
                    count = 1,
                    priority = DashboardPriority.INFO,
                    params = mapOf("date" to it[EventTable.startsAt].date.toString()),
                    objectId = it[EventTable.id],
                    deadlineWall = it[EventTable.startsAt],
                )
            },
        )
    }
}

/**
 * V1.9.224 -- my own contributions. Overdue / in dunning / returned is urgent; open is an action, but NOT for SEPA direct debit (there the
 * member has nothing to do). `due_date` is a calendar date (class D).
 */
internal object ContributionSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.CONTRIBUTIONS

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsMembershipSection(ctx.status)) return emptyList()
        val overdue =
            ContributionTable
                .select(ContributionTable.id)
                .where {
                    (ContributionTable.memberId eq ctx.memberId) and
                        (
                            ContributionTable.status inList
                                listOf(ContributionStatus.OVERDUE, ContributionStatus.IN_DUNNING, ContributionStatus.RETURNED)
                        )
                }.orderBy(ContributionTable.id to SortOrder.ASC)
                .limit(MAX_ROWS)
                .map { it[ContributionTable.id] }
        val open =
            ContributionTable
                .select(ContributionTable.id, ContributionTable.dueDate)
                .where {
                    (ContributionTable.memberId eq ctx.memberId) and
                        (ContributionTable.status eq ContributionStatus.OPEN) and
                        (ContributionTable.paymentMethod neq ContributionPaymentMethod.SEPA_DEBIT)
                }.orderBy(ContributionTable.dueDate to SortOrder.ASC, ContributionTable.id to SortOrder.ASC)
                .limit(MAX_ROWS)
                .toList()
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.CONTRIBUTIONS_OVERDUE,
                count = overdue.size,
                priority = DashboardPriority.URGENT,
                objectId = overdue.firstOrNull(),
            ),
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.CONTRIBUTIONS_OPEN,
                count = open.size,
                priority = DashboardPriority.ACTION,
                params = open.firstOrNull()?.let { mapOf("date" to it[ContributionTable.dueDate].toString()) } ?: emptyMap(),
                objectId = open.firstOrNull()?.get(ContributionTable.id),
            ),
        )
    }
}

/** V1.9.224 -- one of MY travel expense reports was approved, rejected or paid in the last 14 days (class A stamps). */
internal object TravelExpenseSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.TRAVEL

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsMembershipSection(ctx.status)) return emptyList()
        val since = ctx.systemPlus((-14).days)
        val changed =
            TravelExpenseReportTable
                .select(TravelExpenseReportTable.id)
                .where {
                    (TravelExpenseReportTable.subjectMemberId eq ctx.memberId) and
                        (
                            (
                                (TravelExpenseReportTable.status eq TravelExpenseReportStatus.EXECUTED) and
                                    (TravelExpenseReportTable.executedAt greaterEq since)
                            ) or
                                (
                                    (
                                        TravelExpenseReportTable.status inList
                                            listOf(TravelExpenseReportStatus.APPROVED, TravelExpenseReportStatus.REJECTED)
                                    ) and (TravelExpenseReportTable.decidedAt greaterEq since)
                                )
                        )
                }.orderBy(TravelExpenseReportTable.id to SortOrder.ASC)
                .limit(MAX_ROWS)
                .map { it[TravelExpenseReportTable.id] }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.TRAVEL_STATUS_CHANGED,
                count = changed.size,
                priority = DashboardPriority.INFO,
                objectId = changed.firstOrNull(),
            ),
        )
    }
}

/** V1.9.224 -- drafts an agent left for me that wait for my release. Only while the instance has MCP on (like the menu entry). */
internal object AiDraftSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.AI

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsAiDrafts(status = ctx.status, mcpEnabled = ctx.flags.mcpEnabled)) return emptyList()
        val waiting =
            McpPostDraftTable
                .select(McpPostDraftTable.id)
                .where { (McpPostDraftTable.memberId eq ctx.memberId) and (McpPostDraftTable.status eq McpPostDraftStatus.OPEN) }
                .orderBy(McpPostDraftTable.id to SortOrder.ASC)
                .limit(MAX_ROWS)
                .map { it[McpPostDraftTable.id] }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.AI_DRAFTS_WAITING,
                count = waiting.size,
                priority = DashboardPriority.INFO,
                objectId = waiting.firstOrNull(),
            ),
        )
    }
}

/**
 * V1.9.224 -- BOARD and ADMIN: the number of applications to decide. A bare COUNT, never an id (`objectId` is always `null`: an id would
 * be the id of another person).
 */
internal object ApplicationSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.APPLICATIONS

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.isBoardOrAdmin(ctx.role)) return emptyList()
        val open =
            MemberTable
                .selectAll()
                .where { MemberTable.status eq MemberStatus.APPLICATION }
                .count()
                .toInt()
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.APPLICATIONS_OPEN,
                count = open,
                priority = DashboardPriority.ACTION,
            ),
        )
    }
}

/**
 * V1.9.224 -- TREASURER, BOARD, ADMIN, with the dunning module switched on: how many contributions are overdue and not yet in a dunning
 * case. Deliberately NOT "dunning steps due": that is computed per row in Kotlin (`DunningService.listDunningCases`) and is not a cheap
 * query. A bare COUNT on `contribution.status` (index), never an id.
 */
internal object TreasurySignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.TREASURY

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!ctx.flags.dunningEnabled || !SignalGates.mayReadDunning(ctx.role)) return emptyList()
        val overdue =
            ContributionTable
                .selectAll()
                .where { ContributionTable.status eq ContributionStatus.OVERDUE }
                .count()
                .toInt()
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING,
                count = overdue,
                priority = DashboardPriority.ACTION,
            ),
        )
    }
}
