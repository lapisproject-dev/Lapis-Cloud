package network.lapis.cloud.server.events

import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq

/** V1.9.295 -- the ONE rule "which event may a caller WITHOUT manage scope see": PUBLISHED only. Extracted from EventService.getEvent. */
internal object EventMemberVisibility {
    fun visibleWithoutManageScope(status: EventStatus): Boolean = status == EventStatus.PUBLISHED

    fun visibleWithoutManageScopePredicate(): Op<Boolean> = EventTable.status eq EventStatus.PUBLISHED
}
