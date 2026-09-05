package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.3.1 "Veranstaltungen: Kernschleife + Anmeldegebuehren-Zahlung" -- see
 * `39-events.kuml.kts` file header for the full schema-scope rationale (why not `meeting`, why not
 * `external_donor`, why `active_participant_key` exists). Literal order load-bearing on all three
 * enums below -- must stay aligned with `39-events.kuml.kts`.
 */
@Serializable
enum class EventStatus { DRAFT, PUBLISHED, CANCELLED }

@Serializable
enum class EventVisibility { MEMBERS_ONLY, PUBLIC }

@Serializable
enum class EventRegistrationStatus { PENDING_PAYMENT, CONFIRMED, WAITLISTED, CANCELLED, EXPIRED }

/**
 * [INACTIVE] -- a registration in one of these statuses holds no seat and carries no
 * `active_participant_key` (mirrors `chk_event_registration_active_key`, `V18__events.sql`). Same
 * grouping idiom `ContributionStatusSets`/`MemberStatusSets` already establish elsewhere in this
 * codebase.
 */
object EventRegistrationStatusSets {
    val INACTIVE: Set<EventRegistrationStatus> = setOf(EventRegistrationStatus.CANCELLED, EventRegistrationStatus.EXPIRED)
}

/**
 * Role: BOARD/ADMIN (`createEvent`/`updateEvent`). [feeAmount] is server-validated `>= 0`; a
 * confirmed registration freezes it as `event_registration.fee_amount`'s snapshot at the moment of
 * registration -- see `EventPolicy`/`39-events.kuml.kts` file header. Deliberately carries no
 * `status`/`visibility` transition -- those are separate, auditable RPC calls (`publishEvent`/
 * `cancelEvent`), not silent side effects of a generic update.
 */
@Serializable
data class EventInput(
    val title: String,
    val description: String,
    val locationText: String? = null,
    val onlineUrl: String? = null,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val capacity: Int? = null,
    val feeAmount: Decimal,
    val feeCurrency: String = "EUR",
    val visibility: EventVisibility,
    val registrationClosesAt: LocalDateTime? = null,
)

@Serializable
data class EventDto(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val locationText: String?,
    val onlineUrl: String?,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val capacity: Int?,
    val feeAmount: Decimal,
    val feeCurrency: String,
    val status: EventStatus,
    val visibility: EventVisibility,
    val registrationClosesAt: LocalDateTime?,
    /** Server-computed: CONFIRMED + not-yet-expired PENDING_PAYMENT (lazy hold-expiry, see `EventCapacityGuard`). */
    val occupiedSeats: Int,
    val waitlistCount: Int,
    /** `true` iff a further registration would land on the waitlist -- the binary occupancy form the public page also shows, never an exact remaining count (see design decision "Belegung binaer"). */
    val full: Boolean,
    /** `false` once any non-CANCELLED/EXPIRED registration exists on this event -- see `EventPolicy`. */
    val feeEditable: Boolean,
    val ownRegistrationStatus: EventRegistrationStatus?,
    /** Non-null only when `visibility == PUBLIC && status == PUBLISHED`. */
    val publicUrl: String?,
)

@Serializable
data class EventRegistrationDto(
    val id: String,
    val eventId: String,
    val memberId: String?,
    val memberDisplayName: String?,
    val guestName: String?,
    val guestEmail: String?,
    val status: EventRegistrationStatus,
    val feeAmount: Decimal,
    val waitlistPosition: Int?,
    val registeredAt: LocalDateTime,
    val paymentTransactionId: String?,
    val journalEntryId: String?,
    /** Welle V1.4.3.2 -- additive, defaulted so no pre-existing caller/test breaks. Non-null iff a ticket has ever been issued for this registration. */
    val ticketIssuedAt: LocalDateTime? = null,
    val checkedInAt: LocalDateTime? = null,
    val checkedInByDisplayName: String? = null,
)

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- outcome of a door check-in attempt.
 * Deliberately a typed result, never an exception, for every FACHLICH state (see
 * `network.lapis.cloud.server.events.EventCheckIn` KDoc "verbindliche Reihenfolge") -- an exception
 * is reserved for a genuinely unparsable/unknown `eventId`/`registrationId` (a caller bug), not for
 * "this ticket is not valid right now", which a door volunteer hits routinely and needs rendered as
 * a calm status line, not a crash toast.
 */
@Serializable
enum class EventCheckInOutcome { OK, ALREADY_CHECKED_IN, WRONG_EVENT, NOT_CONFIRMED, CANCELLED_REGISTRATION, UNKNOWN_CODE }

@Serializable
data class EventCheckInResultDto(
    val outcome: EventCheckInOutcome,
    /** `null` for [EventCheckInOutcome.UNKNOWN_CODE] and [EventCheckInOutcome.WRONG_EVENT] -- no oracle over a fremdes Event's registrations. */
    val registrationId: String? = null,
    val participantName: String? = null,
    val checkedInAt: LocalDateTime? = null,
    val checkedInByDisplayName: String? = null,
)

@Serializable
data class EventCheckInRowDto(
    val registrationId: String,
    /** `memberDisplayName` or `guestName` -- the ONLY thing the list shows by default (design decision "die Liste zeigt keine E-Mail-Adressen"). */
    val displayName: String,
    val status: EventRegistrationStatus,
    /** Only rendered by the client after the row is explicitly expanded -- carried in the DTO so expanding needs no second round-trip. */
    val email: String?,
    val checkedInAt: LocalDateTime? = null,
    val checkedInByDisplayName: String? = null,
    val hasTicket: Boolean = false,
)

@Serializable
data class EventCheckInRosterDto(
    val eventId: String,
    val eventTitle: String,
    val startsAt: LocalDateTime,
    val locationText: String?,
    val rows: List<EventCheckInRowDto>,
    val confirmedCount: Int,
    val checkedInCount: Int,
)

@Serializable
data class EventPageDto(
    val rows: List<EventDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class EventQuery(
    val status: EventStatus? = null,
    val includePast: Boolean = false,
    val limit: Int = 50,
    val offset: Int = 0,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IEventService.registerSelf]. [checkoutRedirectUrl] is
 * non-null only when [registration]'s fee is `> 0` -- a free event confirms (or waitlists)
 * synchronously with no payment step.
 */
@Serializable
data class EventRegistrationResultDto(
    val registration: EventRegistrationDto,
    val checkoutRedirectUrl: String?,
)
