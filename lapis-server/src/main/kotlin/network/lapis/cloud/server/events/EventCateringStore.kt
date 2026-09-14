package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventCateringOrderTable
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- reine Exposed-Datenzugriffsschicht
 * für `event_catering_order`. Öffnet, wie [EventRoomStore]/[EventStore], NIE eine eigene
 * `transaction {}` -- die Aufrufer ([CateringService]) tun das.
 */
internal object EventCateringStore {
    fun getOrderOrNull(id: Uuid): ResultRow? = EventCateringOrderTable.selectAll().where { EventCateringOrderTable.id eq id }.singleOrNull()

    fun getOrderOrThrow(id: Uuid): ResultRow = getOrderOrNull(id) ?: throw NotFoundException("EventCateringOrder $id not found")

    fun listOrders(eventId: Uuid): List<ResultRow> =
        EventCateringOrderTable
            .selectAll()
            .where { EventCateringOrderTable.eventId eq eventId }
            .orderBy(EventCateringOrderTable.createdAt, SortOrder.ASC)
            .toList()

    fun insertOrder(
        id: Uuid,
        eventId: Uuid,
        description: String,
        quantity: Int,
        allergenNotes: String?,
        createdAt: LocalDateTime,
        createdBy: Uuid,
    ) {
        EventCateringOrderTable.insert {
            it[EventCateringOrderTable.id] = id
            it[EventCateringOrderTable.eventId] = eventId
            it[EventCateringOrderTable.description] = description
            it[EventCateringOrderTable.quantity] = quantity
            it[EventCateringOrderTable.allergenNotes] = allergenNotes
            it[EventCateringOrderTable.status] = CateringOrderStatus.PLANNED
            it[EventCateringOrderTable.createdAt] = createdAt
            it[EventCateringOrderTable.createdBy] = createdBy
        }
    }

    /** Also reassigns [eventId] -- see `ICateringService.updateCateringOrder` KDoc for why moving
     * an order to a different (existing) event is a deliberately supported edit, not just a
     * validation-only field. */
    fun updateOrder(
        id: Uuid,
        eventId: Uuid,
        description: String,
        quantity: Int,
        allergenNotes: String?,
    ) {
        EventCateringOrderTable.update({ EventCateringOrderTable.id eq id }) {
            it[EventCateringOrderTable.eventId] = eventId
            it[EventCateringOrderTable.description] = description
            it[EventCateringOrderTable.quantity] = quantity
            it[EventCateringOrderTable.allergenNotes] = allergenNotes
        }
    }

    fun setStatus(
        id: Uuid,
        status: CateringOrderStatus,
    ) {
        EventCateringOrderTable.update({ EventCateringOrderTable.id eq id }) {
            it[EventCateringOrderTable.status] = status
        }
    }

    /** Echtes DELETE -- siehe `ICateringService.deleteCateringOrder` KDoc: eine Bestellposition
     * ist niemals FK-Ziel von außerhalb dieser Domäne, anders als `event_room`. */
    fun deleteOrder(id: Uuid) {
        EventCateringOrderTable.deleteWhere { EventCateringOrderTable.id eq id }
    }
}
