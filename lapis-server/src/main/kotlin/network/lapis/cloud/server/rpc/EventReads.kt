package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll

/** One published, upcoming event -- no participant list, no manager-only fields. */
internal data class UpcomingEventSummary(
    val title: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val locationText: String?,
    val onlineUrl: String?,
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
                )
            }
}
