package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.server.db.generated.EventVolunteerSignupTable
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.EventVolunteerSignupStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- reine Exposed-Datenzugriffsschicht
 * für `event_volunteer_shift`/`event_volunteer_signup`. Öffnet, wie [EventRoomStore]/
 * [EventCateringStore], NIE eine eigene `transaction {}` -- die Aufrufer
 * ([EventVolunteerCapacityGuard]/`network.lapis.cloud.server.rpc.EventVolunteerService`) tun das.
 */
internal object EventVolunteerStore {
    // ── Shift ─────────────────────────────────────────────────────────────────────────────────

    fun getShiftOrNull(id: Uuid): ResultRow? =
        EventVolunteerShiftTable.selectAll().where { EventVolunteerShiftTable.id eq id }.singleOrNull()

    fun getShiftOrThrow(id: Uuid): ResultRow = getShiftOrNull(id) ?: throw NotFoundException("EventVolunteerShift $id not found")

    /** `FOR UPDATE` row lock on `event_volunteer_shift` -- see [EventVolunteerCapacityGuard] KDoc
     * for why every capacity-changing operation must hold this lock (mirrors
     * [EventRoomStore.lockRoomForUpdate]'s own "lock the row whose invariant is being protected"
     * posture -- here the invariant "confirmed signups never exceed neededCount" is a property of
     * the SHIFT). */
    fun lockShiftForUpdate(id: Uuid): ResultRow? =
        EventVolunteerShiftTable
            .selectAll()
            .where { EventVolunteerShiftTable.id eq id }
            .forUpdate()
            .singleOrNull()

    fun listShifts(eventId: Uuid): List<ResultRow> =
        EventVolunteerShiftTable
            .selectAll()
            .where { EventVolunteerShiftTable.eventId eq eventId }
            .orderBy(EventVolunteerShiftTable.startsAt, SortOrder.ASC)
            .toList()

    fun insertShift(
        id: Uuid,
        eventId: Uuid,
        description: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        neededCount: Int,
        createdAt: LocalDateTime,
        createdBy: Uuid,
    ) {
        EventVolunteerShiftTable.insert {
            it[EventVolunteerShiftTable.id] = id
            it[EventVolunteerShiftTable.eventId] = eventId
            it[EventVolunteerShiftTable.description] = description
            it[EventVolunteerShiftTable.startsAt] = startsAt
            it[EventVolunteerShiftTable.endsAt] = endsAt
            it[EventVolunteerShiftTable.neededCount] = neededCount
            it[EventVolunteerShiftTable.status] = EventVolunteerShiftStatus.ACTIVE
            it[EventVolunteerShiftTable.createdAt] = createdAt
            it[EventVolunteerShiftTable.createdBy] = createdBy
        }
    }

    fun updateShift(
        id: Uuid,
        eventId: Uuid,
        description: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        neededCount: Int,
    ) {
        EventVolunteerShiftTable.update({ EventVolunteerShiftTable.id eq id }) {
            it[EventVolunteerShiftTable.eventId] = eventId
            it[EventVolunteerShiftTable.description] = description
            it[EventVolunteerShiftTable.startsAt] = startsAt
            it[EventVolunteerShiftTable.endsAt] = endsAt
            it[EventVolunteerShiftTable.neededCount] = neededCount
        }
    }

    /** Soft-Cancel, nie ein DELETE -- see `51-event-volunteer.kuml.kts` file header. */
    fun cancelShift(id: Uuid) {
        EventVolunteerShiftTable.update({ EventVolunteerShiftTable.id eq id }) {
            it[EventVolunteerShiftTable.status] = EventVolunteerShiftStatus.CANCELLED
        }
    }

    /** Anzahl der noch aktiven (CONFIRMED) Zusagen für [shiftId] -- die Kapazitäts-Zählbasis für [EventVolunteerCapacityGuard]. */
    fun countConfirmedSignups(shiftId: Uuid): Int =
        EventVolunteerSignupTable
            .selectAll()
            .where {
                (EventVolunteerSignupTable.shiftId eq shiftId) and
                    (EventVolunteerSignupTable.status eq EventVolunteerSignupStatus.CONFIRMED)
            }.count()
            .toInt()

    // ── Signup ────────────────────────────────────────────────────────────────────────────────

    fun listSignups(shiftId: Uuid): List<ResultRow> =
        EventVolunteerSignupTable
            .selectAll()
            .where { EventVolunteerSignupTable.shiftId eq shiftId }
            .orderBy(EventVolunteerSignupTable.signedUpAt, SortOrder.ASC)
            .toList()

    /** The calling member's own active (CONFIRMED) signup for [shiftId], if any. */
    fun findOwnActiveSignup(
        shiftId: Uuid,
        memberId: Uuid,
    ): ResultRow? =
        EventVolunteerSignupTable
            .selectAll()
            .where {
                (EventVolunteerSignupTable.shiftId eq shiftId) and
                    (EventVolunteerSignupTable.memberId eq memberId) and
                    (EventVolunteerSignupTable.status eq EventVolunteerSignupStatus.CONFIRMED)
            }.singleOrNull()

    /** ANY (CONFIRMED or CANCELLED) existing signup row for [shiftId]/[memberId] -- used by
     * `signUpSelf`'s Re-Anmeldungs-Upsert (see that method's own KDoc): reactivates an existing
     * CANCELLED row instead of inserting a second one for the same member/shift pair. */
    fun findExistingSignup(
        shiftId: Uuid,
        memberId: Uuid,
    ): ResultRow? =
        EventVolunteerSignupTable
            .selectAll()
            .where { (EventVolunteerSignupTable.shiftId eq shiftId) and (EventVolunteerSignupTable.memberId eq memberId) }
            .singleOrNull()

    fun insertSignup(
        id: Uuid,
        shiftId: Uuid,
        memberId: Uuid,
        signedUpAt: LocalDateTime,
    ) {
        EventVolunteerSignupTable.insert {
            it[EventVolunteerSignupTable.id] = id
            it[EventVolunteerSignupTable.shiftId] = shiftId
            it[EventVolunteerSignupTable.memberId] = memberId
            it[EventVolunteerSignupTable.status] = EventVolunteerSignupStatus.CONFIRMED
            it[EventVolunteerSignupTable.signedUpAt] = signedUpAt
            it[EventVolunteerSignupTable.cancelledAt] = null
            it[EventVolunteerSignupTable.activeMemberKey] = memberId.toString()
        }
    }

    /** Reactivates an existing CANCELLED row -- see [findExistingSignup] KDoc "Re-Anmeldungs-Upsert". */
    fun reactivateSignup(
        id: Uuid,
        memberId: Uuid,
        signedUpAt: LocalDateTime,
    ) {
        EventVolunteerSignupTable.update({ EventVolunteerSignupTable.id eq id }) {
            it[EventVolunteerSignupTable.status] = EventVolunteerSignupStatus.CONFIRMED
            it[EventVolunteerSignupTable.signedUpAt] = signedUpAt
            it[EventVolunteerSignupTable.cancelledAt] = null
            it[EventVolunteerSignupTable.activeMemberKey] = memberId.toString()
        }
    }

    fun cancelSignup(
        id: Uuid,
        now: LocalDateTime,
    ) {
        EventVolunteerSignupTable.update({ EventVolunteerSignupTable.id eq id }) {
            it[EventVolunteerSignupTable.status] = EventVolunteerSignupStatus.CANCELLED
            it[EventVolunteerSignupTable.cancelledAt] = now
            it[EventVolunteerSignupTable.activeMemberKey] = null
        }
    }
}
