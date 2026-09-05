package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatusSets
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Reine Exposed-Datenzugriffsschicht für `event`/`event_registration` -- öffnet, wie jede
 * `*Store` in diesem Codebase, NIE eine eigene `transaction {}` (die Aufrufer --
 * `EventService`/`EventRegistrationSubmission`/`EventCapacityGuard`/`EventWaitlist` -- tun das).
 * Ausschließlich typisierte Exposed-Query-Builder, nie dynamisches SQL über Tabellen-/Spaltennamen.
 *
 * **`event_registration.status`/`.active_participant_key` werden AUSSCHLIESSLICH hier geschrieben**
 * -- no other file in this codebase may update either column.
 */
internal object EventStore {
    private const val MAX_PAGE_SIZE = 200

    // ── event ──────────────────────────────────────────────────────────────────────────────────

    fun getEventOrNull(id: Uuid): ResultRow? = EventTable.selectAll().where { EventTable.id eq id }.singleOrNull()

    fun getEventOrThrow(id: Uuid): ResultRow = getEventOrNull(id) ?: throw NotFoundException("Event $id not found")

    fun getEventBySlugOrNull(slug: String): ResultRow? = EventTable.selectAll().where { EventTable.slug eq slug }.singleOrNull()

    /** `FOR UPDATE` row lock on `event` -- see `EventCapacityGuard.withEventLock` KDoc for why every capacity-changing operation must hold this lock. */
    fun lockEventForUpdate(id: Uuid): ResultRow? =
        EventTable
            .selectAll()
            .where { EventTable.id eq id }
            .forUpdate()
            .singleOrNull()

    fun slugTaken(
        slug: String,
        excludingId: Uuid?,
    ): Boolean {
        var condition: Op<Boolean> = EventTable.slug eq slug
        if (excludingId != null) condition = condition and (EventTable.id neq excludingId)
        return EventTable.selectAll().where { condition }.any()
    }

