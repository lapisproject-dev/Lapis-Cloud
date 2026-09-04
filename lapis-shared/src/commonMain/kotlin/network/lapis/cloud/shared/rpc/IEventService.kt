package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationResultDto

/**
 * Welle V1.4.3.1 "Veranstaltungen: Kernschleife + Anmeldegebuehren-Zahlung" -- the AUTHENTICATED
 * RPC surface. The unauthenticated public registration path
 * (`network.lapis.cloud.server.routes.registerEventPublicRoutes`, a classic server-rendered
 * `<form>`) does not go through this interface at all -- see that file's own KDoc.
 *
 * **No amount parameter anywhere in this interface, deliberately** -- [registerSelf] always reads
 * the fee exclusively from `event.fee_amount`, server-side, under the event row lock
 * (`EventCapacityGuard.withEventLock`). This is a structural (not merely validated) guarantee: a
 * caller cannot even attempt to pass a different amount.
 */
@RpcService
interface IEventService {
    /** Role: any authenticated member. `MEMBERS_ONLY`-visibility events are included; `PUBLIC` ones too (a member sees at least what a stranger sees). [limit] is server-capped. */
    suspend fun listEvents(query: EventQuery = EventQuery()): EventPageDto

    /** Role: any authenticated member. */
    suspend fun getEvent(id: String): EventDto

    /** Role: BOARD/ADMIN. Server validates [input] regardless of any client-side pre-check. */
    suspend fun createEvent(input: EventInput): EventDto

    /** Role: BOARD/ADMIN. `feeAmount`/`feeCurrency` are rejected if a non-CANCELLED/EXPIRED registration already exists -- see `EventDto.feeEditable`. */
    suspend fun updateEvent(
        id: String,
        input: EventInput,
    ): EventDto

    /** Role: BOARD/ADMIN. DRAFT -> PUBLISHED only; idempotent-by-rejection on an already-published/cancelled event. */
    suspend fun publishEvent(id: String): EventDto

    /** Role: BOARD/ADMIN. Cancels the event and mails every active (non-CANCELLED/EXPIRED) registrant. [reason] is included in that mail. */
    suspend fun cancelEvent(
        id: String,
        reason: String,
    ): EventDto

    /** Role: BOARD/ADMIN. Every registration, including CANCELLED/EXPIRED ones (for a full audit trail) -- NEVER exposed to a plain MEMBER (PII-leak boundary, see design-team plan). */
    suspend fun listRegistrations(eventId: String): List<EventRegistrationDto>

    /**
     * Role: any authenticated member. Self-registration for the calling member. The fee amount is
     * NEVER client-supplied -- see interface KDoc. Returns a [EventRegistrationResultDto] whose
     * `checkoutRedirectUrl` is set iff the event's fee is `> 0` and a seat (not a waitlist slot) was
     * granted.
     */
    suspend fun registerSelf(eventId: String): EventRegistrationResultDto

    /** Role: any authenticated member. Cancels the CALLING member's own registration for [eventId] -- never someone else's. */
    suspend fun cancelOwnRegistration(eventId: String): EventRegistrationDto

    /**
     * Role: BOARD/ADMIN. Manual fallback sweep (expire stale holds, promote the waitlist head) --
     * this codebase has no scheduler/background-job infrastructure, so every OTHER sweep trigger is
     * lazy (inside `EventCapacityGuard.withEventLock`, or the `checkout.session.expired` webhook).
     * This is the human-operated safety net for an event nobody happens to touch otherwise.
     */
    suspend fun sweepEvent(id: String): EventDto
}
