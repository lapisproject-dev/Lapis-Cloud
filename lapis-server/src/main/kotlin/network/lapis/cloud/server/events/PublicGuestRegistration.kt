package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.rpc.FriendRegistrationConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.260 -- the ONE code path both public guest entry points share: the server-rendered form (`EventPublicRoutes`) and the JSON embed
 * endpoint (`EmbedEventRoutes`). Sharing it (rather than copying it) is what keeps the two answers equal: a second path that decides
 * differently would be a membership oracle again.
 *
 * **Binding order inside [register], any reordering is a review blocker** (see `docs/features/public-event-page.adoc`):
 * 1. [EventRegistrationSubmission.probeAvailability] -- the address-INDEPENDENT reasons (closed, waitlist full, no gateway) answer first,
 *    so they look the same for a member address and a stranger.
 * 2. Only then ONE [GuestAddressAccountMatcher.selfRegistrantOrNull] query.
 * 3. Account address: lock + sweep ([EventRegistrationSubmission.touchEventLock]) to keep the database work comparable, a rate-limited
 *    notice mail to the account owner, nothing stored.
 * 4. Otherwise a guest placement with `deferCheckout = true` (no provider round-trip inside the request).
 *
 * The caller has ALREADY done limits, validation, the PUBLIC visibility check and the strict registration rate limiter.
 */
internal class PublicGuestRegistration(
    private val submission: EventRegistrationSubmission,
    private val noticeMailLimiter: EventNoticeMailLimiter,
    private val friendRegistrationConfig: FriendRegistrationConfig,
) {
    /** What a public caller may learn. [Accepted] is the ONE answer for fresh / paid / waitlisted / duplicate / account address. */
    sealed interface Outcome {
        data object Accepted : Outcome

        /** Probe said not available, or the event closed between probe and lock (race). */
        data object NotAvailable : Outcome

        data object WaitlistFull : Outcome

        data object GatewayUnavailable : Outcome

        /** Unreachable with `deferCheckout = true`; kept for exhaustiveness. */
        data object PaymentFailed : Outcome
    }

    suspend fun register(
        eventId: Uuid,
        guestName: String,
        normalizedEmail: String,
        message: String?,
        embedOrigin: String?,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
    ): Outcome {
        when (submission.probeAvailability(eventId = eventId, now = now)) {
            EventRegistrationSubmission.Availability.NotAvailable -> return Outcome.NotAvailable
            EventRegistrationSubmission.Availability.WaitlistFull -> return Outcome.WaitlistFull
            EventRegistrationSubmission.Availability.GatewayUnavailable -> return Outcome.GatewayUnavailable
            EventRegistrationSubmission.Availability.Open -> Unit
        }
        val eventTitle = transaction { EventStore.getEventOrNull(eventId)?.get(EventTable.title) }.orEmpty()
        // ONE query, whatever the address is.
        val account =
            GuestAddressAccountMatcher.selfRegistrantOrNull(
                normalizedEmail = normalizedEmail,
                friendConfig = friendRegistrationConfig,
            )
        if (account != null) {
            // Nothing is stored, no organiser is told, the account owner gets a notice (once per event+address per day).
            submission.touchEventLock(eventId = eventId, now = now)
            if (noticeMailLimiter.tryAcquire(eventId = eventId, normalizedEmail = normalizedEmail)) {
                submission.mailMemberNotice(to = account.email, title = eventTitle, eventId = eventId)
            }
            return Outcome.Accepted
        }
        val result =
            submission.submit(
                eventId = eventId,
                participant = EventParticipant.Guest(name = guestName, normalizedEmail = normalizedEmail),
                now = now,
                embedOrigin = embedOrigin,
                message = message,
                // A paid seat is reserved and the payment link goes out by mail: no provider round-trip inside this request.
                deferCheckout = true,
            )
        return when (result) {
            is EventRegistrationResult.Confirmed,
            is EventRegistrationResult.Waitlisted,
            is EventRegistrationResult.PaymentLinkSent,
            // Not produced with deferCheckout = true; if it ever were, the visitor still gets the one neutral answer.
            is EventRegistrationResult.PaymentRequired,
            -> Outcome.Accepted
            EventRegistrationResult.AlreadyRegistered -> {
                if (noticeMailLimiter.tryAcquire(eventId = eventId, normalizedEmail = normalizedEmail)) {
                    submission.mailDuplicateNotice(to = normalizedEmail, title = eventTitle)
                }
                Outcome.Accepted
            }
            EventRegistrationResult.EventNotAvailable -> Outcome.NotAvailable
            EventRegistrationResult.WaitlistFull -> Outcome.WaitlistFull
            EventRegistrationResult.GatewayUnavailable -> Outcome.GatewayUnavailable
            is EventRegistrationResult.PaymentFailed -> Outcome.PaymentFailed
        }
    }
}
