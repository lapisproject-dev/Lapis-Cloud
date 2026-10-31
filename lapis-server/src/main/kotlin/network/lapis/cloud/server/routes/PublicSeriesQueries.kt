package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.243 -- the queries behind "one series, one entry" on the public pages. Every function needs an open `transaction`.
 * [EventIcsFeed.loadUpcomingPublicPublished] is deliberately NOT changed: the embed feed, the nav availability and the ICS feed keep
 * using it. All queries share one definition of "public and still to come" ([publicUpcoming]) and are served by
 * `idx_event_series_starts (series_id, starts_at)`; the number of statements is constant, never per series.
 *
 * **Tiebreak**: when two events of one series start at the same minute (a moved occurrence), the one with the smaller id (compared as
 * text, so H2 and PostgreSQL agree) is the representative -- never both, never none.
 */
internal object PublicSeriesQueries {
    const val OVERVIEW_MAX_ENTRIES = 50
    const val DETAIL_MAX_SIBLINGS = 50

    data class SeriesStats(
        val seriesId: Uuid,
        val upcomingCount: Int,
        val lastStartsAt: LocalDateTime,
        val rrule: String,
    )

    private fun publicUpcoming(
        visibility: Column<EventVisibility>,
        status: Column<EventStatus>,
        endsAt: Column<LocalDateTime>,
        wallNow: LocalDateTime,
    ): Op<Boolean> = (visibility eq EventVisibility.PUBLIC) and (status eq EventStatus.PUBLISHED) and (endsAt greater wallNow)

    /** Q1: single events plus, per series, its earliest still-upcoming public event; ordered by start, capped at [limit] ENTRIES. */
    fun loadOverviewRepresentatives(
        wallNow: LocalDateTime,
        limit: Int = OVERVIEW_MAX_ENTRIES,
    ): List<ResultRow> {
        val sib = EventTable.alias("sib")
        val idText = EventTable.id.castTo(VarCharColumnType())
        val sibIdText = sib[EventTable.id].castTo(VarCharColumnType())
        return EventTable
            .selectAll()
            .where {
                publicUpcoming(
                    visibility = EventTable.visibility,
                    status = EventTable.status,
                    endsAt = EventTable.endsAt,
                    wallNow = wallNow,
                ) and
                    (
                        EventTable.seriesId.isNull() or
                            notExists(
                                sib.selectAll().where {
                                    (sib[EventTable.seriesId] eq EventTable.seriesId) and
                                        publicUpcoming(
                                            visibility = sib[EventTable.visibility],
                                            status = sib[EventTable.status],
                                            endsAt = sib[EventTable.endsAt],
                                            wallNow = wallNow,
                                        ) and
                                        (
                                            (sib[EventTable.startsAt] less EventTable.startsAt) or
                                                (
                                                    (sib[EventTable.startsAt] eq EventTable.startsAt) and
                                                        (sibIdText less idText)
                                                )
                                        )
                                },
                            )
                    )
            }.orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()
    }

    /** Q2: ONE grouped statement for any number of series ids (at most 50 here): count and last start of the public upcoming events. */
    fun loadSeriesStats(
        seriesIds: Collection<Uuid>,
        wallNow: LocalDateTime,
    ): Map<Uuid, SeriesStats> {
        if (seriesIds.isEmpty()) return emptyMap()
        val countExpr = EventTable.id.count()
        val lastExpr = EventTable.startsAt.max()
        return EventTable
            .join(EventSeriesTable, JoinType.INNER, EventTable.seriesId, EventSeriesTable.id)
            .select(EventTable.seriesId, EventSeriesTable.rrule, countExpr, lastExpr)
            .where {
                (EventTable.seriesId inList seriesIds.distinct()) and
                    publicUpcoming(
                        visibility = EventTable.visibility,
                        status = EventTable.status,
                        endsAt = EventTable.endsAt,
                        wallNow = wallNow,
                    )
            }.groupBy(EventTable.seriesId, EventSeriesTable.rrule)
            .mapNotNull { row ->
                val id = row[EventTable.seriesId] ?: return@mapNotNull null
                val last = row[lastExpr] ?: return@mapNotNull null
                SeriesStats(seriesId = id, upcomingCount = row[countExpr].toInt(), lastStartsAt = last, rrule = row[EventSeriesTable.rrule])
            }.associateBy { it.seriesId }
    }

    /** C1: the other public upcoming events of a series, earliest first; one more than [DETAIL_MAX_SIBLINGS] so the caller can tell it was cut. */
    fun loadSiblings(
        seriesId: Uuid,
        excludeEventId: Uuid,
        wallNow: LocalDateTime,
    ): List<ResultRow> =
        EventTable
            .selectAll()
            .where {
                (EventTable.seriesId eq seriesId) and
                    (EventTable.id neq excludeEventId) and
                    publicUpcoming(
                        visibility = EventTable.visibility,
                        status = EventTable.status,
                        endsAt = EventTable.endsAt,
                        wallNow = wallNow,
                    )
            }.orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.id to SortOrder.ASC)
            .limit(DETAIL_MAX_SIBLINGS + 1)
            .toList()

    /** C3: which of [eventIds] are full -- same occupancy rule as `EventStore.countOccupied` (CONFIRMED, or PENDING_PAYMENT with a live hold). */
    fun loadFullEventIds(
        eventIds: Collection<Uuid>,
        now: LocalDateTime,
    ): Set<Uuid> {
        if (eventIds.isEmpty()) return emptySet()
        val countExpr = EventRegistrationTable.id.count()
        return EventRegistrationTable
            .join(EventTable, JoinType.INNER, EventRegistrationTable.eventId, EventTable.id)
            .select(EventRegistrationTable.eventId, EventTable.capacity, countExpr)
            .where {
                (EventRegistrationTable.eventId inList eventIds.distinct()) and
                    EventTable.capacity.isNotNull() and
                    (
                        (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED) or
                            (
                                (EventRegistrationTable.status eq EventRegistrationStatus.PENDING_PAYMENT) and
                                    (EventRegistrationTable.holdExpiresAt greater now)
                            )
                    )
            }.groupBy(EventRegistrationTable.eventId, EventTable.capacity)
            .mapNotNull { row ->
                val capacity = row[EventTable.capacity] ?: return@mapNotNull null
                row[EventRegistrationTable.eventId].takeIf { row[countExpr] >= capacity.toLong() }
            }.toSet()
    }
}
