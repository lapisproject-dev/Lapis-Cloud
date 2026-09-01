package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.CheckoutCompletedIngestionOutcome
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.PspWebhookEventLog
import network.lapis.cloud.server.payment.psp.PspWebhookIngestion
import network.lapis.cloud.server.payment.psp.PspWebhookOutcome
import network.lapis.cloud.server.payment.psp.STRIPE_JSON
import network.lapis.cloud.server.payment.psp.StripeSignatureResult
import network.lapis.cloud.server.payment.psp.StripeSignatureVerifier
import network.lapis.cloud.server.payment.psp.StripeWebhookEvent
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.paymentGatewayDisclaimerIsCurrentlyAcknowledged
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Hard cap on a raw `POST /api/webhooks/stripe` body -- same DoS-guard reasoning as `MAX_INBOX_BODY_BYTES` (`FederationRoutes.kt`), a generous ceiling for a Stripe Checkout Session event. */
private const val MAX_WEBHOOK_BODY_BYTES = 64 * 1024

/** Same linear, non-recursive JSON-nesting-depth cap as `FederationRoutes.kt`'s own `MAX_JSON_NESTING_DEPTH`. */
private const val MAX_JSON_NESTING_DEPTH = 20

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `POST /api/webhooks/stripe`, the FIRST
 * webhook receiver of this repo's own money path, and the second webhook receiver overall after
 * `POST /federation/inbox`. **Handler ordering below deliberately MIRRORS `FederationRoutes`'s own
 * inbox handler exactly** -- rate limit -> `Content-Length` pre-check -> bounded streaming read ->
 * (here: config/gate checks) -> signature verification -> JSON-nesting-depth scan -> typed decode ->
 * dispatch. The "new pattern" this file adds is only the specific PSP signature scheme
 * ([StripeSignatureVerifier]), not the surrounding DoS/forensic-logging discipline, which is reused
 * verbatim via `JsonBodyGuards.kt`.
 *
 * **Writes exactly one `psp_webhook_event` row for every branch from step 4 onward** (config gate,
 * signature, JSON-depth, decode, gateway gate, and dispatch), via [PspWebhookEventLog] (its own,
 * separate `transaction {}` -- so a rolled-back ingestion still leaves the forensic trace, see that
 * object's own KDoc). MINOR fix (code review, Welle V1.2.8) -- corrected from a prior "always
 * exactly one row per request" claim: the THREE early-exit guards ahead of step 4 (rate limit,
 * `Content-Length` pre-check, oversized-body streaming read) respond directly and write no row --
 * there is nothing yet worth forensically logging at that point.
 *
 * **This does NOT make the table immune to unauthenticated growth** (security audit finding, Welle
 * V1.2.8, MINOR) -- steps 5 (`MISSING_SIGNATURE`) and 6 (failed signature verification) run AFTER the
 * rate limiter but BEFORE any authentication succeeds, and both DO write a row each via
 * [recordDeliveryAndRespond]. An attacker who never presents a valid signature can therefore still
 * make `psp_webhook_event` grow, one row per accepted request, for as long as they keep sending
 * traffic -- what actually bounds this is `rateLimiter` (per-source-IP, see `rateLimitKeyFor` below
 * and its tuning in `Application.kt`), not the absence of logging. That limiter is per-IP, so a
 * flood spread across many IPs is bounded only by the size of the attacker's IP pool, and there is
 * currently no retention/purge job for this table -- accepted as a bounded, not eliminated, risk for
 * this welle. Any exception besides [ConflictException] escaping
 * [PspWebhookIngestion.ingestCheckoutCompleted] (e.g. a constraint violation, or an
 * `IllegalArgumentException` from one of the posting bridges) is now also caught below and recorded
 * as `REJECTED`/`"INTERNAL_ERROR"` rather than escaping as a bare 500 with no `psp_webhook_event` row.
 *
 * **The webhook route is deliberately unauthenticated** -- no `resolveCurrentMember` call anywhere
 * in this file; the Stripe signature IS the authentication, and the ingestion path never trusts
 * anything from the request except what the signature covers.
 */
