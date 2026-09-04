package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * The ONLY allowed entry point into any capacity-changing operation on `event`/`event_registration`
 * -- the lock sits on the `event` ROW, not on any `event_registration` row (Atkinson: the invariant
 * being protected -- "occupied seats never exceed capacity" -- is a property of the EVENT, so the
 * lock must be too). Locking individual registration rows would let two concurrent transactions
 * each count the same 39 occupied seats and both admit a 40th registrant into a `capacity = 40`
 * event -- the exact Lost-Update shape `CrmContactStore.update`'s own `getForUpdateOrThrow` KDoc
 * documents fixing for a different table.
 *
 * Every call opens its own `transaction {}`. Inside it: lock the `event` row, lazily expire stale
 * `PENDING_PAYMENT` holds (`EventStore.expireStaleHolds` -- see `EventWaitlist` KDoc "this codebase
 * has no scheduler"), then greedily promote as much of the waitlist as the now-current occupancy
 * allows (`EventWaitlist.promoteWhileCapacityFree`). [block] runs NEXT, inside the same lock, so its
 * own capacity-counting/registration-writing decisions see a fully up-to-date `event_registration`
 * state. **A second `promoteWhileCapacityFree` sweep then runs AFTER [block]**, still inside the
 * same lock (Review MAJOR fix): [block] itself frequently FREES a seat (a self-cancellation, an
 * event cancellation) -- without this second sweep, that freed seat would only ever be picked up by
 * the NEXT, unrelated lock acquisition on this event, leaving the waitlist head stranded (no mail,
 * no promotion) until someone else happens to touch the event or a BOARD/ADMIN runs `sweepEvent`.
 *
 * Returns [block]'s result paired with every [WaitlistPromotion] this call caused (both sweeps
 * combined) -- **the caller is responsible for mailing each one AFTER this function returns** (i.e.
 * after the transaction has committed). `MailDispatcher.enqueue` must never run inside an open
 * `transaction {}` -- a mail whose transaction later rolls back would be a lie to the recipient
 * (same rule `EventRegistrationSubmission` KDoc states for its own confirmation mail).
 */
internal object EventCapacityGuard {
    /**
     * Trigger 2 of [EventWaitlist]'s three -- `PspWebhookIngestion.ingestCheckoutExpired`'s
     * `EVENT_FEE` branch calls this instead of [withEventLock]: it needs to expire ONE SPECIFIC
     * registration (Stripe's own expiry timeline, not [EventPolicy.STANDARD_HOLD]'s) BEFORE the
     * capacity sweep runs, not after -- [withEventLock]'s own built-in `expireStaleHolds` only
     * catches holds whose `hold_expires_at` has already passed BY THIS SERVER'S CLOCK, which this
     * specific row's may not have (see that KDoc's own "~24h, not 30min" note). [EventStore
     * .expireRegistrationIfPending] itself still re-checks `hold_expires_at <= now` (Review MAJOR
     * fix, see that function's own KDoc) -- a waitlist promotion's 48h `WAITLIST_OFFER_WINDOW` hold
     * outlives Stripe's own ~24h session-expiry default, so this call must NOT blindly trust that
     * Stripe's `checkout.session.expired` webhook firing means the REGISTRATION's hold is actually
     * over. Returns every [WaitlistPromotion] the resulting sweep caused -- same "caller mails these
     * after commit" contract as [withEventLock].
     */
    fun expireHoldAndSweep(
        eventId: Uuid,
        registrationId: Uuid,
        now: LocalDateTime,
    ): List<WaitlistPromotion> =
        transaction {
            val event = EventStore.lockEventForUpdate(eventId) ?: throw NotFoundException("Event $eventId not found")
            EventStore.expireStaleHolds(eventId = eventId, now = now)
            EventStore.expireRegistrationIfPending(id = registrationId, now = now)
            EventWaitlist.promoteWhileCapacityFree(event = event, now = now)
        }

    fun <T> withEventLock(
        eventId: Uuid,
        now: LocalDateTime,
        block: (ResultRow) -> T,
    ): Pair<T, List<WaitlistPromotion>> =
        transaction {
            val event = EventStore.lockEventForUpdate(eventId) ?: throw NotFoundException("Event $eventId not found")
            EventStore.expireStaleHolds(eventId = eventId, now = now)
            val promotionsBeforeBlock = EventWaitlist.promoteWhileCapacityFree(event = event, now = now)
            // Re-read: `event` itself (title/fee/etc.) never changes as a side effect of the sweep
            // above, but re-fetching keeps this function honest about only ever handing the caller a
            // row read AFTER the lock+sweep, never a possibly-stale pre-sweep snapshot.
            val freshEvent = EventStore.getEventOrThrow(eventId)
            check(freshEvent[EventTable.id] == event[EventTable.id])
            val result = block(freshEvent)
            // See class KDoc "A second promoteWhileCapacityFree sweep" -- catches a seat [block]
            // itself just freed, still under this SAME lock acquisition.
            val promotionsAfterBlock = EventWaitlist.promoteWhileCapacityFree(event = freshEvent, now = now)
            result to (promotionsBeforeBlock + promotionsAfterBlock)
        }
}
