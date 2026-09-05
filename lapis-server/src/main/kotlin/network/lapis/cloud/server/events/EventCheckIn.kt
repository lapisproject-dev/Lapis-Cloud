package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.shared.domain.EventCheckInOutcome
import network.lapis.cloud.shared.domain.EventCheckInResultDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatusSets
import network.lapis.cloud.shared.domain.EventTicketCode
import org.jetbrains.exposed.v1.core.ResultRow
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- pure classification + the atomic
 * check-in transition, analogous to the `EventPolicy` (rein) / `EventStore` (Exposed) split this
 * domain already establishes. Every function here must run inside an already-open `transaction {}`
 * -- neither opens its own.
 *
 * **Does NOT go through [EventCapacityGuard]** -- see `39-events.kuml.kts` file header "Why
 * check-in does NOT go through EventCapacityGuard" for the full reasoning (a check-in changes
 * neither the confirmed-seat count nor the waitlist).
 */
internal object EventCheckIn {
    /**
     * **Verbindliche Reihenfolge, any reordering is a review blocker:**
     * 1. [network.lapis.cloud.shared.domain.EventTicketCode.extractAndCanonicalize] -- failure ->
     *    [EventCheckInOutcome.UNKNOWN_CODE], WITHOUT any database access (first line of DoS
     *    defense, same posture `EventPolicy.normalizeGuestEmail` establishes in the registration
     *    path).
     * 2. Lookup by SHA-256 hash GLOBALLY (`EventStore.findByTicketCodeHash`, not scoped to
     *    `eventId`) -- only this way is [EventCheckInOutcome.WRONG_EVENT] distinguishable from
     *    [EventCheckInOutcome.UNKNOWN_CODE]; the unique index this relies on is global for exactly
     *    this reason (`uq_event_registration_ticket_code`, `V19__event_tickets.sql`).
     * 3. [MessageDigest.isEqual] against the stored hash -- defense-in-depth on top of the lookup
     *    already having matched by hash (same "never trust a raw `==` on a secret-derived value"
     *    idiom `EventPublicRoutes`' storno/payment-resume handlers already establish).
     * 4. `eventId` mismatch -> [EventCheckInOutcome.WRONG_EVENT] (never names the other event).
     * 5. `status` in [EventRegistrationStatusSets.INACTIVE] -> [EventCheckInOutcome.CANCELLED_REGISTRATION];
     *    any other non-CONFIRMED status (PENDING_PAYMENT/WAITLISTED) -> [EventCheckInOutcome.NOT_CONFIRMED].
     * 6. `checkedInAt != null` -> [EventCheckInOutcome.ALREADY_CHECKED_IN] (with timestamp + who).
     * 7. Atomic `UPDATE ... WHERE checked_in_at IS NULL AND status = 'CONFIRMED'`
     *    ([EventStore.checkInIfNotCheckedIn]). Zero rows affected means a concurrent check-in won
     *    the race between step 6's read and this write -- re-read and report
     *    [EventCheckInOutcome.ALREADY_CHECKED_IN], never silently succeed twice.
     */
    fun byCode(
        eventId: Uuid,
        rawInput: String,
        actorMemberId: Uuid,
        now: LocalDateTime,
    ): EventCheckInResultDto {
        val canonical =
            EventTicketCode.extractAndCanonicalize(rawInput)
                ?: return EventCheckInResultDto(outcome = EventCheckInOutcome.UNKNOWN_CODE)
        val hash = sha256Hex(canonical.toByteArray(Charsets.US_ASCII))
        val row = EventStore.findByTicketCodeHash(hash) ?: return EventCheckInResultDto(outcome = EventCheckInOutcome.UNKNOWN_CODE)
        val storedHash = row[EventRegistrationTable.ticketCodeSha256]
        if (storedHash == null || !MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), storedHash.toByteArray(Charsets.US_ASCII))) {
            return EventCheckInResultDto(outcome = EventCheckInOutcome.UNKNOWN_CODE)
        }
        return classifyAndCheckIn(row = row, eventId = eventId, actorMemberId = actorMemberId, now = now)
    }

    /**
     * The list-based counterpart of [byCode] -- starts directly from a known [registrationId], no
     * code involved. [eventId] is REQUIRED (not derived from the looked-up row, review MINOR fix)
     * so step 4's WRONG_EVENT check is a real cross-check against the caller's own check-in-screen
     * scope, not a tautology against the very row it is meant to validate.
     */
    fun byRegistration(
        eventId: Uuid,
        registrationId: Uuid,
        actorMemberId: Uuid,
        now: LocalDateTime,
    ): EventCheckInResultDto {
        val row =
            EventStore.getRegistrationOrNull(registrationId)
                ?: return EventCheckInResultDto(outcome = EventCheckInOutcome.UNKNOWN_CODE)
        return classifyAndCheckIn(row = row, eventId = eventId, actorMemberId = actorMemberId, now = now)
    }

    private fun classifyAndCheckIn(
        row: ResultRow,
        eventId: Uuid,
        actorMemberId: Uuid,
        now: LocalDateTime,
    ): EventCheckInResultDto {
        val registrationId = row[EventRegistrationTable.id]
        if (row[EventRegistrationTable.eventId] != eventId) {
            return EventCheckInResultDto(outcome = EventCheckInOutcome.WRONG_EVENT)
        }
        val status = row[EventRegistrationTable.status]
        if (status in EventRegistrationStatusSets.INACTIVE) {
            return EventCheckInResultDto(outcome = EventCheckInOutcome.CANCELLED_REGISTRATION, registrationId = registrationId.toString())
        }
        if (status != EventRegistrationStatus.CONFIRMED) {
            return EventCheckInResultDto(outcome = EventCheckInOutcome.NOT_CONFIRMED, registrationId = registrationId.toString())
        }
        val alreadyCheckedInAt = row[EventRegistrationTable.checkedInAt]
        if (alreadyCheckedInAt != null) {
            return alreadyCheckedInResult(row)
        }
        // Security-Review LOW fix: a CONFIRMED, not-yet-checked-in row can still lack a ticket --
        // e.g. a legacy V1.4.3.1-era registration, or one reached via `byRegistration` directly
        // without `IEventService.openCheckIn`'s own nachausstellung sweep having run first (a
        // BOARD/ADMIN calling the RPC straight). `EventStore.checkInIfNotCheckedIn`'s guard now
        // requires a ticket (mirroring `chk_event_registration_checkin_ticket`), so issue one here,
        // idempotently, before attempting the check-in -- a no-op if the row already has one.
        EventTicketIssuer.issueIfMissing(registrationId = registrationId, now = now)
        val affected = EventStore.checkInIfNotCheckedIn(id = registrationId, byMemberId = actorMemberId, now = now)
        if (affected == 0) {
            // Lost the race against a concurrent check-in between the read above and this write --
            // re-read and report the truth, never silently succeed twice.
            val fresh = EventStore.getRegistrationOrThrow(registrationId)
            return alreadyCheckedInResult(fresh)
        }
        return EventCheckInResultDto(
            outcome = EventCheckInOutcome.OK,
            registrationId = registrationId.toString(),
            participantName = participantNameOf(row),
            checkedInAt = now,
            checkedInByDisplayName = EventStore.memberDisplayNameOrNull(actorMemberId),
        )
    }

    private fun alreadyCheckedInResult(row: ResultRow): EventCheckInResultDto {
        val checkedInBy = row[EventRegistrationTable.checkedInBy]
        return EventCheckInResultDto(
            outcome = EventCheckInOutcome.ALREADY_CHECKED_IN,
            registrationId = row[EventRegistrationTable.id].toString(),
            participantName = participantNameOf(row),
            checkedInAt = row[EventRegistrationTable.checkedInAt],
            checkedInByDisplayName = checkedInBy?.let { EventStore.memberDisplayNameOrNull(it) },
        )
    }

    private fun participantNameOf(row: ResultRow): String? {
        val memberId = row[EventRegistrationTable.memberId]
        return if (memberId != null) EventStore.memberDisplayNameOrNull(memberId) else row[EventRegistrationTable.guestName]
    }
}
