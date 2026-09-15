package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.CheckoutCompletedIngestionOutcome
import network.lapis.cloud.server.payment.psp.PAYPAL_JSON
import network.lapis.cloud.server.payment.psp.PaypalCaptureResult
import network.lapis.cloud.server.payment.psp.PaypalConfigState
import network.lapis.cloud.server.payment.psp.PaypalOrdersClient
import network.lapis.cloud.server.payment.psp.PaypalSignatureResult
import network.lapis.cloud.server.payment.psp.PaypalTransmissionHeaders
import network.lapis.cloud.server.payment.psp.PaypalVerifyResult
import network.lapis.cloud.server.payment.psp.PaypalWebhookEvent
import network.lapis.cloud.server.payment.psp.PspCheckoutSessions
import network.lapis.cloud.server.payment.psp.PspWebhookIngestion
import network.lapis.cloud.server.payment.psp.PspWebhookOutcome
import network.lapis.cloud.server.payment.psp.checkTransmissionFreshness
import network.lapis.cloud.server.payment.psp.toPspPaymentEvent
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.paymentGatewayDisclaimerIsCurrentlyAcknowledged
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- `POST /api/webhooks/paypal`, das
 * PayPal-Geschwister zu `PspWebhookRoutes.kt`s `registerPspWebhookRoutes`. **Bewusst NICHT mit
 * jener Datei zusammengeführt** -- Header, Verifikationsmechanismus und Body-Form unterscheiden
 * sich zu stark; nur die gemeinsamen Teile (DoS-Deckel, [recordDeliveryAndRespond],
 * [mailEventTicket]) leben in `PspWebhookCommon.kt` und werden hier WORTGLEICH wiederverwendet.
 *
 * Schritt-Reihenfolge -- gleiches 11-Schritte-Skelett wie `registerPspWebhookRoutes`, mit
 * getauschten Schritten 5/6 für PayPals Mechanismus (Implementierungsplan §1.1/§6.4):
 * 1. Rate limit nach `remoteHost` (EIGENE Limiter-Instanz), VOR jedem Body-Read.
 * 2. `Content-Length`-Vorabprüfung.
 * 3. Begrenzter streamender Read.
 * 4. Config-Gate -- `paypalConfig`/`ordersClient` nicht konfiguriert -> `503`, niemals verraten
 *    WELCHE Variable fehlt.
 * 5. Header-Parse ([PaypalTransmissionHeaders.parseOrNull]) -> `401`.
 * 6. Signaturverifikation via [PaypalOrdersClient.verifyWebhookSignature] -> `NotVerified`
 *    `401`/`Unavailable` **`503`** (PayPal liefert erneut zu) /`Verified` -> ERST DANN
 *    [checkTransmissionFreshness] -> `401` (Anti-Timing-Oracle-Reihenfolge, siehe
 *    `StripeSignatureVerifier` KDoc Schritt 4).
 * 7. JSON-Verschachtelungstiefen-Scan -- **VOR** Schritt 6 verschoben (Implementierungsplan-Pitfall
 *    §6.4: der rohe Body muss für die Verify-API zu einem [kotlinx.serialization.json.JsonElement]
 *    geparst werden; diese bewusste Abweichung von der Stripe-Route wird hier dokumentiert).
 * 8. Typisierte Dekodierung.
 * 9. Gate-Check (`payment_gateway_enabled` + aktuelle Disclaimer-Bestätigung).
 * 10. Dispatch nach `event.eventType` -- `CHECKOUT.ORDER.APPROVED` löst die Capture aus (KEIN Geld
 *     gebucht), `PAYMENT.CAPTURE.COMPLETED` bucht das Geld, `PAYMENT.CAPTURE.DENIED`/
 *     `CHECKOUT.ORDER.VOIDED` räumen auf, alles andere `200 IGNORED`.
 * 11. [recordDeliveryAndRespond].
 * 12. NACH der Antwort: opportunistisches [PspCheckoutSessions.sweepExpiredAnonymousSessions].
 *
 * **Route ist bewusst UNAUTHENTIFIZIERT** -- kein `resolveCurrentMember` irgendwo in dieser Datei;
 * die PayPal-Signatur IST die Authentifizierung.
 */
