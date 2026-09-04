package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.routes.sha256Hex
import org.jetbrains.exposed.v1.core.ResultRow
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * One waitlist->seat promotion this sweep caused, still needing its confirmation mail sent AFTER
 * the caller's transaction commits -- see `EventCapacityGuard.withEventLock` KDoc.
 * [payNowRequired] is `false` for a free event's waitlist promotion (confirmed directly, nothing to
 * pay); `true` means the recipient has [network.lapis.cloud.server.events.EventPolicy
 * .WAITLIST_OFFER_WINDOW] to pay before this seat is offered onward again -- [paymentResumeUrl] is
 * then the (non-null) link that lets them actually do so (Review MAJOR fix, see
 * `promoteWhileCapacityFree`'s own KDoc); always `null` when [payNowRequired] is `false`. [cancelUrl]
 * is the storno link built from the SAME rotated token [paymentResumeUrl] carries -- non-null exactly
 * when [paymentResumeUrl] is (Review MINOR fix, Welle events-core-Runde-3): `promoteToPendingPayment`
 * REPLACES the registration's `cancel_token_sha256`, so the original "join the waitlist" mail's
 * storno link goes dead the moment a `payNowRequired` promotion happens -- without this field, a
 * promoted GUEST (no member RPC path to `cancelOwnRegistration`) had no way left to actively
 * withdraw, only to let the hold expire. `null` when [payNowRequired] is `false`: a free-event
 * promotion never rotates the token (`promoteToConfirmedDirectly` leaves `cancel_token_sha256`
 * untouched), so the ORIGINAL waitlist mail's storno link is still valid and there is no fresh raw
 * token here to build a new one from anyway (only its SHA-256 hash is ever persisted).
 */
internal data class WaitlistPromotion(
    val registrationId: Uuid,
    val eventId: Uuid,
    val recipientEmail: String,
    val recipientDisplayName: String,
    val eventTitle: String,
    val feeAmount: BigDecimal,
    val payNowRequired: Boolean,
    val paymentResumeUrl: String?,
    val cancelUrl: String?,
)

/**
 * Automatic waitlist->seat promotion -- **this codebase has NO scheduler/background-job
 * infrastructure** (documented at length in `network.lapis.cloud.server.federation
 * .OidcBackChannelLogoutNotifier` KDoc and `network.lapis.cloud.server.payment.psp
 * .PspWebhookIngestion.ingestCheckoutExpired` KDoc), so every time-based transition here is LAZY.
 * Exactly three triggers cause [promoteWhileCapacityFree] to run at all:
 *
 * 1. **Every [EventCapacityGuard.withEventLock] call** -- i.e. every registration attempt, every
 *    self-cancellation, every event cancellation. The overwhelmingly common trigger in practice.
 * 2. **`PspWebhookIngestion.ingestCheckoutExpired`'s `EVENT_FEE` branch** -- a payment window that
 *    ran out without the Stripe checkout completing.
 * 3. **`IEventService.sweepEvent`** -- a manual BOARD/ADMIN safety-net button, for an event nobody
 *    happens to touch through the two triggers above (e.g. no further registrations AND no Stripe
 *    webhook because the offer window itself, not a checkout session, is what expired).
 *
 * Deliberately NOT re-triggered by a plain `getEvent`/`listEvents` read -- unlike a
 * capacity-changing write, a read has no natural place to safely send the resulting confirmation
 * mail from (see `EventCapacityGuard` KDoc "the caller is responsible for mailing"), and every
 * genuine capacity change already runs through trigger 1.
 */
internal object EventWaitlist {
    /**
     * Promotes waitlist heads (lowest `waitlist_position` first) one at a time, re-counting
     * occupancy after each promotion, until either the waitlist is empty or the event is full again
     * -- naturally bounded by [EventPolicy.MAX_WAITLIST] and terminates because every promotion
     * removes exactly one row from the `WAITLISTED` pool. Must run INSIDE the caller's already-held
     * `event` row lock (`EventCapacityGuard.withEventLock`) -- never call this standalone.
     */
    fun promoteWhileCapacityFree(
        event: ResultRow,
        now: LocalDateTime,
    ): List<WaitlistPromotion> {
        val eventId = event[EventTable.id]
        val slug = event[EventTable.slug]
        val capacity = event[EventTable.capacity]
        val promotions = mutableListOf<WaitlistPromotion>()
        while (capacity == null || EventStore.countOccupied(eventId = eventId, now = now) < capacity) {
            val head = EventStore.findWaitlistHead(eventId) ?: break
            val registrationId = head[EventRegistrationTable.id]
            val memberId = head[EventRegistrationTable.memberId]
            val guestEmail = head[EventRegistrationTable.guestEmail]
            val guestName = head[EventRegistrationTable.guestName]
            val feeAmount = head[EventRegistrationTable.feeAmount]
            val key =
                EventPolicy.activeParticipantKey(
                    memberId = memberId,
                    normalizedGuestEmail =
                        if (memberId ==
                            null
                        ) {
                            guestEmail
                        } else {
                            null
                        },
                )
            val recipientEmail = memberId?.let { EventStore.memberEmailOrNull(it) } ?: guestEmail
            val recipientDisplayName = memberId?.let { EventStore.memberDisplayNameOrNull(it) } ?: guestName
            val payNowRequired = feeAmount.compareTo(BigDecimal.ZERO) != 0
            // Review MAJOR fix: a paid-event promotion used to leave the registrant with NO way to
            // ever pay for the seat just granted -- `mailPromotion` told them to "please complete
            // payment" but no code path anywhere let them do so. A fresh token, rotated onto this
            // registration's `cancel_token_sha256` (see `EventStore.promoteToPendingPayment` KDoc),
            // both supersedes the old storno link AND doubles as the payment-resume link
            // `registerEventPublicRoutes`'s `POST /veranstaltung/{slug}/zahlung` route accepts.
            val paymentResumeUrl: String?
            val cancelUrl: String?
            if (payNowRequired) {
                val holdExpiresAt = now.plusDuration(EventPolicy.WAITLIST_OFFER_WINDOW)
                val rawToken = EventPolicy.randomToken()
                EventStore.promoteToPendingPayment(
                    id = registrationId,
                    holdExpiresAt = holdExpiresAt,
                    activeParticipantKey = key,
                    cancelTokenSha256 = sha256Hex(rawToken.toByteArray(Charsets.US_ASCII)),
                    now = now,
                )
                val publicBaseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
                paymentResumeUrl = "$publicBaseUrl/veranstaltung/$slug/zahlung?token=$rawToken"
                // Review MINOR fix: the token rotation two lines above just invalidated this
                // registrant's ORIGINAL storno link (see `WaitlistPromotion.cancelUrl` KDoc) -- this
                // fresh one, built from the SAME `rawToken`, replaces it in the promotion mail.
                cancelUrl = "$publicBaseUrl/veranstaltung/$slug/storno?token=$rawToken"
            } else {
                EventStore.promoteToConfirmedDirectly(id = registrationId, activeParticipantKey = key, now = now)
                paymentResumeUrl = null
                cancelUrl = null
            }
            if (recipientEmail != null) {
                promotions +=
                    WaitlistPromotion(
                        registrationId = registrationId,
                        eventId = eventId,
                        recipientEmail = recipientEmail,
                        recipientDisplayName = recipientDisplayName ?: recipientEmail,
                        eventTitle = event[EventTable.title],
                        feeAmount = feeAmount,
                        payNowRequired = payNowRequired,
                        paymentResumeUrl = paymentResumeUrl,
                        cancelUrl = cancelUrl,
                    )
            }
        }
        return promotions
    }
}

/** `Instant`-roundtrip addition of a [kotlin.time.Duration] to a [LocalDateTime] -- same idiom `AnonymousDonationCheckout` already establishes inline; extracted here since both this file and `EventRegistrationSubmission` need it. */
internal fun LocalDateTime.plusDuration(duration: kotlin.time.Duration): LocalDateTime =
    (toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

/**
 * Sends the "a seat freed up for you" mail for one [WaitlistPromotion] -- the single shared
 * implementation for every trigger that can cause a promotion: `EventService.sweepEvent`/
 * `.cancelOwnRegistration`, `EventRegistrationSubmission.submit` (both for OTHER registrants a
 * lock acquisition happened to promote, and for this call's OWN registration when a paid seat's
 * Stripe checkout fails and its freed seat is immediately re-swept), the public storno handler in
 * `EventPublicRoutes`, and `PspWebhookIngestion.ingestCheckoutExpired`'s `EVENT_FEE` branch (Review
 * MAJOR fix -- that trigger used to discard its promotions instead of mailing them). Extracted here
 * (previously duplicated near-verbatim in `EventService`/`EventRegistrationSubmission`) so the
 * message text can never drift between call sites again.
 *
 * **NEVER call this from inside an open `transaction {}`** -- see `EventCapacityGuard` KDoc "the
 * caller is responsible for mailing each one AFTER this function returns".
 */
internal fun WaitlistPromotion.mailPromotion(mailDispatcher: MailDispatcher) {
    val subject = "Platz frei geworden: $eventTitle"
    val plainText: String
    val html: String
    if (payNowRequired) {
        // paymentResumeUrl/cancelUrl are always set together alongside payNowRequired -- see
        // promoteWhileCapacityFree.
        val url = checkNotNull(paymentResumeUrl) { "payNowRequired promotion $registrationId has no paymentResumeUrl" }
        val cancel = checkNotNull(cancelUrl) { "payNowRequired promotion $registrationId has no cancelUrl" }
        val body =
            "Ein Platz für \"$eventTitle\" ist frei geworden. Bitte schließen Sie die Zahlung " +
                "innerhalb von ${EventPolicy.WAITLIST_OFFER_WINDOW.inWholeHours} Stunden ab, sonst rückt " +
                "die nächste Person nach."
        plainText = "Hallo $recipientDisplayName,\n\n$body\n\nZahlung abschließen: $url\n\nAnmeldung stornieren: $cancel\n"
        html =
            "<p>Hallo $recipientDisplayName,</p><p>$body</p>" +
            "<p><a href=\"$url\">Zahlung abschließen</a></p>" +
            "<p><a href=\"$cancel\">Anmeldung stornieren</a></p>"
    } else {
        val body = "Ein Platz für \"$eventTitle\" ist frei geworden -- Ihre Teilnahme ist bestätigt."
        plainText = "Hallo $recipientDisplayName,\n\n$body\n"
        html = "<p>Hallo $recipientDisplayName,</p><p>$body</p>"
    }
    mailDispatcher.enqueue(
        to = recipientEmail,
        subject = subject,
        plainTextBody = plainText,
        htmlBody = html,
        purpose = "event-waitlist-promotion",
    )
}
