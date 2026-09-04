package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.payment.psp.PspCheckoutSessions
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.payment.psp.StripeCheckoutResult
import network.lapis.cloud.server.payment.psp.StripeReturnUrls
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.paymentGatewayDisclaimerIsCurrentlyAcknowledged
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Which person is registering -- one shared submission path serves both the member-RPC and the public-guest route. */
internal sealed interface EventParticipant {
    data class Member(
        val memberId: Uuid,
        val displayName: String,
        val email: String,
    ) : EventParticipant

    data class Guest(
        val name: String,
        val normalizedEmail: String,
    ) : EventParticipant
}

/** Outcome of [EventRegistrationSubmission.submit]. */
internal sealed interface EventRegistrationResult {
    data class Confirmed(
        val registrationId: Uuid,
    ) : EventRegistrationResult

    data class PaymentRequired(
        val registrationId: Uuid,
        val redirectUrl: String,
    ) : EventRegistrationResult

    data class Waitlisted(
        val registrationId: Uuid,
        val position: Int,
    ) : EventRegistrationResult

    /** The event exists but is not currently open for registration: not `PUBLISHED`, past its registration deadline, or already started. Deliberately ONE result for all three -- no oracle for an anonymous caller. */
    data object EventNotAvailable : EventRegistrationResult

    data object AlreadyRegistered : EventRegistrationResult

    data object WaitlistFull : EventRegistrationResult

    /** Never reveals WHICH precondition (gateway disabled/misconfigured/disclaimer not acknowledged) failed -- same posture `AnonymousDonationCheckout`'s own `GatewayUnavailable` establishes. */
    data object GatewayUnavailable : EventRegistrationResult

    data class StripeFailed(
        val message: String,
    ) : EventRegistrationResult
}

/**
 * Welle V1.4.3.1 -- the fachlogik shared by BOTH registration paths (`IEventService.registerSelf`
 * for members, the server-rendered public `<form>` for guests). Honeypot/rate-limiting are the
 * CALLER's responsibility (only the public route has either) and must run BEFORE [submit] is ever
 * called -- see `network.lapis.cloud.server.routes.registerEventPublicRoutes` KDoc.
 *
 * **Verbindliche Reihenfolge inside [submit], any reordering is a review blocker:**
 * 1. Resolve the event + a CHEAP, pre-lock registration-window check (`EventPolicy
 *    .isRegistrationOpen`) -> [EventRegistrationResult.EventNotAvailable]. This is only an early
 *    exit for the common case -- see step 3 for the AUTHORITATIVE re-check.
 * 2. Gateway-availability check -- ONLY when the event's fee (pre-lock estimate) is `> 0`. Never
 *    reveals which precondition failed.
 * 3. `[EventCapacityGuard.withEventLock]`: re-checks `EventPolicy.isRegistrationOpen` against the
 *    LOCKED event row (Review MAJOR fix -- an `EventService.cancelEvent` may have committed between
 *    step 1's read and this lock acquisition; without this re-check, a registration could be
 *    inserted against an event that is, by the time this transaction commits, already CANCELLED,
 *    with its holder never receiving a cancellation mail), re-reads `event.fee_amount` from that
 *    SAME locked row (see [network.lapis.cloud.shared.rpc.IEventService] KDoc -- "always reads the
 *    fee exclusively ... under the event row lock" is a guarantee about THIS read, not step 1's),
 *    expires stale holds, sweeps the waitlist for OTHER registrants (unrelated to this call),
 *    duplicate check via `active_participant_key`, then either places a seat (`PENDING_PAYMENT` if
 *    the fee is `> 0`, else `CONFIRMED` immediately) or joins the waitlist (capped at
 *    [EventPolicy.MAX_WAITLIST]).
 * 4. Free event / waitlisted -> done, mail sent after the transaction commits.
 * 5. Fee `> 0` and a seat was placed -> the Stripe call happens OUTSIDE any transaction (never holds
 *    a DB lock across a network round-trip). A failure immediately frees the seat again AND
 *    re-sweeps the waitlist for a successor, both under a fresh [EventCapacityGuard.withEventLock]
 *    acquisition (Review MAJOR fix -- a bare `EventStore.cancelRegistration` in its own
 *    lock-free `transaction {}` used to free the seat without ever promoting a waitlisted
 *    registrant onto it) -- **never** leaves a `PENDING_PAYMENT` row occupying a seat nobody can
 *    ever pay for, and never leaves a freed seat unswept.
 *
 * **Deliberate divergence from `AnonymousDonationCheckout`'s own "Stripe call BEFORE persistence"
 * rule.** That class's own KDoc explains its ordering exists so a Stripe failure never leaves a
 * dangling `external_donor` row -- correct there because a donation has no scarce resource to
 * protect. An event SEAT is scarce: calling Stripe before reserving the seat would let two
 * concurrent registrants both be sent to Stripe for the LAST seat, and one of them would pay for a
 * seat that no longer exists. The V1.4.1b rule's actual goal ("never leave garbage behind") is still
 * honored -- step 5's failure branch frees the seat immediately -- just via a different mechanism
 * suited to a capacity-bounded resource. **This reasoning must not be "corrected" back to
 * Stripe-before-persistence without re-reading this paragraph.**
 *
 * `MailDispatcher.enqueue` is NEVER called from inside a `transaction {}` -- see
 * `EventCapacityGuard` KDoc.
 */
