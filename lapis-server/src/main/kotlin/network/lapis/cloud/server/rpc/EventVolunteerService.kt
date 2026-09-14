package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.server.db.generated.EventVolunteerSignupTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventVolunteerCapacityGuard
import network.lapis.cloud.server.events.EventVolunteerStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftInput
import network.lapis.cloud.shared.domain.EventVolunteerShiftRosterDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.EventVolunteerSignupDto
import network.lapis.cloud.shared.domain.EventVolunteerSignupStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEventVolunteerService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

internal val EVENT_VOLUNTEER_MANAGE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** Mirrors `event_volunteer_shift.description VARCHAR(500)` (`V39__event_volunteer.sql`) -- same
 * "friendly first gate, DB is the backstop" posture `EventRoomService`'s own
 * `MAX_EQUIPMENT_TAGS_CSV_LENGTH` KDoc documents. */
private const val MAX_DESCRIPTION_LENGTH = 500

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- implements
 * [IEventVolunteerService]. Schicht-Management (BOARD/ADMIN) + Selbstbedienungs-Zusagen (jedes
 * authentifizierte Mitglied) -- siehe [network.lapis.cloud.shared.domain.EventVolunteerShiftInput]
 * KDoc für die "KEINE neue Rolle"-Scope-Entscheidung.
 *
 * [writeRateLimiter] gates ONLY [signUpSelf]/[cancelOwnSignup] -- same rate-limiting posture
 * `EventService.registerSelf`/`.cancelOwnRegistration` establish via their own `writeRateLimiter`
 * (member-keyed `requireWithinRate`). The BOARD/ADMIN management methods below are deliberately
 * NOT rate-limited -- mirrors `EventRoomService`/`CateringService`, neither of which rate-limits
 * their own (also BOARD/ADMIN-only) management methods either.
 */
