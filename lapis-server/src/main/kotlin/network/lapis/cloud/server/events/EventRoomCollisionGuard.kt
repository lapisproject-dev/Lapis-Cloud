package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- the ONLY allowed entry point for
 * checking (and, by holding the lock through the caller's own insert/update, preventing) a
 * double-booking of an `event_room`. Mirrors [EventCapacityGuard]'s own "lock the row whose
 * invariant is being protected" posture: the invariant here ("no two non-CANCELLED events on the
 * same room may overlap in time") is a property of the ROOM, so [assertNoOverlap] locks the
 * `event_room` row, not any `event` row.
 *
 * **Deliberately no own `transaction {}`** -- unlike [EventCapacityGuard.withEventLock], this is
 * called from INSIDE an already-open transaction ([network.lapis.cloud.server.rpc.EventService
 * .createEvent]/`.updateEvent`'s own `transaction {}` block), so the room lock is held for the
 * remainder of that SAME transaction -- through the caller's own `INSERT`/`UPDATE` of the `event`
 * row -- closing the check-then-act race a separate, already-committed lock could not.
 *
 * **Edge-to-edge is NOT a conflict.** `existing.endsAt == startsAt` (the new booking starts the
 * instant the existing one ends) or `existing.startsAt == endsAt` (the reverse) does not overlap
 * -- the overlap predicate is the classic open-interval `existing.startsAt < newEnd AND
 * existing.endsAt > newStart`, tested explicitly in `EventRoomCollisionGuardTest`.
 */
internal object EventRoomCollisionGuard {
    fun assertNoOverlap(
        roomId: Uuid,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        excludingEventId: Uuid?,
    ) {
        val room = EventRoomStore.lockRoomForUpdate(roomId) ?: throw NotFoundException("EventRoom $roomId not found")
        if (room[EventRoomTable.status] != EventRoomStatus.ACTIVE) {
            throw ConflictException("Der Raum ist deaktiviert und kann keiner Veranstaltung mehr zugeordnet werden.")
        }
        val conflict =
            EventRoomStore.findOverlapping(
                roomId = roomId,
                startsAt = startsAt,
                endsAt = endsAt,
                excludingEventId = excludingEventId,
            ) ?: return
        throw ConflictException(
            "Der Raum ist im gewählten Zeitraum bereits belegt: \"${conflict[EventTable.title]}\" " +
                "(${conflict[EventTable.startsAt]} – ${conflict[EventTable.endsAt]}).",
        )
    }
}