internal class EventRegistrationSubmission(
    private val pspConfigState: PspConfigState,
    private val checkoutClient: StripeCheckoutClient?,
    private val baseUrl: String,
    private val mailDispatcher: MailDispatcher,
) {
    suspend fun submit(
        eventId: Uuid,
        participant: EventParticipant,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
    ): EventRegistrationResult {
        // 1. Resolve + a CHEAP pre-lock registration-window check (early exit for the common case
        // only -- step 3 re-checks this authoritatively against the LOCKED row).
        val eventRow = transaction { EventStore.getEventOrNull(eventId) } ?: return EventRegistrationResult.EventNotAvailable
        if (!EventPolicy.isRegistrationOpen(
                status = eventRow[EventTable.status],
                registrationClosesAt = eventRow[EventTable.registrationClosesAt],
                startsAt = eventRow[EventTable.startsAt],
                now = now,
            )
        ) {
            return EventRegistrationResult.EventNotAvailable
        }
        val slug = eventRow[EventTable.slug]
        val title = eventRow[EventTable.title]
        // Cheap pre-lock estimate only, to gate the network-bound gatewayUsable() check below
        // BEFORE the row lock is even taken -- the AUTHORITATIVE fee read happens under the lock in
        // step 3 (see class KDoc).
        val preLockGatewayNeeded = eventRow[EventTable.feeAmount].compareTo(BigDecimal.ZERO) != 0

        // 2. Gateway availability -- only when there is something to pay.
        if (preLockGatewayNeeded && !gatewayUsable()) {
            return EventRegistrationResult.GatewayUnavailable
        }

        // 3. Under the event lock: re-check the registration window, re-read the fee, duplicate
        // check, capacity decision, insert.
        val registrationId = Uuid.random()
        val cancelToken = randomCancelToken()
        val cancelTokenHash = sha256Hex(cancelToken.toByteArray(Charsets.US_ASCII))
        val (placement, promotions) =
            EventCapacityGuard.withEventLock(eventId = eventId, now = now) { lockedEvent ->
                if (!EventPolicy.isRegistrationOpen(
                        status = lockedEvent[EventTable.status],
                        registrationClosesAt = lockedEvent[EventTable.registrationClosesAt],
                        startsAt = lockedEvent[EventTable.startsAt],
                        now = now,
                    )
                ) {
                    return@withEventLock Placement.EventClosed
                }
                val feeAmount = lockedEvent[EventTable.feeAmount]
                val gatewayNeeded = feeAmount.compareTo(BigDecimal.ZERO) != 0
                val memberId = (participant as? EventParticipant.Member)?.memberId
                val guestEmail = (participant as? EventParticipant.Guest)?.normalizedEmail
                val key = EventPolicy.activeParticipantKey(memberId = memberId, normalizedGuestEmail = guestEmail)
                val existing = EventStore.findActiveByParticipantKey(eventId = eventId, activeParticipantKey = key)
                if (existing != null) return@withEventLock Placement.Duplicate

                val capacity = lockedEvent[EventTable.capacity]
                val occupied = EventStore.countOccupied(eventId = eventId, now = now)
                val hasSeat = capacity == null || occupied < capacity
                try {
                    if (hasSeat) {
                        val holdExpiresAt = if (gatewayNeeded) now.plusDuration(EventPolicy.STANDARD_HOLD) else null
                        val initialStatus =
                            if (gatewayNeeded) EventRegistrationStatus.PENDING_PAYMENT else EventRegistrationStatus.CONFIRMED
                        insertRegistration(
                            registrationId = registrationId,
                            eventId = eventId,
                            participant = participant,
                            key = key,
                            status = initialStatus,
                            feeAmount = feeAmount,
                            holdExpiresAt = holdExpiresAt,
                            waitlistPosition = null,
                            cancelTokenHash = cancelTokenHash,
                            now = now,
                            confirmedAt = if (gatewayNeeded) null else now,
                        )
                        Placement.Placed(needsPayment = gatewayNeeded, feeAmount = feeAmount)
                    } else {
                        val waitlistCount = EventStore.countWaitlisted(eventId)
                        if (waitlistCount >= EventPolicy.MAX_WAITLIST) return@withEventLock Placement.WaitlistFull
                        val position = EventStore.nextWaitlistPosition(eventId)
                        insertRegistration(
                            registrationId = registrationId,
                            eventId = eventId,
                            participant = participant,
                            key = key,
                            status = EventRegistrationStatus.WAITLISTED,
                            feeAmount = feeAmount,
                            holdExpiresAt = null,
                            waitlistPosition = position,
                            cancelTokenHash = cancelTokenHash,
                            now = now,
                            confirmedAt = null,
                        )
                        Placement.Waitlisted(position)
                    }
                } catch (e: ExposedSQLException) {
                    // Backstop against the unique index (uq_event_registration_active_participant) --
                    // the application-level `findActiveByParticipantKey` check above is racy on its
                    // own under concurrency outside this lock's normal discipline; same "pre-check +
                    // ExposedSQLException backstop" idiom `CrmContactStore.create` establishes.
                    // ONLY that specific unique-index violation is treated as a duplicate (Review
                    // MINOR fix) -- anything else (an FK violation, `chk_event_registration_identity`/
                    // `chk_event_registration_hold`, a `guest_name`/`guest_email` overflow on a
                    // caller that skipped the route-level length pre-check) is a genuine bug, not a
                    // race, and re-throwing it: (a) surfaces it as a logged 500 instead of a
                    // misleading "already registered" with NO log line at all, and (b) rolls back
                    // this whole `transaction {}` -- including `expireStaleHolds`/
                    // `promoteWhileCapacityFree` -- so `promotions` below is never populated for
                    // promotions that in truth never committed.
                    if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                        Placement.Duplicate
                    } else {
                        logger.error(e) {
                            "EventRegistrationSubmission: unexpected ExposedSQLException inserting a registration " +
                                "for event $eventId (registration $registrationId) -- not a unique-participant " +
                                "violation, re-thrown rather than silently treated as AlreadyRegistered."
                        }
                        throw e
                    }
                }
            }

        // Mail every OTHER registrant this same lock acquisition happened to promote off the
        // waitlist -- unrelated to this call's own outcome, see EventCapacityGuard KDoc.
        promotions.forEach { it.mailPromotion(mailDispatcher) }

        return when (placement) {
            Placement.Duplicate -> EventRegistrationResult.AlreadyRegistered
            Placement.WaitlistFull -> EventRegistrationResult.WaitlistFull
            Placement.EventClosed -> EventRegistrationResult.EventNotAvailable
            is Placement.Waitlisted -> {
                mailRegistrationReceived(
                    participant = participant,
                    title = title,
                    slug = slug,
                    cancelToken = cancelToken,
                    mode = RegistrationMailMode.WAITLISTED,
                )
                EventRegistrationResult.Waitlisted(registrationId = registrationId, position = placement.position)
            }
            is Placement.Placed -> {
                if (!placement.needsPayment) {
                    mailRegistrationReceived(
                        participant = participant,
                        title = title,
                        slug = slug,
                        cancelToken = cancelToken,
                        mode = RegistrationMailMode.CONFIRMED,
                    )
                    EventRegistrationResult.Confirmed(registrationId = registrationId)
                } else {
                    createCheckoutOrFreeSeat(
                        eventId = eventId,
                        registrationId = registrationId,
                        participant = participant,
                        title = title,
                        slug = slug,
                        cancelToken = cancelToken,
                        feeAmount = placement.feeAmount,
                        now = now,
                    )
                }
            }
        }
    }

    /** Step 5 -- the Stripe call, deliberately outside any transaction (see class KDoc). */
    private suspend fun createCheckoutOrFreeSeat(
        eventId: Uuid,
        registrationId: Uuid,
        participant: EventParticipant,
        title: String,
        slug: String,
        cancelToken: String,
        feeAmount: BigDecimal,
        now: LocalDateTime,
    ): EventRegistrationResult {
        val outcome =
            startStripeCheckout(
                eventId = eventId,
                registrationId = registrationId,
                slug = slug,
                feeAmount = feeAmount,
                now = now,
                freeSeatOnFailure = true,
                // A brand-new registration's hold is always the flat EventPolicy.STANDARD_HOLD
                // (set at insertRegistration-time, see the `submit` step above) -- never a waitlist
                // promotion's 48h WAITLIST_OFFER_WINDOW, which only ever applies to resumeCheckout.
                holdExpiresAt = now.plusDuration(EventPolicy.STANDARD_HOLD),
            )
        return when (outcome) {
            is CheckoutOutcome.Success -> {
                // Sent only once the checkout session genuinely exists -- a Stripe failure inside
                // startStripeCheckout already cancelled the registration before reaching here, so no
                // "please pay" mail is ever sent for a seat that was immediately freed again.
                mailRegistrationReceived(
                    participant = participant,
                    title = title,
                    slug = slug,
                    cancelToken = cancelToken,
                    mode = RegistrationMailMode.PAYMENT_PENDING,
                )
                EventRegistrationResult.PaymentRequired(registrationId = registrationId, redirectUrl = outcome.redirectUrl)
            }
            CheckoutOutcome.GatewayMissing -> EventRegistrationResult.GatewayUnavailable
            is CheckoutOutcome.Failed -> EventRegistrationResult.StripeFailed(outcome.message)
        }
    }

    /**
     * Resumes payment for an ALREADY-EXISTING `PENDING_PAYMENT` registration -- the missing
     * counterpart Review MAJOR fix found: a waitlist registrant promoted to `PENDING_PAYMENT` on a
     * paid event (`EventWaitlist.promoteWhileCapacityFree`) previously had no code path through which
     * to ever pay for the seat just granted. Reached exclusively via `registerEventPublicRoutes`'s
     * `POST /veranstaltung/{slug}/zahlung`, which has already looked the registration up by its
     * payment-resume token (the SAME token/column `submit`'s own cancel flow uses, see
     * `EventStore.findByCancelTokenHash`) and verified it is still `PENDING_PAYMENT` -- this function
     * re-verifies BOTH facts again, under a fresh event-row lock (same "cheap pre-check + AUTHORITATIVE
     * re-check under the lock" discipline [submit] step 3 documents): `withEventLock`'s own
     * `expireStaleHolds` call may have expired this exact hold moments after the caller's own read.
     * ALSO re-checks [EventPolicy.isRegistrationOpen] against the locked event row (Review MAJOR
     * fix -- this check used to be entirely absent here, unlike every other entry point into this
     * class: a waitlist promotion's [EventPolicy.WAITLIST_OFFER_WINDOW] hold is 48h, long enough to
     * outlive the event itself, so without this a registrant could still pay -- and Stripe would
     * still confirm the seat via the webhook -- for an event that has already started or been
     * cancelled). Deliberately does NOT send another "please pay" mail on success -- the caller's
     * browser is redirected straight to Stripe, so a second mail would be redundant noise, and
     * (unlike a brand new registration) there is no fresh [randomCancelToken] here to build one from
     * anyway. Passes `freeSeatOnFailure = false` to [startStripeCheckout] (Review MAJOR fix -- see
     * that parameter's own KDoc): unlike a brand-new registration, a resumed one already holds a
     * legitimately-granted seat, and a transient Stripe error here must leave it `PENDING_PAYMENT`
     * (so the hold's own expiry / another click on the same link can still resolve it) rather than
     * cancelling it outright and handing the seat to the next waitlist entry.
     */
    suspend fun resumeCheckout(
        eventId: Uuid,
        registrationId: Uuid,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
    ): EventRegistrationResult {
        if (!gatewayUsable()) return EventRegistrationResult.GatewayUnavailable
        val (feeAndHold, promotions) =
            EventCapacityGuard.withEventLock(eventId = eventId, now = now) { lockedEvent ->
                val registration = EventStore.getRegistrationOrNull(registrationId)
                val registrationOpen =
                    EventPolicy.isRegistrationOpen(
                        status = lockedEvent[EventTable.status],
                        registrationClosesAt = lockedEvent[EventTable.registrationClosesAt],
                        startsAt = lockedEvent[EventTable.startsAt],
                        now = now,
                    )
                when {
                    registration == null -> null
                    registration[EventRegistrationTable.eventId] != eventId -> null
                    registration[EventRegistrationTable.status] != EventRegistrationStatus.PENDING_PAYMENT -> null
                    !registrationOpen -> null
                    // Review MINOR fix ("Session-Dedup greift nur 30 Minuten"): carries the
                    // registration's REAL hold (up to 48h for a waitlist promotion) out of the lock
                    // alongside the fee, so startStripeCheckout can bind the checkout session's
                    // local expires_at to it instead of always assuming the flat 30-minute default.
                    else -> registration[EventRegistrationTable.feeAmount] to registration[EventRegistrationTable.holdExpiresAt]
                }
            }
        promotions.forEach { it.mailPromotion(mailDispatcher) }
        val (fee, holdExpiresAt) = feeAndHold ?: return EventRegistrationResult.EventNotAvailable
        val slug = transaction { EventStore.getEventOrThrow(eventId)[EventTable.slug] }
        val outcome =
            startStripeCheckout(
                eventId = eventId,
                registrationId = registrationId,
                slug = slug,
                feeAmount = fee,
                now = now,
                freeSeatOnFailure = false,
                holdExpiresAt = holdExpiresAt,
            )
        return when (outcome) {
            is CheckoutOutcome.Success ->
                EventRegistrationResult.PaymentRequired(registrationId = registrationId, redirectUrl = outcome.redirectUrl)
            CheckoutOutcome.GatewayMissing -> EventRegistrationResult.GatewayUnavailable
            is CheckoutOutcome.Failed -> EventRegistrationResult.StripeFailed(outcome.message)
        }
    }

    /**
     * The Stripe-call-only portion previously inline in [createCheckoutOrFreeSeat] -- extracted so
     * [resumeCheckout] can share it without also sending [createCheckoutOrFreeSeat]'s own
     * `PAYMENT_PENDING` confirmation mail (see that function's own KDoc for why resuming sends none).
     *
     * First reuses a still-valid, not-yet-expired `CREATED` `payment_checkout_session` for
     * [registrationId] if one exists (Review MINOR fix -- same "reuse instead of minting a second
     * session" idiom `PaymentGatewayService.createContributionCheckout` already establishes via
     * `PspCheckoutSessions.findReusableForContribution`): without this, two clicks on the SAME
     * payment-resume link within the hold window each mint their own Stripe session for the same
     * registration, and nothing ever supersedes the older one -- whichever session's
     * `checkout.session.completed` webhook loses the race to `confirmRegistrationIfPending`'s guard
     * (only a still-`PENDING_PAYMENT` row confirms) lands as a genuine payment with NO seat behind
     * it. A no-op for [createCheckoutOrFreeSeat]'s own caller -- a brand-new [registrationId] can
     * never already have a session.
     *
     * On any failure to obtain a session (missing client or a Stripe-side rejection), [freeSeatOnFailure]
     * decides what happens to the seat: `true` (only [createCheckoutOrFreeSeat]'s own, brand-new
     * registration) frees it and sweeps the waitlist for a successor -- same discipline the
     * pre-extraction code already established (see class KDoc step 5). `false` ([resumeCheckout]'s
     * own call, Review MAJOR fix) leaves the registration `PENDING_PAYMENT` instead: it already
     * holds a LEGITIMATELY granted seat (a waitlist promotion), so cancelling it over a merely
     * transient Stripe/network hiccup would strip that seat from someone who did nothing wrong and
     * hand it to the next waitlist entry -- the existing hold-expiry sweep (`EventStore
     * .expireStaleHolds`) is what should decide its fate, not one failed HTTP call.
     */
    private suspend fun startStripeCheckout(
        eventId: Uuid,
        registrationId: Uuid,
        slug: String,
        feeAmount: BigDecimal,
        now: LocalDateTime,
        freeSeatOnFailure: Boolean,
        holdExpiresAt: LocalDateTime?,
    ): CheckoutOutcome {
        val reusable = transaction { PspCheckoutSessions.findReusableForRegistration(eventRegistrationId = registrationId, now = now) }
        val reusableRedirectUrl = reusable?.get(PaymentCheckoutSessionTable.redirectUrl)
        if (reusableRedirectUrl != null) {
            return CheckoutOutcome.Success(reusableRedirectUrl)
        }
        val client = checkoutClient
        if (client == null) {
            if (freeSeatOnFailure) freeSeatAndSweepWaitlist(eventId = eventId, registrationId = registrationId, now = now)
            return CheckoutOutcome.GatewayMissing
        }
        val checkoutSessionId = Uuid.random()
        val returnUrls = StripeReturnUrls.eventRegistration(baseUrl = baseUrl, slug = slug, registrationId = registrationId.toString())
        val stripeResult =
            client.createCheckoutSession(
                checkoutSessionId = checkoutSessionId.toString(),
                amount = feeAmount.setScale(2, RoundingMode.UNNECESSARY),
                currency = "EUR",
                description = "Anmeldegebühr",
                returnUrls = returnUrls,
            )
        val success =
            stripeResult as? StripeCheckoutResult.Success ?: run {
                val failure = stripeResult as StripeCheckoutResult.Failure
                logger.warn { "EventRegistrationSubmission: Stripe checkout failed for registration $registrationId -- ${failure.message}" }
                if (freeSeatOnFailure) freeSeatAndSweepWaitlist(eventId = eventId, registrationId = registrationId, now = now)
                return CheckoutOutcome.Failed(failure.message)
            }
        transaction {
            // Review MINOR fix ("Session-Dedup greift nur 30 Minuten"): the LOCAL session's
            // expires_at now mirrors the registration's real hold (up to 48h for a waitlist
            // promotion, see EventPolicy.WAITLIST_OFFER_WINDOW) instead of always the flat
            // 30-minute EventPolicy.STANDARD_HOLD -- otherwise findReusableForRegistration()
            // stopped considering a still-payable Stripe session "reusable" long before Stripe
            // itself would refuse it, minting an avoidable second session for the same
            // registration (and, if the registrant later paid on BOTH, a double charge). Capped at
            // EventPolicy.STRIPE_SESSION_LIFETIME_CAP because Stripe's own Checkout Session expiry
            // is ~24h regardless of what we track locally.
            val cap = now.plusDuration(EventPolicy.STRIPE_SESSION_LIFETIME_CAP)
            val fallback = now.plusDuration(EventPolicy.STANDARD_HOLD)
            val expiresAt = (holdExpiresAt ?: fallback).let { if (it < cap) it else cap }
            PspCheckoutSessions.create(
                id = checkoutSessionId,
                provider = PaymentProvider.STRIPE,
                providerSessionId = success.sessionId,
                intent = PaymentIntent.EVENT_FEE,
                contributionId = null,
                memberId = null,
                externalDonorId = null,
                eventRegistrationId = registrationId,
                embedOrigin = null,
                amount = feeAmount.setScale(2, RoundingMode.UNNECESSARY),
                currency = "EUR",
                donorCategory = null,
                purpose = null,
                createdAt = now,
                expiresAt = expiresAt,
                providerIdempotencyKey = success.idempotencyKey,
                redirectUrl = success.redirectUrl,
            )
        }
        return CheckoutOutcome.Success(success.redirectUrl)
    }

    private sealed interface CheckoutOutcome {
        data class Success(
            val redirectUrl: String,
        ) : CheckoutOutcome

        data object GatewayMissing : CheckoutOutcome

        data class Failed(
            val message: String,
        ) : CheckoutOutcome
    }

    private fun insertRegistration(
        registrationId: Uuid,
        eventId: Uuid,
        participant: EventParticipant,
        key: String,
        status: EventRegistrationStatus,
        feeAmount: BigDecimal,
        holdExpiresAt: LocalDateTime?,
        waitlistPosition: Int?,
        cancelTokenHash: String,
        now: LocalDateTime,
        confirmedAt: LocalDateTime?,
    ) {
        EventStore.insertRegistration(
            id = registrationId,
            eventId = eventId,
            memberId = (participant as? EventParticipant.Member)?.memberId,
            guestName = (participant as? EventParticipant.Guest)?.name,
            guestEmail = (participant as? EventParticipant.Guest)?.normalizedEmail,
            activeParticipantKey = key,
            status = status,
            feeAmount = feeAmount,
            holdExpiresAt = holdExpiresAt,
            waitlistPosition = waitlistPosition,
            cancelTokenSha256 = cancelTokenHash,
            registeredAt = now,
            confirmedAt = confirmedAt,
        )
    }

    private fun gatewayUsable(): Boolean {
        val settingsRow =
            transaction {
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
            }
        val gatewayEnabled = settingsRow?.get(OrganizationSettingsTable.paymentGatewayEnabled) ?: false
        val provider = settingsRow?.get(OrganizationSettingsTable.paymentGatewayProvider)
        return gatewayEnabled &&
            provider == PaymentProvider.STRIPE &&
            pspConfigState is PspConfigState.Configured &&
            checkoutClient != null &&
            paymentGatewayDisclaimerIsCurrentlyAcknowledged()
    }

    private fun mailRegistrationReceived(
        participant: EventParticipant,
        title: String,
        slug: String,
        cancelToken: String,
        mode: RegistrationMailMode,
    ) {
        val to = participant.emailForMail() ?: return
        val name = participant.displayNameForMail()
        val cancelUrl = "$baseUrl/veranstaltung/$slug/storno?token=$cancelToken"
        val subject =
            when (mode) {
                RegistrationMailMode.CONFIRMED -> "Anmeldebestätigung: $title"
                RegistrationMailMode.WAITLISTED -> "Warteliste: $title"
                RegistrationMailMode.PAYMENT_PENDING -> "Bitte Zahlung abschließen: $title"
            }
        val statusLine =
            when (mode) {
                RegistrationMailMode.CONFIRMED -> "Ihre Anmeldung für \"$title\" ist bestätigt."
                RegistrationMailMode.WAITLISTED ->
                    "Sie stehen auf der Warteliste für \"$title\". Wir melden uns, sobald ein Platz frei wird."
                RegistrationMailMode.PAYMENT_PENDING ->
                    "Ihr Platz für \"$title\" ist reserviert. Bitte schließen Sie die Zahlung innerhalb von " +
                        "${EventPolicy.STANDARD_HOLD.inWholeMinutes} Minuten ab, sonst wird der Platz wieder freigegeben."
            }
        val plainText = "Hallo $name,\n\n$statusLine\n\nAnmeldung stornieren: $cancelUrl\n"
        val html = "<p>Hallo $name,</p><p>$statusLine</p><p><a href=\"$cancelUrl\">Anmeldung stornieren</a></p>"
        mailDispatcher.enqueue(to = to, subject = subject, plainTextBody = plainText, htmlBody = html, purpose = "event-registration")
    }

    private enum class RegistrationMailMode { CONFIRMED, WAITLISTED, PAYMENT_PENDING }

    /**
     * Frees [registrationId]'s seat (its own paid-registration attempt failed -- gateway
     * unavailable at the last moment, or Stripe itself rejected the checkout call) AND sweeps the
     * waitlist for a successor, both under a FRESH [EventCapacityGuard.withEventLock] acquisition
     * on [eventId] (Review MAJOR fix -- this used to be a bare `EventStore.cancelRegistration`
     * inside its own lock-free `transaction {}`, which freed the seat but left any waitlisted
     * registrant stranded until an unrelated later lock acquisition happened to sweep it).
     */
    private fun freeSeatAndSweepWaitlist(
        eventId: Uuid,
        registrationId: Uuid,
        now: LocalDateTime,
    ) {
        val (_, promotions) =
            EventCapacityGuard.withEventLock(eventId = eventId, now = now) { _ ->
                EventStore.cancelRegistration(id = registrationId, now = now)
            }
        promotions.forEach { it.mailPromotion(mailDispatcher) }
    }

    private fun randomCancelToken(): String {
        val buffer = ByteArray(32)
        SECURE_RANDOM.nextBytes(buffer)
        return buffer.joinToString(separator = "") { "%02x".format(it) }
    }

    private sealed interface Placement {
        data class Placed(
            val needsPayment: Boolean,
            val feeAmount: BigDecimal,
        ) : Placement

        data class Waitlisted(
            val position: Int,
        ) : Placement

        data object WaitlistFull : Placement

        data object Duplicate : Placement

        /** The event closed (cancelled/registration window ended/already started) between step 1's cheap pre-lock read and this lock acquisition -- see class KDoc step 3. */
        data object EventClosed : Placement
    }

    private companion object {
        val SECURE_RANDOM = SecureRandom()

        /** ANSI SQL `unique_violation` -- the SQLSTATE both PostgreSQL and H2 (this codebase's test-time dialect) report for a UNIQUE-index conflict, see `submit`'s own `catch (e: ExposedSQLException)` KDoc. */
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}

private fun EventParticipant.emailForMail(): String? =
    when (this) {
        is EventParticipant.Member -> email
        is EventParticipant.Guest -> normalizedEmail
    }

private fun EventParticipant.displayNameForMail(): String =
    when (this) {
        is EventParticipant.Member -> displayName
        is EventParticipant.Guest -> name
    }