class EventVolunteerService(
    private val call: ApplicationCall,
    private val writeRateLimiter: FederationInboxRateLimiter,
) : IEventVolunteerService {
    override suspend fun listShifts(eventId: String): List<EventVolunteerShiftDto> {
        val current = resolveCurrentMember(call)
        val isManager = current.role in EVENT_VOLUNTEER_MANAGE_ROLES
        val id = eventId.toEventVolunteerEventUuid()
        return transaction {
            // Security-Audit MAJOR fix: mirrors EventService.getEvent's own visibility gate -- a
            // non-manager (not BOARD/ADMIN) must not see shift details of a DRAFT event, and
            // NotFoundException (not ForbiddenException) keeps the event's existence undisclosed.
            val event = EventStore.getEventOrThrow(id)
            if (!isManager && event[EventTable.status] != EventStatus.PUBLISHED) {
                throw NotFoundException("Event $id not found")
            }
            EventVolunteerStore.listShifts(id).map { it.toEventVolunteerShiftDto(currentMemberId = current.memberId) }
        }
    }

    override suspend fun createShift(input: EventVolunteerShiftInput): EventVolunteerShiftDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_VOLUNTEER_MANAGE_ROLES)
        val description = requireValidDescription(input.description)
        requireValidNeededCount(input.neededCount)
        requireValidTimeRange(startsAt = input.startsAt, endsAt = input.endsAt)
        val eventId = input.eventId.toEventVolunteerEventUuid()
        return transaction {
            EventStore.getEventOrThrow(eventId)
            val id = Uuid.random()
            EventVolunteerStore.insertShift(
                id = id,
                eventId = eventId,
                description = description,
                startsAt = input.startsAt,
                endsAt = input.endsAt,
                neededCount = input.neededCount,
                createdAt = DbClock.nowLocalDateTime(),
                createdBy = current.memberId,
            )
            EventVolunteerStore.getShiftOrThrow(id).toEventVolunteerShiftDto(currentMemberId = current.memberId)
        }
    }

    override suspend fun updateShift(
        id: String,
        input: EventVolunteerShiftInput,
    ): EventVolunteerShiftDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_VOLUNTEER_MANAGE_ROLES)
        val description = requireValidDescription(input.description)
        requireValidNeededCount(input.neededCount)
        requireValidTimeRange(startsAt = input.startsAt, endsAt = input.endsAt)
        val shiftId = id.toEventVolunteerUuid()
        val eventId = input.eventId.toEventVolunteerEventUuid()
        return transaction {
            EventVolunteerStore.getShiftOrThrow(shiftId)
            EventStore.getEventOrThrow(eventId)
            EventVolunteerStore.updateShift(
                id = shiftId,
                eventId = eventId,
                description = description,
                startsAt = input.startsAt,
                endsAt = input.endsAt,
                neededCount = input.neededCount,
            )
            EventVolunteerStore.getShiftOrThrow(shiftId).toEventVolunteerShiftDto(currentMemberId = current.memberId)
        }
    }

    override suspend fun cancelShift(id: String): EventVolunteerShiftDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_VOLUNTEER_MANAGE_ROLES)
        val shiftId = id.toEventVolunteerUuid()
        return transaction {
            // Security-Audit MAJOR fix: row lock, FIRST operation -- mirrors EventService.cancelEvent's
            // own discipline (see that method's KDoc). Without it, a concurrent signUpSelf that
            // already passed EventVolunteerCapacityGuard.assertCapacityAvailable's own lock+check
            // could commit a fresh CONFIRMED signup against a shift that, by then, is already
            // CANCELLED underneath it.
            val shift =
                EventVolunteerStore.lockShiftForUpdate(shiftId) ?: throw NotFoundException("EventVolunteerShift $shiftId not found")
            if (shift[EventVolunteerShiftTable.status] == EventVolunteerShiftStatus.CANCELLED) {
                throw ConflictException("Diese Schicht wurde bereits abgesagt.")
            }
            EventVolunteerStore.cancelShift(shiftId)
            EventVolunteerStore.getShiftOrThrow(shiftId).toEventVolunteerShiftDto(currentMemberId = current.memberId)
        }
    }

    override suspend fun getShiftRoster(id: String): EventVolunteerShiftRosterDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_VOLUNTEER_MANAGE_ROLES)
        val shiftId = id.toEventVolunteerUuid()
        return transaction {
            val shift = EventVolunteerStore.getShiftOrThrow(shiftId).toEventVolunteerShiftDto(currentMemberId = current.memberId)
            val signups =
                EventVolunteerStore.listSignups(shiftId).map { row ->
                    val memberId = row[EventVolunteerSignupTable.memberId]
                    row.toEventVolunteerSignupDto(memberDisplayName = EventStore.memberDisplayNameOrNull(memberId) ?: "")
                }
            EventVolunteerShiftRosterDto(shift = shift, signups = signups)
        }
    }

    override suspend fun signUpSelf(shiftId: String): EventVolunteerSignupDto {
        val current = resolveCurrentMember(call)
        val isManager = current.role in EVENT_VOLUNTEER_MANAGE_ROLES
        requireWithinRate(current.memberId)
        val id = shiftId.toEventVolunteerUuid()
        val now = DbClock.nowLocalDateTime()
        val signupId =
            transaction {
                // Security-Audit MAJOR fix: visibility gate BEFORE the capacity check/write -- see
                // requireVisibleShift KDoc.
                requireVisibleShift(shiftId = id, isManager = isManager)
                EventVolunteerCapacityGuard.assertCapacityAvailable(id)
                val existing = EventVolunteerStore.findExistingSignup(shiftId = id, memberId = current.memberId)
                if (existing != null) {
                    // Re-Anmeldung nach vorheriger Abmeldung -- reaktiviert die bestehende Zeile
                    // statt eine zweite anzulegen (siehe EventVolunteerStore.findExistingSignup
                    // KDoc "Re-Anmeldungs-Upsert").
                    val existingId = existing[EventVolunteerSignupTable.id]
                    if (existing[EventVolunteerSignupTable.status] == EventVolunteerSignupStatus.CONFIRMED) {
                        throw ConflictException("Sie sind für diese Schicht bereits angemeldet.")
                    }
                    EventVolunteerStore.reactivateSignup(id = existingId, memberId = current.memberId, signedUpAt = now)
                    existingId
                } else {
                    val newId = Uuid.random()
                    EventVolunteerStore.insertSignup(id = newId, shiftId = id, memberId = current.memberId, signedUpAt = now)
                    newId
                }
            }
        return fetchSignupDto(signupId = signupId, memberId = current.memberId)
    }

    override suspend fun cancelOwnSignup(shiftId: String): EventVolunteerSignupDto {
        val current = resolveCurrentMember(call)
        val isManager = current.role in EVENT_VOLUNTEER_MANAGE_ROLES
        requireWithinRate(current.memberId)
        val id = shiftId.toEventVolunteerUuid()
        val now = DbClock.nowLocalDateTime()
        val signupId =
            transaction {
                // Security-Audit MAJOR fix: visibility gate BEFORE the write -- see
                // requireVisibleShift KDoc.
                requireVisibleShift(shiftId = id, isManager = isManager)
                val existing =
                    EventVolunteerStore.findOwnActiveSignup(shiftId = id, memberId = current.memberId)
                        ?: throw NotFoundException("Keine aktive Zusage für diese Schicht gefunden.")
                val existingId = existing[EventVolunteerSignupTable.id]
                EventVolunteerStore.cancelSignup(id = existingId, now = now)
                existingId
            }
        return fetchSignupDto(signupId = signupId, memberId = current.memberId)
    }

    /**
     * Security-Audit MAJOR fix: loads [shiftId]'s parent event and applies the same visibility gate
     * [EventService.getEvent] establishes -- a non-manager (not BOARD/ADMIN) may only see/act on a
     * shift whose event is `PUBLISHED`; a `DRAFT` event's shifts stay invisible, and
     * [NotFoundException] (not `ForbiddenException`) keeps the event's existence undisclosed. Must
     * run INSIDE the same `transaction {}` as the caller's own capacity check/write, and BEFORE it,
     * so a MEMBER can never observe or act on a not-yet-published event's shifts via `shiftId` alone
     * (e.g. one leaked through a shared URL).
     */
    private fun requireVisibleShift(
        shiftId: Uuid,
        isManager: Boolean,
    ) {
        val shift = EventVolunteerStore.getShiftOrThrow(shiftId)
        if (isManager) return
        val eventId = shift[EventVolunteerShiftTable.eventId]
        val event = EventStore.getEventOrThrow(eventId)
        if (event[EventTable.status] != EventStatus.PUBLISHED) {
            throw NotFoundException("EventVolunteerShift $shiftId not found")
        }
    }

    private fun requireWithinRate(memberId: Uuid) {
        if (!writeRateLimiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte kurz warten und erneut versuchen.")
        }
    }

    private fun fetchSignupDto(
        signupId: Uuid,
        memberId: Uuid,
    ): EventVolunteerSignupDto =
        transaction {
            val row = EventVolunteerSignupTable.selectAll().where { EventVolunteerSignupTable.id eq signupId }.single()
            row.toEventVolunteerSignupDto(memberDisplayName = EventStore.memberDisplayNameOrNull(memberId) ?: "")
        }

    private fun requireValidDescription(description: String): String {
        val trimmed = description.trim()
        if (trimmed.isBlank()) throw BadRequestException("Die Beschreibung darf nicht leer sein.")
        if (trimmed.length > MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("Die Beschreibung darf höchstens $MAX_DESCRIPTION_LENGTH Zeichen lang sein.")
        }
        return trimmed
    }

    private fun requireValidNeededCount(neededCount: Int) {
        if (neededCount <= 0) throw BadRequestException("Die benötigte Anzahl muss größer als 0 sein.")
    }

    private fun requireValidTimeRange(
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
    ) {
        if (endsAt <= startsAt) throw BadRequestException("Das Ende der Schicht muss nach ihrem Beginn liegen.")
    }
}

