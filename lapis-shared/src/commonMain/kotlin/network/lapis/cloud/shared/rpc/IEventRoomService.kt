package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomInput

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- room master data CRUD, deliberately its
 * OWN RPC interface rather than a set of methods on [IEventService]: which rooms exist, their
 * capacity/equipment, and whether they are active is orthogonal to the event/registration
 * fachlogik `IEventService` owns -- a room's lifecycle has nothing to do with a specific event.
 * `IEventService.createEvent`/`updateEvent` themselves validate a supplied `EventInput.roomId`
 * (exists, ACTIVE, no overlapping booking) -- no dedicated room-assignment endpoint exists here;
 * assigning a room to an event is just a normal `updateEvent` call.
 *
 * Role: BOARD/ADMIN for every method -- room master data is management/back-office data, never
 * member-public (same tier as `ICrmService`/`IApiKeyService`).
 *
 * Deliberately out of scope for this wave: multi-room-per-event (m:n) -- see
 * `network.lapis.cloud.shared.domain.EventRoomStatus` KDoc.
 */
@RpcService
interface IEventRoomService {
    /** Role: BOARD/ADMIN. [includeInactive] defaults to `true` -- an INACTIVE room still needs to be visible to an admin managing room master data, just not selectable as a NEW booking target (enforced by `EventRoomCollisionGuard`, not by this list). */
    suspend fun listRooms(includeInactive: Boolean = true): List<EventRoomDto>

    /** Role: BOARD/ADMIN. Rejects a blank/duplicate [EventRoomInput.name] (`BadRequestException`/`ConflictException`). */
    suspend fun createRoom(input: EventRoomInput): EventRoomDto

    /** Role: BOARD/ADMIN. */
    suspend fun updateRoom(
        id: String,
        input: EventRoomInput,
    ): EventRoomDto

    /** Role: BOARD/ADMIN. Deactivates, never deletes -- a deactivated room remains a valid FK target for every event that already references it, but can no longer be assigned to a NEW event (`EventRoomCollisionGuard`). */
    suspend fun deactivateRoom(id: String): EventRoomDto

    /** Role: BOARD/ADMIN. */
    suspend fun activateRoom(id: String): EventRoomDto
}
