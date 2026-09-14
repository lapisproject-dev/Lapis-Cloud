package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.events.EventRoomStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomInput
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

internal val EVENT_ROOM_MANAGE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** Longest realistic equipment tag list stays well under this -- defensive cap against an
 * accidental multi-megabyte paste, same posture every other free-text input field in this
 * codebase enforces (see e.g. `CrmContactPolicy` for the precedent). */
private const val MAX_EQUIPMENT_TAGS = 50
private const val MAX_TAG_LENGTH = 100

/** Mirrors `event_room.equipment_tags VARCHAR(1000)` (`V36__event_rooms.sql`) -- the actual
 * write path is the COMMA-JOINED CSV string, not the individual tags, so `MAX_EQUIPMENT_TAGS`
 * (50) x `MAX_TAG_LENGTH` (100) alone is NOT sufficient: 50 tags of 100 chars each plus 49
 * separators is ~5049 chars, which passes both per-tag checks below but overflows this column
 * and would otherwise surface as a raw, unhandled `ExposedSQLException` instead of a clean 400 --
 * same "friendly first gate, DB is the backstop" posture `CrmContactPolicy.requireMaxLength`
 * documents. */
private const val MAX_EQUIPMENT_TAGS_CSV_LENGTH = 1000

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- implements [IEventRoomService]. Room
 * master-data CRUD only; the actual room-to-event assignment (and its collision check) lives in
 * `EventService.createEvent`/`.updateEvent` via `EventRoomCollisionGuard` -- see that interface's
 * own KDoc for why the two are deliberately separate services.
 */
class EventRoomService(
    private val call: ApplicationCall,
) : IEventRoomService {
    override suspend fun listRooms(includeInactive: Boolean): List<EventRoomDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_ROOM_MANAGE_ROLES)
        return transaction {
            EventRoomStore.listRooms(includeInactive = includeInactive).map { it.toEventRoomDto() }
        }
    }

    override suspend fun createRoom(input: EventRoomInput): EventRoomDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_ROOM_MANAGE_ROLES)
        val name = requireValidName(input.name)
        val tagsCsv = requireValidEquipmentTags(input.equipmentTags)
        requireValidCapacity(input.capacity)
        return transaction {
            if (EventRoomStore.nameTaken(name = name, excludingId = null)) {
                throw ConflictException("Ein Raum mit dem Namen \"$name\" existiert bereits.")
            }
            val id = Uuid.random()
            EventRoomStore.insertRoom(
                id = id,
                name = name,
                capacity = input.capacity,
                equipmentTagsCsv = tagsCsv,
                createdAt = DbClock.nowLocalDateTime(),
                createdBy = current.memberId,
            )
            EventRoomStore.getRoomOrThrow(id).toEventRoomDto()
        }
    }

    override suspend fun updateRoom(
        id: String,
        input: EventRoomInput,
    ): EventRoomDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_ROOM_MANAGE_ROLES)
        val name = requireValidName(input.name)
        val tagsCsv = requireValidEquipmentTags(input.equipmentTags)
        requireValidCapacity(input.capacity)
        val roomId = id.toEventRoomUuid()
        return transaction {
            EventRoomStore.getRoomOrThrow(roomId)
            if (EventRoomStore.nameTaken(name = name, excludingId = roomId)) {
                throw ConflictException("Ein Raum mit dem Namen \"$name\" existiert bereits.")
            }
            EventRoomStore.updateRoom(id = roomId, name = name, capacity = input.capacity, equipmentTagsCsv = tagsCsv)
            EventRoomStore.getRoomOrThrow(roomId).toEventRoomDto()
        }
    }

    override suspend fun deactivateRoom(id: String): EventRoomDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_ROOM_MANAGE_ROLES)
        val roomId = id.toEventRoomUuid()
        return transaction {
            EventRoomStore.getRoomOrThrow(roomId)
            EventRoomStore.setStatus(id = roomId, status = EventRoomStatus.INACTIVE)
            EventRoomStore.getRoomOrThrow(roomId).toEventRoomDto()
        }
    }

    override suspend fun activateRoom(id: String): EventRoomDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_ROOM_MANAGE_ROLES)
        val roomId = id.toEventRoomUuid()
        return transaction {
            EventRoomStore.getRoomOrThrow(roomId)
            EventRoomStore.setStatus(id = roomId, status = EventRoomStatus.ACTIVE)
            EventRoomStore.getRoomOrThrow(roomId).toEventRoomDto()
        }
    }

    private fun requireValidName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) throw BadRequestException("Der Raumname darf nicht leer sein.")
        return trimmed
    }

    private fun requireValidCapacity(capacity: Int?) {
        if (capacity != null && capacity <= 0) throw BadRequestException("Die Kapazität muss größer als 0 sein.")
    }

    /** Comma-joins/validates [tags] -- see `MAX_EQUIPMENT_TAGS`/`MAX_TAG_LENGTH` KDoc for the defensive caps. */
    private fun requireValidEquipmentTags(tags: List<String>): String {
        val trimmed = tags.map { it.trim() }.filter { it.isNotBlank() }
        if (trimmed.size > MAX_EQUIPMENT_TAGS) {
            throw BadRequestException("Höchstens $MAX_EQUIPMENT_TAGS Ausstattungsmerkmale sind erlaubt.")
        }
        if (trimmed.any { it.length > MAX_TAG_LENGTH }) {
            throw BadRequestException("Ein Ausstattungsmerkmal darf höchstens $MAX_TAG_LENGTH Zeichen lang sein.")
        }
        val csv = trimmed.joinToString(",")
        if (csv.length > MAX_EQUIPMENT_TAGS_CSV_LENGTH) {
            throw BadRequestException(
                "Die Ausstattungsmerkmale sind in der Summe zu lang (höchstens $MAX_EQUIPMENT_TAGS_CSV_LENGTH Zeichen, aktuell ${csv.length}).",
            )
        }
        return csv
    }
}

private fun String.toEventRoomUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun ResultRow.toEventRoomDto(): EventRoomDto =
    EventRoomDto(
        id = this[EventRoomTable.id].toString(),
        name = this[EventRoomTable.name],
        capacity = this[EventRoomTable.capacity],
        equipmentTags = this[EventRoomTable.equipmentTags].split(",").filter { it.isNotBlank() },
        status = this[EventRoomTable.status],
    )