private fun String.toEventVolunteerUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun String.toEventVolunteerEventUuid(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid event id: $this") }

private fun ResultRow.toEventVolunteerShiftDto(currentMemberId: Uuid): EventVolunteerShiftDto {
    val shiftId = this[EventVolunteerShiftTable.id]
    val confirmedCount = EventVolunteerStore.countConfirmedSignups(shiftId)
    val neededCount = this[EventVolunteerShiftTable.neededCount]
    val ownSignup = EventVolunteerStore.findExistingSignup(shiftId = shiftId, memberId = currentMemberId)
    return EventVolunteerShiftDto(
        id = shiftId.toString(),
        eventId = this[EventVolunteerShiftTable.eventId].toString(),
        description = this[EventVolunteerShiftTable.description],
        startsAt = this[EventVolunteerShiftTable.startsAt],
        endsAt = this[EventVolunteerShiftTable.endsAt],
        neededCount = neededCount,
        status = this[EventVolunteerShiftTable.status],
        confirmedCount = confirmedCount,
        full = confirmedCount >= neededCount,
        ownSignupStatus = ownSignup?.get(EventVolunteerSignupTable.status),
    )
}

private fun ResultRow.toEventVolunteerSignupDto(memberDisplayName: String): EventVolunteerSignupDto =
    EventVolunteerSignupDto(
        id = this[EventVolunteerSignupTable.id].toString(),
        shiftId = this[EventVolunteerSignupTable.shiftId].toString(),
        memberId = this[EventVolunteerSignupTable.memberId].toString(),
        memberDisplayName = memberDisplayName,
        status = this[EventVolunteerSignupTable.status],
        signedUpAt = this[EventVolunteerSignupTable.signedUpAt],
        cancelledAt = this[EventVolunteerSignupTable.cancelledAt],
    )