fun Route.registerPspWebhookRoutes(
    pspConfig: PspConfigState,
    rateLimiter: FederationInboxRateLimiter,
) {
    post("/api/webhooks/stripe") {
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

        // 3. Bounded streaming read -- BEFORE the body is ever parsed/logged.
        val bodyBytes = readCappedBody(call = call, maxBytes = MAX_WEBHOOK_BODY_BYTES)
        if (bodyBytes == null) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max webhook payload size is $MAX_WEBHOOK_BODY_BYTES bytes")
            return@post
        }

        // 4. Config gate -- never leak WHICH variable is missing to an unauthenticated caller.
        if (pspConfig !is PspConfigState.Configured) {
            recordDeliveryAndRespond(
                call = call,
                status = HttpStatusCode.ServiceUnavailable,
                bodyBytes = bodyBytes,
                signatureVerified = false,
                rejectReason = "NOT_CONFIGURED",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 5. Header presence.
        val signatureHeader = call.request.headers["Stripe-Signature"]
        if (signatureHeader == null) {
            recordDeliveryAndRespond(
                call = call,
                status = HttpStatusCode.Unauthorized,
                bodyBytes = bodyBytes,
                signatureVerified = false,
                rejectReason = "MISSING_SIGNATURE",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 6. Signature verification.
        val verification =
            StripeSignatureVerifier.verify(
                body = bodyBytes,
                signatureHeader = signatureHeader,
                signingSecret = pspConfig.config.webhookSigningSecret,
                now = Clock.System.now(),
                tolerance = pspConfig.config.webhookToleranceSeconds.seconds,
            )
        if (verification is StripeSignatureResult.Invalid) {
            recordDeliveryAndRespond(
                call = call,
                status = HttpStatusCode.Unauthorized,
                bodyBytes = bodyBytes,
                signatureVerified = false,
                rejectReason = verification.reason,
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 7. ONLY NOW: a linear nesting-depth scan on the raw text.
        val bodyText = bodyBytes.toString(Charsets.UTF_8)
        if (exceedsMaxJsonNestingDepth(text = bodyText, maxDepth = MAX_JSON_NESTING_DEPTH)) {
            recordDeliveryAndRespond(
                call = call,
                status = HttpStatusCode.BadRequest,
                bodyBytes = bodyBytes,
                signatureVerified = true,
                rejectReason = "JSON_TOO_DEEP",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 8. Typed decode.
        val event = runCatching { STRIPE_JSON.decodeFromString(StripeWebhookEvent.serializer(), bodyText) }.getOrNull()
        if (event == null) {
            recordDeliveryAndRespond(
                call = call,
                status = HttpStatusCode.BadRequest,
                bodyBytes = bodyBytes,
                signatureVerified = true,
                rejectReason = "MALFORMED_EVENT",
                outcome = PspWebhookOutcome.REJECTED,
            )
            return@post
        }

        // 9. Gate check -- payment_gateway_enabled + a current disclaimer acknowledgment. Off ->
        // 503 (Stripe retries; an operator may be mid-configuration), no accounting touched.
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
                status = HttpStatusCode.ServiceUnavailable,
                bodyBytes = bodyBytes,
                signatureVerified = true,
                rejectReason = "GATE_DISABLED",
                outcome = PspWebhookOutcome.REJECTED,
                eventType = event.type,
                providerEventId = event.id,
            )
            return@post
        }

        // 10. Dispatch by event.type.
        when (event.type) {
            "checkout.session.completed" -> {
                val outcome =
                    try {
                        PspWebhookIngestion.ingestCheckoutCompleted(event = event, bodyBytes = bodyBytes)
                    } catch (e: ConflictException) {
                        recordDeliveryAndRespond(
                            call = call,
                            status = HttpStatusCode.InternalServerError,
                            bodyBytes = bodyBytes,
                            signatureVerified = true,
                            rejectReason = "POSTING_UNBALANCED",
                            outcome = PspWebhookOutcome.REJECTED,
                            eventType = event.type,
                            providerEventId = event.id,
                        )
                        return@post
                    } catch (e: Exception) {
                        // MINOR fix (code review, Welle V1.2.8): a constraint violation or an
                        // IllegalArgumentException from one of the posting bridges previously escaped
                        // this handler entirely -- a bare 500 with NO psp_webhook_event row, no
                        // forensic trace at all. `ingestCheckoutCompleted` runs one synchronous,
                        // already-completed `transaction {}` (no suspension point inside it this
                        // catch could interrupt), so there is no coroutine-cancellation-swallowing
                        // concern here the way a broad `catch (e: Exception)` around a suspending body
                        // would raise. Recorded the same way the sibling ConflictException branch
                        // above already is.
                        logger.error(e) {
                            "PspWebhookRoutes: unexpected exception ingesting checkout.session.completed (event ${event.id})"
                        }
                        recordDeliveryAndRespond(
                            call = call,
                            status = HttpStatusCode.InternalServerError,
                            bodyBytes = bodyBytes,
                            signatureVerified = true,
                            rejectReason = "INTERNAL_ERROR",
                            outcome = PspWebhookOutcome.REJECTED,
                            eventType = event.type,
                            providerEventId = event.id,
                        )
                        return@post
                    }
                val (outcomeKind, paymentTransactionId) =
                    when (outcome) {
                        is CheckoutCompletedIngestionOutcome.Processed -> PspWebhookOutcome.PROCESSED to outcome.paymentTransactionId
                        is CheckoutCompletedIngestionOutcome.Duplicate -> PspWebhookOutcome.DUPLICATE to null
                        is CheckoutCompletedIngestionOutcome.Unposted -> PspWebhookOutcome.UNPOSTED to outcome.paymentTransactionId
                    }
                recordDeliveryAndRespond(
                    call = call,
                    status = HttpStatusCode.OK,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = null,
                    outcome = outcomeKind,
                    eventType = event.type,
                    providerEventId = event.id,
                    paymentTransactionId = paymentTransactionId,
                )
            }
            "checkout.session.expired" -> {
                // MINOR fix (code review round 2, Welle V1.2.8): this branch was the one dispatch
                // path still left without the forensic try/catch its `checkout.session.completed`
                // sibling above received in f8240c7 -- an ExposedSQLException out of
                // markExpiredIfStillCreated escaped as a bare 500 with NO psp_webhook_event row at
                // all, i.e. exactly the forensic blind spot that fix existed to close, just on the
                // other branch. Same synchronous, already-committed `transaction {}` shape, so the
                // same "no coroutine-cancellation-swallowing concern" reasoning applies verbatim.
                try {
                    PspWebhookIngestion.ingestCheckoutExpired(event = event)
                } catch (e: Exception) {
                    logger.error(e) {
                        "PspWebhookRoutes: unexpected exception ingesting checkout.session.expired (event ${event.id})"
                    }
                    recordDeliveryAndRespond(
                        call = call,
                        status = HttpStatusCode.InternalServerError,
                        bodyBytes = bodyBytes,
                        signatureVerified = true,
                        rejectReason = "INTERNAL_ERROR",
                        outcome = PspWebhookOutcome.REJECTED,
                        eventType = event.type,
                        providerEventId = event.id,
                    )
                    return@post
                }
                recordDeliveryAndRespond(
                    call = call,
                    status = HttpStatusCode.OK,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = null,
                    outcome = PspWebhookOutcome.PROCESSED,
                    eventType = event.type,
                    providerEventId = event.id,
                )
            }
            else -> {
                // 11. An unsupported type is graceful degradation -- Stripe retries any non-2xx for
                // days; respond 200, log IGNORED, no mutation. Same treatment
                // `dispatchInboundActivity` gives an unknown Activity `type`.
                recordDeliveryAndRespond(
                    call = call,
                    status = HttpStatusCode.OK,
                    bodyBytes = bodyBytes,
                    signatureVerified = true,
                    rejectReason = null,
                    outcome = PspWebhookOutcome.IGNORED,
                    eventType = event.type,
                    providerEventId = event.id,
                )
            }
        }
    }
}

/** Writes exactly one [PspWebhookEventLog] row (its own transaction) and responds [status] to [call] -- the one place every branch of the handler above converges. */
private suspend fun recordDeliveryAndRespond(
    call: ApplicationCall,
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
        provider = PaymentProvider.STRIPE,
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