    fun list(
        status: EventStatus?,
        includePast: Boolean,
        now: LocalDateTime,
        limit: Int,
        offset: Int,
    ): Pair<List<ResultRow>, Int> {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val effectiveOffset = offset.coerceAtLeast(0)
        var condition: Op<Boolean>? = null
        if (status != null) condition = (EventTable.status eq status).andWith(condition)
        if (!includePast) condition = (EventTable.endsAt greater now).andWith(condition)
        val fixed = condition

        fun query() = if (fixed != null) EventTable.selectAll().where { fixed } else EventTable.selectAll()
        val total = query().count().toInt()
        val rows =
            query()
                .orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.id to SortOrder.ASC)
                .limit(effectiveLimit)
                .offset(effectiveOffset.toLong())
                .toList()
        return rows to total
    }

    fun insertEvent(
        id: Uuid,
        slug: String,
        title: String,
        description: String,
        locationText: String?,
        onlineUrl: String?,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        capacity: Int?,
        feeAmount: BigDecimal,
        feeCurrency: String,
        visibility: EventVisibility,
        registrationClosesAt: LocalDateTime?,
        createdAt: LocalDateTime,
        createdBy: Uuid,
    ) {
        EventTable.insert {
            it[EventTable.id] = id
            it[EventTable.slug] = slug
            it[EventTable.title] = title
            it[EventTable.description] = description
            it[EventTable.locationText] = locationText
            it[EventTable.onlineUrl] = onlineUrl
            it[EventTable.startsAt] = startsAt
            it[EventTable.endsAt] = endsAt
            it[EventTable.capacity] = capacity
            it[EventTable.feeAmount] = feeAmount
            it[EventTable.feeCurrency] = feeCurrency
            it[status] = EventStatus.DRAFT
            it[EventTable.visibility] = visibility
            it[EventTable.registrationClosesAt] = registrationClosesAt
            it[EventTable.createdAt] = createdAt
            it[EventTable.createdBy] = createdBy
            it[cancelledAt] = null
        }
    }

    fun updateEvent(
        id: Uuid,
        title: String,
        description: String,
        locationText: String?,
        onlineUrl: String?,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        capacity: Int?,
        feeAmount: BigDecimal?,
        feeCurrency: String?,
        visibility: EventVisibility,
        registrationClosesAt: LocalDateTime?,
    ) {
        EventTable.update({ EventTable.id eq id }) {
            it[EventTable.title] = title
            it[EventTable.description] = description
            it[EventTable.locationText] = locationText
            it[EventTable.onlineUrl] = onlineUrl
            it[EventTable.startsAt] = startsAt
            it[EventTable.endsAt] = endsAt
            it[EventTable.capacity] = capacity
            if (feeAmount != null) it[EventTable.feeAmount] = feeAmount
            if (feeCurrency != null) it[EventTable.feeCurrency] = feeCurrency
            it[EventTable.visibility] = visibility
            it[EventTable.registrationClosesAt] = registrationClosesAt
        }
    }

    fun setStatus(
        id: Uuid,
        status: EventStatus,
        cancelledAt: LocalDateTime?,
    ) {
        EventTable.update({ EventTable.id eq id }) {
            it[EventTable.status] = status
            it[EventTable.cancelledAt] = cancelledAt
        }
    }

    // ── event_registration ─────────────────────────────────────────────────────────────────────

    fun getRegistrationOrNull(id: Uuid): ResultRow? =
        EventRegistrationTable
            .selectAll()
            .where {
                EventRegistrationTable.id eq id
            }.singleOrNull()

    fun getRegistrationOrThrow(id: Uuid): ResultRow =
        getRegistrationOrNull(id) ?: throw NotFoundException("Event registration $id not found")

    /** The seats currently occupied: CONFIRMED, or PENDING_PAYMENT with a hold that has not yet lazily expired. See `EventCapacityGuard` KDoc "Lazy-Expiry". */
    fun countOccupied(
        eventId: Uuid,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and
                    (
                        (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED) or
                            (
                                (EventRegistrationTable.status eq EventRegistrationStatus.PENDING_PAYMENT) and
                                    (EventRegistrationTable.holdExpiresAt greater now)
                            )
                    )
            }.count()
            .toInt()

    /** `true` iff any non-CANCELLED/EXPIRED registration exists -- the `EventDto.feeEditable`/`updateEvent` guard (see `EventPolicy`/`39-events.kuml.kts` file header "fee snapshot"). */
    fun hasNonInactiveRegistration(eventId: Uuid): Boolean =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and
                    (EventRegistrationTable.status notInList EventRegistrationStatusSets.INACTIVE.toList())
            }.any()

    fun countWaitlisted(eventId: Uuid): Int =
        EventRegistrationTable
            .selectAll()
            .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.status eq EventRegistrationStatus.WAITLISTED) }
            .count()
            .toInt()

    fun nextWaitlistPosition(eventId: Uuid): Int {
        val max =
            EventRegistrationTable
                .select(EventRegistrationTable.waitlistPosition)
                .where {
                    (EventRegistrationTable.eventId eq eventId) and
                        (EventRegistrationTable.status eq EventRegistrationStatus.WAITLISTED)
                }.orderBy(EventRegistrationTable.waitlistPosition to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(EventRegistrationTable.waitlistPosition)
        return (max ?: 0) + 1
    }

    fun findActiveByParticipantKey(
        eventId: Uuid,
        activeParticipantKey: String,
    ): ResultRow? =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.activeParticipantKey eq activeParticipantKey)
            }.singleOrNull()

    /** The calling member's own active (non-CANCELLED/EXPIRED) registration for [eventId], if any. */
    fun findOwnActiveRegistration(
        eventId: Uuid,
        memberId: Uuid,
    ): ResultRow? =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and
                    (EventRegistrationTable.memberId eq memberId) and
                    (EventRegistrationTable.status notInList EventRegistrationStatusSets.INACTIVE.toList())
            }.singleOrNull()

    fun listByEvent(eventId: Uuid): List<ResultRow> =
        EventRegistrationTable
            .selectAll()
            .where { EventRegistrationTable.eventId eq eventId }
            .orderBy(EventRegistrationTable.registeredAt to SortOrder.ASC)
            .toList()

    /**
     * Security-Review LOW fix (Welle V1.4.3.2 Fix-Runde): [EventService.openCheckIn] re-calls
     * [listByEvent] after EVERY successful door scan (see its own KDoc) and its result feeds both
     * the JSON roster response AND [memberInfoByIds]'s `inList`. Uncapped, an event with an
     * unusually large confirmed-registration count would (a) make each roster refresh grow the
     * response body/egress without bound, scaled by the 240/min check-in-rate budget, and (b) could
     * in principle hand [memberInfoByIds] more distinct member ids than Postgres' bind-parameter
     * limit (65535) tolerates in one `inList`. [MAX_CHECKIN_ROSTER_SIZE] is a hard ceiling, not a
     * navigable page -- the door desk needs the WHOLE roster to look up any registrant by name/code,
     * so this deliberately does not paginate (a paginated roster would hide registrants from the
     * check-in screen, which is worse than the DoS risk it defends against). It stays generous
     * enough to be a structural backstop rather than a real-world limit; [listByEvent]'s other two
     * callers ([EventService.cancelEvent], [EventService.listRegistrations]) must still see every
     * row and therefore keep calling the uncapped [listByEvent] above.
     */
    private const val MAX_CHECKIN_ROSTER_SIZE = 10_000

    fun listByEventForCheckIn(eventId: Uuid): List<ResultRow> =
        EventRegistrationTable
            .selectAll()
            .where { EventRegistrationTable.eventId eq eventId }
            .orderBy(EventRegistrationTable.registeredAt to SortOrder.ASC)
            .limit(MAX_CHECKIN_ROSTER_SIZE)
            .toList()

    fun insertRegistration(
        id: Uuid,
        eventId: Uuid,
        memberId: Uuid?,
        guestName: String?,
        guestEmail: String?,
        activeParticipantKey: String?,
        status: EventRegistrationStatus,
        feeAmount: BigDecimal,
        holdExpiresAt: LocalDateTime?,
        waitlistPosition: Int?,
        cancelTokenSha256: String?,
        registeredAt: LocalDateTime,
        confirmedAt: LocalDateTime?,
        // Welle V1.4.3.2 -- set together, only when `status == CONFIRMED` (a brand-new, immediately-
        // confirmed free registration); both `null` otherwise. See `EventTicketIssuer`/
        // `EventRegistrationSubmission` call site.
        ticketCodeSha256: String? = null,
        ticketIssuedAt: LocalDateTime? = null,
    ) {
        EventRegistrationTable.insert {
            it[EventRegistrationTable.id] = id
            it[EventRegistrationTable.eventId] = eventId
            it[EventRegistrationTable.memberId] = memberId
            it[EventRegistrationTable.guestName] = guestName
            it[EventRegistrationTable.guestEmail] = guestEmail
            it[EventRegistrationTable.activeParticipantKey] = activeParticipantKey
            it[EventRegistrationTable.status] = status
            it[EventRegistrationTable.feeAmount] = feeAmount
            it[EventRegistrationTable.holdExpiresAt] = holdExpiresAt
            it[EventRegistrationTable.waitlistPosition] = waitlistPosition
            it[EventRegistrationTable.cancelTokenSha256] = cancelTokenSha256
            it[EventRegistrationTable.registeredAt] = registeredAt
            it[EventRegistrationTable.confirmedAt] = confirmedAt
            it[cancelledAt] = null
            it[waitlistOfferedAt] = null
            it[EventRegistrationTable.ticketCodeSha256] = ticketCodeSha256
            it[EventRegistrationTable.ticketIssuedAt] = ticketIssuedAt
        }
    }

    /** Lazily flips every stale `PENDING_PAYMENT` hold on [eventId] to `EXPIRED` -- see `EventCapacityGuard`/`EventWaitlist` KDoc "this codebase has no scheduler". Returns the number of rows flipped. */
    fun expireStaleHolds(
        eventId: Uuid,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.eventId eq eventId) and
                (EventRegistrationTable.status eq EventRegistrationStatus.PENDING_PAYMENT) and
                (EventRegistrationTable.holdExpiresAt less now)
        }) {
            it[status] = EventRegistrationStatus.EXPIRED
            it[activeParticipantKey] = null
        }

    fun confirmRegistration(
        id: Uuid,
        now: LocalDateTime,
    ) {
        EventRegistrationTable.update({ EventRegistrationTable.id eq id }) {
            it[status] = EventRegistrationStatus.CONFIRMED
            it[confirmedAt] = now
        }
    }

    /** Guarded: only a still-`PENDING_PAYMENT` row is confirmed -- returns the affected row count (0 means the hold already expired/was cancelled, see `PspWebhookIngestion` race KDoc). */
    fun confirmRegistrationIfPending(
        id: Uuid,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.id eq id) and (EventRegistrationTable.status eq EventRegistrationStatus.PENDING_PAYMENT)
        }) {
            it[status] = EventRegistrationStatus.CONFIRMED
            it[confirmedAt] = now
        }

    /**
     * Welle V1.4.3.2 -- the paid-registration counterpart of [confirmRegistrationIfPending]: flips
     * PENDING_PAYMENT -> CONFIRMED AND writes the ticket in the SAME statement/transaction, so a
     * paying registrant's row is never observably CONFIRMED-without-a-ticket even for an instant.
     * Used exclusively by `PspWebhookIngestion.ingestCheckoutCompleted`'s EVENT_FEE branch --
     * `confirmRegistrationIfPending` itself stays in place, unmodified, for any other/older caller.
     */
    fun confirmRegistrationIfPendingAndIssueTicket(
        id: Uuid,
        ticketCodeSha256: String,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.id eq id) and (EventRegistrationTable.status eq EventRegistrationStatus.PENDING_PAYMENT)
        }) {
            it[status] = EventRegistrationStatus.CONFIRMED
            it[confirmedAt] = now
            it[EventRegistrationTable.ticketCodeSha256] = ticketCodeSha256
            it[ticketIssuedAt] = now
        }

    /** Moves a `PENDING_PAYMENT`/`WAITLISTED`/`CONFIRMED` registration to `CANCELLED`, freeing its `active_participant_key`. */
    fun cancelRegistration(
        id: Uuid,
        now: LocalDateTime,
    ) {
        EventRegistrationTable.update({ EventRegistrationTable.id eq id }) {
            it[status] = EventRegistrationStatus.CANCELLED
            it[activeParticipantKey] = null
            it[cancelledAt] = now
        }
    }

    /**
     * Marks a still-`PENDING_PAYMENT` registration `EXPIRED` (a Stripe session that expired
     * unconfirmed). Guarded on TWO conditions -- returns the affected row count (0 if the row was
     * already CANCELLED/CONFIRMED/EXPIRED by the time this ran, OR its [holdExpiresAt] has not
     * actually elapsed yet): (Review MAJOR fix) this used to expire the row unconditionally the
     * moment Stripe's OWN `checkout.session.expired` webhook arrived, silently assuming Stripe's
     * session lifetime and this row's `hold_expires_at` were the same duration -- true for the
     * `STANDARD_HOLD` (30 min, shorter than Stripe's ~24h default) but false for a waitlist
     * promotion's `WAITLIST_OFFER_WINDOW` (48h, LONGER than Stripe's default): Stripe would expire
     * its session ~24h into that 48h window and this call would evict the registrant a full day
     * before the hold they were promised actually ran out. Checking `holdExpiresAt <= now` here
     * makes this call a no-op in that case -- the row stays `PENDING_PAYMENT`, a fresh checkout
     * session can still be started against it (`EventRegistrationSubmission.resumeCheckout`), and
     * `EventStore.expireStaleHolds`/`EventCapacityGuard.withEventLock`'s own lazy sweep (which DOES
     * compare `hold_expires_at` to `now`) is what actually evicts it once the real hold elapses.
     */
    fun expireRegistrationIfPending(
        id: Uuid,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.id eq id) and
                (EventRegistrationTable.status eq EventRegistrationStatus.PENDING_PAYMENT) and
                (EventRegistrationTable.holdExpiresAt lessEq now)
        }) {
            it[status] = EventRegistrationStatus.EXPIRED
            it[activeParticipantKey] = null
        }

    /**
     * Promotes the waitlist head (lowest `waitlist_position`) to `PENDING_PAYMENT` with a fresh hold
     * -- see `EventWaitlist` KDoc. Does NOT re-count capacity -- the caller already established a
     * seat is free. [cancelTokenSha256] REPLACES the registration's existing token (Review MAJOR
     * fix): the original token was only ever handed out in this registration's very first
     * "you're on the waitlist" mail and is never persisted in plaintext (only its hash, see
     * `findByCancelTokenHash` KDoc), so it cannot be recovered here to build a payment-resume link --
     * a fresh token serves as BOTH the storno token AND the payment-resume token going forward (both
     * routes look a registration up by this SAME column), deliberately superseding the old one rather
     * than leaving two valid tokens for the same row.
     */
    fun promoteToPendingPayment(
        id: Uuid,
        holdExpiresAt: LocalDateTime,
        activeParticipantKey: String,
        cancelTokenSha256: String,
        now: LocalDateTime,
    ) {
        EventRegistrationTable.update({ EventRegistrationTable.id eq id }) {
            it[status] = EventRegistrationStatus.PENDING_PAYMENT
            it[EventRegistrationTable.holdExpiresAt] = holdExpiresAt
            it[EventRegistrationTable.activeParticipantKey] = activeParticipantKey
            it[EventRegistrationTable.cancelTokenSha256] = cancelTokenSha256
            it[waitlistOfferedAt] = now
        }
    }

    /**
     * A free event's waitlist promotion confirms directly -- there is nothing to pay. [ticketCodeSha256]
     * is written in the SAME statement (Welle V1.4.3.2), same "never observably CONFIRMED without a
     * ticket" reasoning as [confirmRegistrationIfPendingAndIssueTicket].
     */
    fun promoteToConfirmedDirectly(
        id: Uuid,
        activeParticipantKey: String,
        ticketCodeSha256: String,
        now: LocalDateTime,
    ) {
        EventRegistrationTable.update({ EventRegistrationTable.id eq id }) {
            it[status] = EventRegistrationStatus.CONFIRMED
            it[EventRegistrationTable.activeParticipantKey] = activeParticipantKey
            it[confirmedAt] = now
            it[waitlistOfferedAt] = now
            it[EventRegistrationTable.ticketCodeSha256] = ticketCodeSha256
            it[ticketIssuedAt] = now
        }
    }

    fun findWaitlistHead(eventId: Uuid): ResultRow? =
        EventRegistrationTable
            .selectAll()
            .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.status eq EventRegistrationStatus.WAITLISTED) }
            .orderBy(EventRegistrationTable.waitlistPosition to SortOrder.ASC)
            .limit(1)
            .firstOrNull()

    fun findByCancelTokenHash(
        eventId: Uuid,
        cancelTokenSha256: String,
    ): ResultRow? =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and
                    (EventRegistrationTable.cancelTokenSha256 eq cancelTokenSha256) and
                    (EventRegistrationTable.status notInList EventRegistrationStatusSets.INACTIVE.toList())
            }.singleOrNull()

    /** Best-effort lookup: the [PaymentTransactionTable] row (if any) whose checkout session belongs to [registrationId], most-recently-created first. */
    fun findPaymentInfo(registrationId: Uuid): Pair<Uuid?, Uuid?> {
        val row =
            PaymentCheckoutSessionTable
                .join(
                    PaymentTransactionTable,
                    JoinType.LEFT,
                    PaymentCheckoutSessionTable.id,
                    PaymentTransactionTable.checkoutSessionId,
                ).select(PaymentTransactionTable.id, PaymentTransactionTable.journalEntryId)
                .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                .orderBy(PaymentCheckoutSessionTable.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull() ?: return null to null
        return row.getOrNull(PaymentTransactionTable.id) to row.getOrNull(PaymentTransactionTable.journalEntryId)
    }

    fun memberDisplayNameOrNull(memberId: Uuid): String? =
        MemberTable
            .select(MemberTable.displayName)
            .where { MemberTable.id eq memberId }
            .firstOrNull()
            ?.get(MemberTable.displayName)

    fun memberEmailOrNull(memberId: Uuid): String? =
        MemberTable
            .select(MemberTable.email)
            .where { MemberTable.id eq memberId }
            .firstOrNull()
            ?.get(MemberTable.email)

    /** Display name + e-mail, bulk-resolved for a whole set of member ids in ONE query. */
    data class MemberInfo(
        val displayName: String,
        val email: String,
    )

    /**
     * Bulk counterpart of [memberDisplayNameOrNull]/[memberEmailOrNull] -- Security-Review MAJOR
     * fix (Welle V1.4.3.2 Fix-Runde): `IEventService.openCheckIn` used to call those two
     * single-row lookups (plus a THIRD for `checkedInBy`) once per registration row, an N+1 inside
     * one transaction. An event with thousands of confirmed registrations turned every roster
     * refresh into thousands of extra round-trips against the same DB connection -- and the
     * check-in screen re-calls `openCheckIn` after every single scan on a 240/min budget, so a
     * busy door (several helpers scanning in parallel) could exhaust the connection pool for the
     * whole server, not just check-in. Callers collect every `memberId`/`checkedInBy` id they need
     * up front and resolve them all here at once; an empty [memberIds] is a deliberate no-op
     * (skips the round-trip entirely -- relevant for an all-guest event).
     */
    fun memberInfoByIds(memberIds: Collection<Uuid>): Map<Uuid, MemberInfo> {
        if (memberIds.isEmpty()) return emptyMap()
        return MemberTable
            .select(MemberTable.id, MemberTable.displayName, MemberTable.email)
            .where { MemberTable.id inList memberIds.distinct() }
            .associate { it[MemberTable.id] to MemberInfo(displayName = it[MemberTable.displayName], email = it[MemberTable.email]) }
    }

    // ── event_registration ticketing/check-in (Welle V1.4.3.2) ────────────────────────────────────

    fun findByTicketCodeHash(ticketCodeSha256: String): ResultRow? =
        EventRegistrationTable.selectAll().where { EventRegistrationTable.ticketCodeSha256 eq ticketCodeSha256 }.singleOrNull()

    /** Guarded: only a CONFIRMED row with NO ticket yet is affected -- returns the row count so the caller ([EventTicketIssuer]) can distinguish a genuine issuance from a no-op. */
    fun issueTicketIfMissing(
        id: Uuid,
        ticketCodeSha256: String,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.id eq id) and
                (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED) and
                (EventRegistrationTable.ticketCodeSha256.isNull())
        }) {
            it[EventRegistrationTable.ticketCodeSha256] = ticketCodeSha256
            it[ticketIssuedAt] = now
        }

    /** Rotation -- only a CONFIRMED row is affected, overwrites any existing hash. */
    fun rotateTicket(
        id: Uuid,
        ticketCodeSha256: String,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.id eq id) and (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED)
        }) {
            it[EventRegistrationTable.ticketCodeSha256] = ticketCodeSha256
            it[ticketIssuedAt] = now
        }

    /**
     * Atomic check-in transition -- see `EventCheckIn.byCode`/`byRegistration` KDoc "verbindliche
     * Reihenfolge" step 7 for why this must be one guarded UPDATE, not a read-then-write.
     *
     * The `ticketCodeSha256.isNotNull()` guard (Security-Review LOW fix) mirrors
     * `chk_event_registration_checkin_ticket` (`V19__event_tickets.sql`) -- without it, checking in
     * a CONFIRMED row that has no ticket yet (a legacy V1.4.3.1-era row, or one for which
     * `EventTicketIssuer.issueIfMissing` returned `null`) would violate that CHECK constraint and
     * throw an uncaught [org.jetbrains.exposed.v1.exceptions.ExposedSQLException] straight out of
     * this `UPDATE`, aborting the whole RPC transaction as an unhandled 500 instead of the guard
     * simply matching 0 rows the way every other "can this transition happen at all" guard here
     * does. `EventCheckIn.classifyAndCheckIn` calls `EventTicketIssuer.issueIfMissing` first
     * precisely so this guard essentially never actually excludes a row in practice -- this is the
     * belt to that suspenders, matching the DB's own invariant exactly rather than relying solely
     * on the call-site ordering.
     */
    fun checkInIfNotCheckedIn(
        id: Uuid,
        byMemberId: Uuid,
        now: LocalDateTime,
    ): Int =
        EventRegistrationTable.update({
            (EventRegistrationTable.id eq id) and
                (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED) and
                (EventRegistrationTable.checkedInAt.isNull()) and
                (EventRegistrationTable.ticketCodeSha256.isNotNull())
        }) {
            it[checkedInAt] = now
            it[checkedInBy] = byMemberId
        }

    /** Every CONFIRMED row on [eventId] without a ticket -- the input set for `IEventService.openCheckIn`'s idempotent nachausstellung sweep. */
    fun listConfirmedWithoutTicket(eventId: Uuid): List<ResultRow> =
        EventRegistrationTable
            .selectAll()
            .where {
                (EventRegistrationTable.eventId eq eventId) and
                    (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED) and
                    (EventRegistrationTable.ticketCodeSha256.isNull())
            }.toList()
}

/** ANDs [this] onto [existing] (or returns [this] alone if [existing] is `null`) -- same idiom `CrmContactStore`'s own `andWith` establishes. */
private fun Op<Boolean>.andWith(existing: Op<Boolean>?): Op<Boolean> = existing?.and(this) ?: this
