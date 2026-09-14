package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- reine Exposed-Datenzugriffsschicht für
 * `event_room`. Öffnet, wie [EventStore], NIE eine eigene `transaction {}` -- die Aufrufer
 * ([EventRoomService]/[EventRoomCollisionGuard]) tun das.
 */
internal object EventRoomStore {
    fun getRoomOrNull(id: Uuid): ResultRow? = EventRoomTable.selectAll().where { EventRoomTable.id eq id }.singleOrNull()

    fun getRoomOrThrow(id: Uuid): ResultRow = getRoomOrNull(id) ?: throw NotFoundException("EventRoom $id not found")

    /** `FOR UPDATE` row lock on `event_room` -- see [EventRoomCollisionGuard] KDoc for why every
     * assignment-collision check must hold this lock (mirrors [EventStore.lockEventForUpdate]'s
     * own "lock the row whose invariant is being protected" posture). */
    fun lockRoomForUpdate(id: Uuid): ResultRow? =
        EventRoomTable
            .selectAll()
            .where { EventRoomTable.id eq id }
            .forUpdate()
            .singleOrNull()

    fun nameTaken(
        name: String,
        excludingId: Uuid?,
    ): Boolean {
        val condition: Op<Boolean> =
            if (excludingId != null) {
                (EventRoomTable.name eq name) and (EventRoomTable.id neq excludingId)
            } else {
                EventRoomTable.name eq name
            }
        return EventRoomTable.selectAll().where { condition }.any()
    }

    fun listRooms(includeInactive: Boolean): List<ResultRow> {
        val query =
            if (includeInactive) {
                EventRoomTable.selectAll()
            } else {
                EventRoomTable.selectAll().where { EventRoomTable.status eq EventRoomStatus.ACTIVE }
            }
        return query.orderBy(EventRoomTable.name, SortOrder.ASC).toList()
    }

    fun insertRoom(
        id: Uuid,
        name: String,
        capacity: Int?,
        equipmentTagsCsv: String,
        createdAt: LocalDateTime,
        createdBy: Uuid,
    ) {
        EventRoomTable.insert {
            it[EventRoomTable.id] = id
            it[EventRoomTable.name] = name
            it[EventRoomTable.capacity] = capacity
            it[EventRoomTable.equipmentTags] = equipmentTagsCsv
            it[EventRoomTable.status] = EventRoomStatus.ACTIVE
            it[EventRoomTable.createdAt] = createdAt
            it[EventRoomTable.createdBy] = createdBy
        }
    }

    fun updateRoom(
        id: Uuid,
        name: String,
        capacity: Int?,
        equipmentTagsCsv: String,
    ) {
        EventRoomTable.update({ EventRoomTable.id eq id }) {
            it[EventRoomTable.name] = name
            it[EventRoomTable.capacity] = capacity
            it[EventRoomTable.equipmentTags] = equipmentTagsCsv
        }
    }

    fun setStatus(
        id: Uuid,
        status: EventRoomStatus,
    ) {
        EventRoomTable.update({ EventRoomTable.id eq id }) {
            it[EventRoomTable.status] = status
        }
    }

    /**
     * The room's name, for [EventDto.roomName] denormalization -- `null` if [roomId] is `null` or
     * (should never happen given the FK, but defensive) does not resolve.
     */
    fun roomNameOrNull(roomId: Uuid?): String? = roomId?.let { getRoomOrNull(it)?.get(EventRoomTable.name) }

    /**
     * The first `event` row (excluding [excludingEventId], if given, and CANCELLED events) whose
     * `[startsAt, endsAt)` window overlaps `[startsAt, endsAt)` on [roomId] -- edge-to-edge
     * (`existing.endsAt == startsAt` or `existing.startsAt == endsAt`) is deliberately NOT a
     * conflict, see [EventRoomCollisionGuard] KDoc.
     */
    fun findOverlapping(
        roomId: Uuid,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        excludingEventId: Uuid?,
    ): ResultRow? {
        var condition: Op<Boolean> =
            (EventTable.roomId eq roomId) and
                (EventTable.status neq EventStatus.CANCELLED) and
                (EventTable.startsAt less endsAt) and
                (EventTable.endsAt greater startsAt)
        if (excludingEventId != null) condition = condition and (EventTable.id neq excludingEventId)
        return EventTable.selectAll().where { condition }.firstOrNull()
    }
}
