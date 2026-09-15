package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.htmlEscape
import network.lapis.cloud.server.payment.psp.EventTicketMail
import network.lapis.cloud.server.payment.psp.PspWebhookEventLog
import network.lapis.cloud.server.payment.psp.PspWebhookOutcome
import network.lapis.cloud.shared.domain.PaymentProvider
import kotlin.uuid.Uuid

// Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6), Phase A7 der Neutralisierungs-Refaktorierung
// -- der Teil von PspWebhookRoutes.kt, den PaypalWebhookRoutes.kt WORTGLEICH wiederverwendet,
// statt ihn zu duplizieren: die gemeinsamen DoS-Deckel, recordDeliveryAndRespond und
// mailEventTicket. Nur der Verifikationsmechanismus und die Kopfzeilen-/Body-Form unterscheiden
// sich zwischen den beiden Anbieter-Routen -- siehe PaypalWebhookRoutes KDoc "Do NOT merge them".

/** Hard cap on a raw webhook body -- same DoS-guard reasoning as `MAX_INBOX_BODY_BYTES` (`FederationRoutes.kt`), a generous ceiling for a Checkout-Session/Order event. */
internal const val MAX_WEBHOOK_BODY_BYTES = 64 * 1024

/** Same linear, non-recursive JSON-nesting-depth cap as `FederationRoutes.kt`'s own `PSP_WEBHOOK_MAX_JSON_NESTING_DEPTH`. */
internal const val PSP_WEBHOOK_MAX_JSON_NESTING_DEPTH = 20

/** Writes exactly one [PspWebhookEventLog] row (its own transaction) and responds [status] to [call] -- the one place every branch of either provider's handler converges. */
internal suspend fun recordDeliveryAndRespond(
    call: ApplicationCall,
    provider: PaymentProvider,
    status: HttpStatusCode,
    bodyBytes: ByteArray,
    signatureVerified: Boolean,
    rejectReason: String?,
    outcome: PspWebhookOutcome,
    eventType: String? = null,
    providerEventId: String? = null,
    paymentTransactionId: Uuid? = null,
) {
    PspWebhookEventLog.record(
        provider = provider,
        providerEventId = providerEventId,
        eventType = eventType,
        signatureVerified = signatureVerified,
        rejectReason = rejectReason,
        outcome = outcome,
        paymentTransactionId = paymentTransactionId,
        bodySha256 = sha256Hex(bodyBytes),
        bodyByteSize = bodyBytes.size,
    )
    call.respond(status)
}

/**
 * Welle V1.4.3.2 -- sends the "your payment is confirmed, here is your ticket" mail for a paid
 * event registration. Deliberately its OWN, minimal mail (not `EventRegistrationSubmission`'s
 * `mailRegistrationReceived`, which never runs for this confirmation path at all -- the webhook
 * confirms the registration, not that class) -- kept here (rather than `PspWebhookIngestion`)
 * because building the URL needs [EventTicketPolicy.ticketUrl], and this is the one shared call
 * site with a concrete `baseUrl` available, for BOTH provider webhook routes.
 */
internal fun mailEventTicket(
    mail: EventTicketMail,
    mailDispatcher: MailDispatcher,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    val ticketUrl = EventTicketPolicy.ticketUrl(baseUrl = baseUrl, slug = mail.slug, rawCode = mail.rawTicketCode)
    val subject = "Zahlung bestätigt: ${mail.eventTitle}"
    val body = "Ihre Zahlung für \"${mail.eventTitle}\" ist eingegangen -- Ihre Teilnahme ist bestätigt."
    // Security-Review MINOR fix: `mail.recipientName`/`mail.eventTitle` can originate from an
    // unauthenticated guest form / a BOARD-supplied event title -- htmlEscape() both before they
    // reach `htmlBody` (see `network.lapis.cloud.server.mail.htmlEscape` KDoc).
    val bodyHtml = "Ihre Zahlung für \"${htmlEscape(mail.eventTitle)}\" ist eingegangen -- Ihre Teilnahme ist bestätigt."
    mailDispatcher.enqueue(
        to = mail.to,
        subject = subject,
        plainTextBody = "Hallo ${mail.recipientName},\n\n$body\n\nIhr Ticket: $ticketUrl\n",
        htmlBody = "<p>Hallo ${htmlEscape(mail.recipientName)},</p><p>$bodyHtml</p><p><a href=\"$ticketUrl\">Ihr Ticket</a></p>",
        purpose = "event-ticket",
    )
}
