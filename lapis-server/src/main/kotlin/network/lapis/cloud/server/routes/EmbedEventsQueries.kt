package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll

/** V1.9.82 -- queries behind the public archive feed. Must run inside the caller's (short, read-only) transaction. */
internal object EmbedEventsQueries {
    /**
     * Public + published events that are over (`endsAt <= wallNow`), newest ending first. `endsAt == wallNow` belongs HERE and not to the
     * main feed, whose predicate is `endsAt > wallNow` -- no gap and no duplicate at the boundary. Returns up to `limit + 1` rows so the
     * caller can derive `hasMore` without a COUNT. The tie-break on `id` keeps the order stable across page boundaries. Backed by
     * `idx_event_public_archive (status, visibility, ends_at, id)`.
     */
    fun loadPastPublicPublished(
        wallNow: LocalDateTime,
        limit: Int,
        offset: Long,
    ): List<ResultRow> =
        EventTable
            .selectAll()
            .where {
                (EventTable.visibility eq EventVisibility.PUBLIC) and
                    (EventTable.status eq EventStatus.PUBLISHED) and
                    (EventTable.endsAt lessEq wallNow)
            }.orderBy(EventTable.endsAt to SortOrder.DESC, EventTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .offset(offset)
            .toList()
}
