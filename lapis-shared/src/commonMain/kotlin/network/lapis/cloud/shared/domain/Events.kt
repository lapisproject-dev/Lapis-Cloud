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
    /** Welle V1.4.3.4 "Raumverwaltung" -- additive, defaulted so no pre-existing caller/test breaks. Server-validated: must reference an ACTIVE `EventRoom` with no overlapping booking (see `EventRoomCollisionGuard`). */
    val roomId: String? = null,
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
    /** Welle V1.4.3.4 "Raumverwaltung" -- additive, defaulted so no pre-existing caller/test breaks. Mirrors [EventInput.roomId]. */
    val roomId: String? = null,
    /** Welle V1.4.3.4 "Raumverwaltung" -- denormalized `EventRoom.name` for display, server-computed, never client-supplied. Null iff [roomId] is null. */
    val roomName: String? = null,
    /**
     * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- additive, defaulted so no pre-existing
     * caller/test breaks. Absolute URL, non-null iff a cover image exists:
     * `"$baseUrl/veranstaltung/$slug/bild?v=<first 8 chars of coverImageId>"` -- see
     * `EventCoverPolicy.coverImageUrl`. Publicly fetchable without a session only for a
     * PUBLIC+PUBLISHED event; for any other event the same URL requires a BOARD/ADMIN session (see
     * `EventCoverRoutes`' GET route KDoc).
     */
    val coverImageUrl: String? = null,
    /**
     * Dritte Folgewelle "Wiederkehrende Veranstaltungen" -- additive, defaulted so no pre-existing
     * caller/test breaks. Non-null iff this event was materialized as part of an
     * `EventSeriesCreateResultDto`/`EventSeriesMaterializer` series.
     */
    val seriesId: String? = null,
    /** `true` once `EventSeriesEditScope.THIS` detached this single occurrence from its series. */
    val seriesDetached: Boolean = false,
    /** Non-null only when `seriesId != null && !seriesDetached` -- for the ↻ symbol's tooltip/aria-label. */
    val seriesRuleSummary: String? = null,
    /** V1.9.35: the caller's NEWEST own registration (any status) has a COMPLETED event-fee session. */
    val ownPaid: Boolean = false,
    /** V1.9.35: `refund_marked_at` of that newest own registration (null = not marked). */
    val ownRefundMarkedAt: LocalDateTime? = null,
    /** V1.9.35: the amount actually paid for that newest own registration (sum of COMPLETED sessions), null if unpaid. */
    val ownPaidAmount: Decimal? = null,
)

/** V1.9.35 -- one paid-but-withdrawn registration whose refund the board still has to pay outside Lapis Cloud. */
@Serializable
data class EventRefundDto(
    val registrationId: String,
    val eventId: String,
    val eventTitle: String,
    val eventStartsAt: LocalDateTime,
    /** Member name or guest name -- UNTRUSTED in the client. */
    val participantDisplayName: String,
    /** Sum of the COMPLETED sessions, never client-supplied. */
    val paidAmount: Decimal,
    /** More than 1 = paid several times. */
    val paymentCount: Int,
    val cancelledAt: LocalDateTime?,
    val status: EventRegistrationStatus,
    val refundMarkedAt: LocalDateTime? = null,
)

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- result of uploading or removing an
 * event's cover image (`network.lapis.cloud.server.routes.EventCoverRoutes`). Deliberately a
 * narrow, dedicated DTO rather than the full [EventDto]: [EventDto] carries
 * `dev.kilua.rpc.types.Decimal`/`kotlinx.datetime.LocalDateTime` fields that no plain Ktor route in
 * this codebase has ever encoded with bare `kotlinx.serialization.Json` (only Kilua RPC's own
 * serializer module does), and the upload/remove client only ever needs the resulting URL -- the
 * event list itself is reloaded via the existing RPC `listEvents`/`getEvent` calls, which already
 * carry the full, current [EventDto].
 */
@Serializable
data class EventCoverResultDto(
    val coverImageUrl: String?,
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
    /** Welle V1.4.3.6 "Externe Rechnungsstellung" -- additive, defaulted so no pre-existing caller/test breaks. Non-null iff [network.lapis.cloud.shared.rpc.IEventService.issueEventInvoice] was ever called for this registration -- see that method's KDoc. */
    val billingStreet: String? = null,
    val billingPostalCode: String? = null,
    val billingCity: String? = null,
    val billingCountry: String? = null,
    val openItemId: String? = null,
    val invoiceIssuedAt: LocalDateTime? = null,
    val invoiceIssuedByDisplayName: String? = null,
)

/**
 * Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen" -- input to
 * [network.lapis.cloud.shared.rpc.IEventService.issueEventInvoice]. The billing address fields are
 * ALL optional (a guest without a mailing address is still invoiceable -- the invoice PDF simply
 * omits the address block, same defensive posture [network.lapis.cloud.server.pdf
 * .LetterPdfBuilder]'s own KDoc documents for other letter templates) but are server-length-
 * validated when present -- see `EventService.issueEventInvoice` KDoc.
 */
@Serializable
data class EventInvoiceRequestDto(
    val registrationId: String,
    val billingStreet: String? = null,
    val billingPostalCode: String? = null,
    val billingCity: String? = null,
    val billingCountry: String? = null,
    val dueInDays: Int = 14,
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