fun Route.registerPaypalWebhookRoutes(
    paypalConfig: PaypalConfigState,
    ordersClient: PaypalOrdersClient?,
    rateLimiter: FederationInboxRateLimiter,
    mailDispatcher: MailDispatcher = MailDispatcher(transport = NoOpMailTransport()),
) {
    post("/api/webhooks/paypal") {
        val remoteHost = call.request.origin.remoteHost

        // 1. Rate limit -- before any body read.
        if (!rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many requests")
            return@post
        }

        // 2. Content-Length pre-check.
        val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredContentLength != null && declaredContentLength > MAX_WEBHOOK_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max webhook payload size is $MAX_WEBHOOK_BODY_BYTES bytes")
            return@post
        }

        // 3. Bounded streaming read.
        val bodyBytes = readCappedBody(call = call, maxBytes = MAX_WEBHOOK_BODY_BYTES)
        if (bodyBytes == null) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max webhook payload size is $MAX_WEBHOOK_BODY_BYTES bytes")
            return@post
        }

        // 4. Config gate.
        if (paypalConfig !is PaypalConfigState.Configured || ordersClient == null) {
            recordDeliveryAndRespond(
                call = call,
                provider = PaymentProvider.PAYPAL,
                status = HttpStatusCode.ServiceUnavailable,
                bodyBytes = bodyBytes,
                signatureVerified = false,
                rejectReason = "NOT_CONFIGURED",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 5. Header parse -- BEFORE the verify-API call, so a garbage/missing header never even
        // makes us flood PayPal's own verify endpoint.
        val headers = PaypalTransmissionHeaders.parseOrNull { name -> call.request.headers[name] }
        if (headers == null) {
            recordDeliveryAndRespond(
                call = call,
                provider = PaymentProvider.PAYPAL,
                status = HttpStatusCode.Unauthorized,
                bodyBytes = bodyBytes,
                signatureVerified = false,
                rejectReason = "MISSING_SIGNATURE",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 7 (moved ahead of step 6, see class KDoc pitfall §6.4) -- nesting-depth scan on the raw
        // text, BEFORE it is parsed to a JsonElement for the verify-API request body.
        val bodyText = bodyBytes.toString(Charsets.UTF_8)
        if (exceedsMaxJsonNestingDepth(text = bodyText, maxDepth = PSP_WEBHOOK_MAX_JSON_NESTING_DEPTH)) {
            recordDeliveryAndRespond(
                call = call,
                provider = PaymentProvider.PAYPAL,
                status = HttpStatusCode.BadRequest,
                bodyBytes = bodyBytes,
                signatureVerified = false,
                rejectReason = "JSON_TOO_DEEP",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }
        val rawEvent =
            runCatching { PAYPAL_JSON.parseToJsonElement(bodyText) }.getOrNull()
                ?: run {
                    recordDeliveryAndRespond(
                        call = call,
                        provider = PaymentProvider.PAYPAL,
                        status = HttpStatusCode.BadRequest,
                        bodyBytes = bodyBytes,
                        signatureVerified = false,
                        rejectReason = "MALFORMED_EVENT",
                        outcome = PspWebhookOutcome.REJECTED,
                    )
                    return@post
                }

        // 6. Signature verification via PayPal's own verify-webhook-signature API.
        val verification = ordersClient.verifyWebhookSignature(headers = headers, rawEvent = rawEvent)
        when (verification) {
            is PaypalVerifyResult.NotVerified -> {
                recordDeliveryAndRespond(
                    call = call,
                    provider = PaymentProvider.PAYPAL,
                    status = HttpStatusCode.Unauthorized,
                    bodyBytes = bodyBytes,
                    signatureVerified = false,
                    rejectReason = "NOT_VERIFIED",
                    outcome = PspWebhookOutcome.REJECTED,
                )
                return@post
            }
            is PaypalVerifyResult.Unavailable -> {
                // 503 -- PayPal retries a non-2xx delivery for up to 3 days (Entscheidung §1.1).
                recordDeliveryAndRespond(
                    call = call,
                    provider = PaymentProvider.PAYPAL,
                    status = HttpStatusCode.ServiceUnavailable,
                    bodyBytes = bodyBytes,
                    signatureVerified = false,
                    rejectReason = "VERIFY_UNAVAILABLE",
                    outcome = PspWebhookOutcome.REJECTED,
                )
                return@post
            }
            is PaypalVerifyResult.Verified -> Unit
        }

        // ERST NACH erfolgreicher Verifikation: Frische-Check (Anti-Timing-Oracle-Reihenfolge).
        val freshness =
            checkTransmissionFreshness(
                transmissionTime = headers.transmissionTime,
                now = Clock.System.now(),
                tolerance = paypalConfig.config.webhookToleranceSeconds.seconds,
            )
        if (freshness is PaypalSignatureResult.Invalid) {
            recordDeliveryAndRespond(
                call = call,
                provider = PaymentProvider.PAYPAL,
                status = HttpStatusCode.Unauthorized,
                bodyBytes = bodyBytes,
                signatureVerified = true,
                rejectReason = freshness.reason,
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 8. Typed decode.
        val event = runCatching { PAYPAL_JSON.decodeFromString(PaypalWebhookEvent.serializer(), bodyText) }.getOrNull()
        if (event == null) {
            recordDeliveryAndRespond(
                call = call,
                provider = PaymentProvider.PAYPAL,
                status = HttpStatusCode.BadRequest,
                bodyBytes = bodyBytes,
                signatureVerified = true,
                rejectReason = "MALFORMED_EVENT",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 9. Gate check.
        val gatewayEnabled =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()[OrganizationSettingsTable.paymentGatewayEnabled]
            }
        if (!gatewayEnabled || !paymentGatewayDisclaimerIsCurrentlyAcknowledged()) {
            recordDeliveryAndRespond(
                call = call,
                provider = PaymentProvider.PAYPAL,
                status = HttpStatusCode.ServiceUnavailable,
                bodyBytes = bodyBytes,
                signatureVerified = true,
                rejectReason = "GATE_DISABLED",
                outcome = PspWebhookOutcome.REJECTED,
                eventType = event.eventType,
                providerEventId = event.id,
            )
            return@post
        }

        // 10. Dispatch by event.eventType.
        when (event.eventType) {
            "CHECKOUT.ORDER.APPROVED" -> {
                val captureResult =
                    try {
                        ordersClient.captureOrder(event.resource.id)
                    } catch (e: Exception) {
                        logger.error(
                            e,
                        ) { "PaypalWebhookRoutes: unexpected exception capturing order ${event.resource.id} (event ${event.id})" }
                        recordDeliveryAndRespond(
                            call = call,
                            provider = PaymentProvider.PAYPAL,
                            status = HttpStatusCode.InternalServerError,
                            bodyBytes = bodyBytes,
                            signatureVerified = true,
                            rejectReason = "INTERNAL_ERROR",
                            outcome = PspWebhookOutcome.REJECTED,
                            eventType = event.eventType,
                            providerEventId = event.id,
                        )
                        return@post
                    }
                val (status, outcome, rejectReason) =
                    when (captureResult) {
                        is PaypalCaptureResult.Captured -> Triple(HttpStatusCode.OK, PspWebhookOutcome.PROCESSED, null)
                        is PaypalCaptureResult.AlreadyCaptured -> Triple(HttpStatusCode.OK, PspWebhookOutcome.DUPLICATE, null)
                        is PaypalCaptureResult.Failed ->
                            Triple(
                                HttpStatusCode.InternalServerError,
                                PspWebhookOutcome.REJECTED,
                                "CAPTURE_FAILED",
                            )
                    }
                recordDeliveryAndRespond(
                    call = call,
                    provider = PaymentProvider.PAYPAL,
                    status = status,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = rejectReason,
                    outcome = outcome,
                    eventType = event.eventType,
                    providerEventId = event.id,
                )
            }
            "PAYMENT.CAPTURE.COMPLETED" -> {
                val result =
                    try {
                        PspWebhookIngestion.ingestCheckoutCompleted(event = event.toPspPaymentEvent(), bodyBytes = bodyBytes)
                    } catch (e: ConflictException) {
                        recordDeliveryAndRespond(
                            call = call,
                            provider = PaymentProvider.PAYPAL,
                            status = HttpStatusCode.InternalServerError,
                            bodyBytes = bodyBytes,
                            signatureVerified = true,
                            rejectReason = "POSTING_UNBALANCED",
                            outcome = PspWebhookOutcome.REJECTED,
                            eventType = event.eventType,
                            providerEventId = event.id,
                        )
                        return@post
                    } catch (e: Exception) {
                        logger.error(
                            e,
                        ) { "PaypalWebhookRoutes: unexpected exception ingesting PAYMENT.CAPTURE.COMPLETED (event ${event.id})" }
                        recordDeliveryAndRespond(
                            call = call,
                            provider = PaymentProvider.PAYPAL,
                            status = HttpStatusCode.InternalServerError,
                            bodyBytes = bodyBytes,
                            signatureVerified = true,
                            rejectReason = "INTERNAL_ERROR",
                            outcome = PspWebhookOutcome.REJECTED,
                            eventType = event.eventType,
                            providerEventId = event.id,
                        )
                        return@post
                    }
                val (outcomeKind, paymentTransactionId) =
                    when (val outcome = result.outcome) {
                        is CheckoutCompletedIngestionOutcome.Processed -> PspWebhookOutcome.PROCESSED to outcome.paymentTransactionId
                        is CheckoutCompletedIngestionOutcome.Duplicate -> PspWebhookOutcome.DUPLICATE to null
                        is CheckoutCompletedIngestionOutcome.Unposted -> PspWebhookOutcome.UNPOSTED to outcome.paymentTransactionId
                    }
                result.ticketMail?.let { mailEventTicket(mail = it, mailDispatcher = mailDispatcher) }
                recordDeliveryAndRespond(
                    call = call,
                    provider = PaymentProvider.PAYPAL,
                    status = HttpStatusCode.OK,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = null,
                    outcome = outcomeKind,
                    eventType = event.eventType,
                    providerEventId = event.id,
                    paymentTransactionId = paymentTransactionId,
                )
            }
            "CHECKOUT.ORDER.VOIDED", "PAYMENT.CAPTURE.DENIED" -> {
                try {
                    PspWebhookIngestion.ingestCheckoutExpired(event = event.toPspPaymentEvent(), mailDispatcher = mailDispatcher)
                } catch (e: Exception) {
                    logger.error(e) { "PaypalWebhookRoutes: unexpected exception ingesting ${event.eventType} (event ${event.id})" }
                    recordDeliveryAndRespond(
                        call = call,
                        provider = PaymentProvider.PAYPAL,
                        status = HttpStatusCode.InternalServerError,
                        bodyBytes = bodyBytes,
                        signatureVerified = true,
                        rejectReason = "INTERNAL_ERROR",
                        outcome = PspWebhookOutcome.REJECTED,
                        eventType = event.eventType,
                        providerEventId = event.id,
                    )
                    return@post
                }
                recordDeliveryAndRespond(
                    call = call,
                    provider = PaymentProvider.PAYPAL,
                    status = HttpStatusCode.OK,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = null,
                    outcome = PspWebhookOutcome.PROCESSED,
                    eventType = event.eventType,
                    providerEventId = event.id,
                )
            }
            else -> {
                // 200/IGNORED -- never let PayPal retry an unsupported type for days.
                recordDeliveryAndRespond(
                    call = call,
                    provider = PaymentProvider.PAYPAL,
                    status = HttpStatusCode.OK,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = null,
                    outcome = PspWebhookOutcome.IGNORED,
                    eventType = event.eventType,
                    providerEventId = event.id,
                )
            }
        }

        // 12. Opportunistic sweep, AFTER the response above -- see PspCheckoutSessions
        // .sweepExpiredAnonymousSessions KDoc / Implementierungsplan §1.3. Currently a no-op in
        // practice (Review round 2, nit): anonymous donations only ever use STRIPE this wave
        // (AnonymousDonationCheckout.gatewayUsable is Stripe-only), so no PayPal
        // payment_checkout_session row ever carries a non-null externalDonorId for this to match.
        // Kept here anyway, ready for the day anonymous PayPal donations ship -- remove this call
        // (or this comment) if that plan changes instead.
        runCatching {
            transaction {
                PspCheckoutSessions.sweepExpiredAnonymousSessions(
                    provider = PaymentProvider.PAYPAL,
                    now = DbClock.nowLocalDateTime(),
                )
            }
        }.onFailure { e -> logger.warn(e) { "PaypalWebhookRoutes: opportunistic sweepExpiredAnonymousSessions failed, non-fatal" } }
    }
}
