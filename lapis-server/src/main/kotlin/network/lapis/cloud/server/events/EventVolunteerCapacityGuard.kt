package network.lapis.cloud.server.events

import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- the ONLY allowed entry point for
 * checking (and, by holding the lock through the caller's own insert/update, preventing) an
 * over-capacity signup on an `event_volunteer_shift`. Mirrors [EventRoomCollisionGuard]'s own
 * "lock the row whose invariant is being protected" posture: the invariant here ("confirmed
 * signups never exceed neededCount") is a property of the SHIFT, so [assertCapacityAvailable]
 * locks the `event_volunteer_shift` row, not any `event_volunteer_signup` row.
 *
 * **Deliberately no own `transaction {}`** -- unlike [EventCapacityGuard.withEventLock], this is
 * called from INSIDE an already-open transaction (`EventVolunteerService.signUpSelf`'s own
 * `transaction {}` block), so the shift lock is held for the remainder of that SAME transaction --
 * through the caller's own INSERT/UPDATE of the `event_volunteer_signup` row -- closing the
 * check-then-act race a separate, already-committed lock could not.
 */
internal object EventVolunteerCapacityGuard {
    fun assertCapacityAvailable(shiftId: Uuid) {
        val shift = EventVolunteerStore.lockShiftForUpdate(shiftId) ?: throw NotFoundException("EventVolunteerShift $shiftId not found")
        if (shift[EventVolunteerShiftTable.status] != EventVolunteerShiftStatus.ACTIVE) {
            throw ConflictException("Diese Schicht wurde storniert und kann keine neuen Zusagen mehr annehmen.")
        }
        val confirmedCount = EventVolunteerStore.countConfirmedSignups(shiftId)
        if (confirmedCount >= shift[EventVolunteerShiftTable.neededCount]) {
            throw ConflictException("Diese Schicht ist bereits voll besetzt.")
        }
    }
}
