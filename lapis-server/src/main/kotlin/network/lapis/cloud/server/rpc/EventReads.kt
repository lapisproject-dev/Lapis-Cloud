package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One published, upcoming event -- no participant list, no manager-only fields. */
internal data class UpcomingEventSummary(
    val title: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val locationText: String?,
    val onlineUrl: String?,
    /**
     * Welle V1.8.2b -- exposed so `list_upcoming_events` can advertise `feeAmount`/`requiresPayment`
     * (see `mcp.tools.ListUpcomingEventsTool`): an agent can filter out fee-bearing events BEFORE
     * calling `register_for_event`, and `register_for_event`'s own `event_requires_payment`
     * rejection reveals nothing this list did not already show -- closing the oracle it would
     * otherwise open (see `RegisterForEventTool` KDoc "No payment through MCP").
     */
    val feeAmount: BigDecimal,
)

/**
 * Welle V1.8.2 -- the caller's own, already-existing registration status for one event. Used
 * exclusively by `mcp.tools.RegisterForEventTool` to fill in `status`/`waitlistPosition` on an
 * `AlreadyRegistered` outcome (`EventRegistrationResult.AlreadyRegistered` itself carries neither
 * -- see that `data object`'s own KDoc). Never exposes another participant's row.
 */
internal data class OwnRegistrationSummary(
    val status: EventRegistrationStatus,
    val waitlistPosition: Int?,
)

/**
 * Welle V1.8.2b -- title + fee of one `PUBLISHED` event, read in a single lookup. `null` means
 * "does not exist OR is not `PUBLISHED`" -- deliberately indistinguishable, so
 * `mcp.tools.RegisterForEventTool` can map it to the SAME generic `Forbidden` every other
 * "cannot register" outcome already uses, never a distinct "event not found" oracle.
 */
internal data class PublishedEventFee(
    val title: String,
    val feeAmount: BigDecimal,
)

/**
 * Welle V1.8.1 "MCP-Server" -- `status = PUBLISHED` is hard-wired here, never the manager view
 * [network.lapis.cloud.server.rpc.EventService.listEvents] can produce for a BOARD/ADMIN caller
 * (`EVENT_MANAGE_ROLES`). **Must run inside an already-open `transaction {}`**, same contract as
 * [MemberReads].
 */
internal object EventReads {
    fun listPublishedUpcoming(
        now: LocalDateTime,
        limit: Int,
    ): List<UpcomingEventSummary> =
        EventTable
            .selectAll()
            .where { (EventTable.status eq EventStatus.PUBLISHED) and (EventTable.endsAt greater now) }
            .orderBy(EventTable.startsAt, SortOrder.ASC)
            .limit(limit)
            .map {
                UpcomingEventSummary(
                    title = it[EventTable.title],
                    startsAt = it[EventTable.startsAt],
                    endsAt = it[EventTable.endsAt],
                    locationText = it[EventTable.locationText],
                    onlineUrl = it[EventTable.onlineUrl],
                    feeAmount = it[EventTable.feeAmount],
                )
            }

    /**
     * Welle V1.8.2b -- used exclusively by `mcp.tools.RegisterForEventTool` to reject a fee-bearing
     * event BEFORE `EventRegistrationSubmission.submit` ever runs, see that tool's own KDoc.
     * `status = PUBLISHED` hard-wired, same posture as [listPublishedUpcoming]/[getTitle].
     */
    fun findPublishedEventFee(eventId: Uuid): PublishedEventFee? =
        EventTable
            .selectAll()
            .where { (EventTable.id eq eventId) and (EventTable.status eq EventStatus.PUBLISHED) }
            .singleOrNull()
            ?.let { PublishedEventFee(title = it[EventTable.title], feeAmount = it[EventTable.feeAmount]) }

    /** Welle V1.8.2 -- `null` if the event does not exist. Title only, no other event fields. */
    fun getTitle(eventId: Uuid): String? =
        EventTable
            .selectAll()
            .where { EventTable.id eq eventId }
            .singleOrNull()
            ?.get(EventTable.title)

    /**
     * Welle V1.8.2 -- the caller's own active (non-CANCELLED, non-EXPIRED) registration for this
     * event, if any. `null` when the caller has none -- deliberately NOT reachable for anyone
     * else's `memberId` (the caller passes their own, from `McpPrincipal.memberId`, never an
     * argument).
     */
    fun findOwnRegistrationStatus(
        eventId: Uuid,
        memberId: Uuid,
    ): OwnRegistrationSummary? =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and
                    (EventRegistrationTable.memberId eq memberId) and
                    (EventRegistrationTable.status neq EventRegistrationStatus.CANCELLED) and
                    (EventRegistrationTable.status neq EventRegistrationStatus.EXPIRED)
            }.singleOrNull()
            ?.let {
                OwnRegistrationSummary(
                    status = it[EventRegistrationTable.status],
                    waitlistPosition = it[EventRegistrationTable.waitlistPosition],
                )
            }
}
