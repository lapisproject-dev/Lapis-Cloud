package network.lapis.cloud.server.translation

import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The ONE predicate for "this event may leave the server for a translation": `visibility = PUBLIC` and a status that is shown publicly
 * (`PUBLISHED`, `CANCELLED` -- the public detail page shows a cancelled event), never `DRAFT`.
 */
internal object EventTranslationEligibility {
    fun isPublic(row: ResultRow): Boolean = isPublic(visibility = row[EventTable.visibility], status = row[EventTable.status])

    fun isPublic(
        visibility: EventVisibility,
        status: EventStatus,
    ): Boolean = visibility == EventVisibility.PUBLIC && status != EventStatus.DRAFT
}
