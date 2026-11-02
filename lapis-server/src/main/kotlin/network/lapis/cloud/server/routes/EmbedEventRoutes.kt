package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.embed.respondEmbedPreflight
import network.lapis.cloud.server.events.EventNoticeMailLimiter
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.PublicGuestRegistration
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.payment.psp.PspCheckoutGateway
import network.lapis.cloud.server.rpc.FriendRegistrationConfig
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Hard cap on a `POST /api/embed/v1/event/{slug}/registration` body -- generous for
 * `{"guestName":"...","guestEmail":"...","kommentar":"..."}`, same DoS-guard reasoning as
 * `EmbedDonationRoutes`' own `MAX_EMBED_DONATION_BODY_BYTES`.
 */
private const val MAX_EMBED_EVENT_REGISTRATION_BODY_BYTES = 4096

private val EMBED_EVENT_JSON = Json { ignoreUnknownKeys = true }

// explicitNulls = false -- a null retryAfterSeconds is OMITTED from the wire body entirely (it only
// ever appears on a 429), never serialized as a literal `"retryAfterSeconds":null`. See
// embedEventResponseFor's own KDoc "Byte-identity" for why that matters.
private val EMBED_EVENT_RESPONSE_JSON = Json { explicitNulls = false }
private val EMBED_EVENT_JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

private val logger = KotlinLogging.logger {}

@Serializable
internal data class EmbedEventRegistrationRequest(
    val guestName: String? = null,
    val guestEmail: String? = null,
    /** Honeypot -- identisch zur Formular-Route (`params["kommentar"]`, see `EventPublicRoutes` KDoc). */
    val kommentar: String? = null,
)

@Serializable
internal data class EmbedEventRegistrationResponse(
    val outcome: String,
    val retryAfterSeconds: Long? = null,
)

/**
 * V1.9.260 -- ONE neutral accepted answer plus the address-independent reasons and the pure
 * operator diagnoses (see `embedEventResponseFor`'s status-code mapping in this file's own KDoc).
 */
internal object EmbedEventOutcome {
    const val ACCEPTED = "ACCEPTED"
    const val NOT_AVAILABLE = "NOT_AVAILABLE"
    const val WAITLIST_FULL = "WAITLIST_FULL"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val GATEWAY_ERROR = "GATEWAY_ERROR"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val RATE_LIMITED = "RATE_LIMITED"
}

/** The ONE body every accepted registration sends -- all accepted paths send exactly this instance. */
private val ACCEPTED_BODY = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.ACCEPTED)

/**
 * REINE Projektion, kein Serializer: [PublicGuestRegistration.Outcome] -> (Status, Wire-Body).
 * Bewusst verlustbehaftet -- eine frische Anmeldung, eine Warteliste, ein Duplikat, eine Konto-Adresse
 * (und der Honeypot) sind ALLE `200 {"outcome":"ACCEPTED"}`, byte-identisch, auch bei kostenpflichtigen
 * Veranstaltungen (V1.9.260): ein unauthentifizierter Aufrufer kann weder erfragen, ob eine Adresse
 * bereits angemeldet ist, noch ob sie zu einem Konto gehört. Adressunabhängige Gründe (geschlossen,
 * Warteliste voll, kein Zahlungsanbieter) kommen VOR dem Adressabgleich und sehen für jede Adresse
 * gleich aus. Weder `registrationId` noch eine Wartelisten-`position` noch eine `redirectUrl`
 * erreichen je die Leitung. `explicitNulls = false` lässt `retryAfterSeconds` bei `null` vollständig
 * weg. Ausgelagert und `internal`, damit alle Zweige als reiner Unit-Test prüfbar sind, siehe
 * `EmbedEventOutcomeMappingTest`.
 */
internal fun embedEventResponseFor(outcome: PublicGuestRegistration.Outcome): Pair<HttpStatusCode, EmbedEventRegistrationResponse> =
    when (outcome) {
        PublicGuestRegistration.Outcome.Accepted -> HttpStatusCode.OK to ACCEPTED_BODY
        PublicGuestRegistration.Outcome.NotAvailable ->
            HttpStatusCode.NotFound to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.NOT_AVAILABLE)
        PublicGuestRegistration.Outcome.WaitlistFull ->
            HttpStatusCode.Conflict to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.WAITLIST_FULL)
        PublicGuestRegistration.Outcome.GatewayUnavailable ->
            HttpStatusCode.ServiceUnavailable to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.UNAVAILABLE)
        // Unreachable (the guest path never calls the provider inside the request); the provider text would stay server-side anyway.
        PublicGuestRegistration.Outcome.PaymentFailed ->
            HttpStatusCode.BadGateway to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.GATEWAY_ERROR)
    }

/**
 * Welle V1.4.3.3 "Veranstaltungs-Anmeldung als einbettbares Website-Widget" -- registers
 * `POST /api/embed/v1/event/{slug}/registration` and its `OPTIONS` preflight. Called from
 * [registerEmbedRoutes] INSIDE the `if (!config.enabled) return` gate, directly after
 * `registerEmbedDonationRoutes`, same registration discipline every other route in that function
 * follows. Builds exactly ONE [EventRegistrationSubmission] and ONE [PublicGuestRegistration] at
 * registration time (like `registerEventPublicRoutes` does), never per request. The decision logic
 * (availability probe BEFORE the address lookup, member notice, duplicate notice, deferred
 * checkout) lives in [PublicGuestRegistration], shared with the form route (V1.9.260).
 *
 * **Verbindliche Reihenfolge inside the `POST` handler, any reordering is a review blocker:**
 * 0. The entire body runs inside [withEmbedEventErrorHandling] -- no exception, expected or not,
 *    ever escapes as Ktor's bare, header-less default 500.
 * 1. [applyEmbedCors] -- `NoOriginHeader` AND `Rejected` both mean 403 here (same deviation from
 *    `/api/embed/v1/session` that `EmbedDonationRoutes` already establishes: this endpoint has no
 *    legitimate same-origin caller). The `Allowed.canonicalOrigin` value itself does NOT shape the
 *    eventual redirect (that still comes from
 *    [network.lapis.cloud.server.payment.psp.StripeReturnUrls], never from the widget's own
 *    origin) -- but it IS carried through to step 12's [PublicGuestRegistration.register] as
 *    `embedOrigin`. Since V1.9.260 a paid embed registration only reserves the seat (the checkout
 *    session is created later from the mail link). Since V1.9.261 the origin is stored on
 *    `event_registration.embed_origin` and, at checkout, copied to `payment_checkout_session` after
 *    being re-validated against the current allowlist (audit trail only).
 * 2. `X-Content-Type-Options: nosniff` -- set EXACTLY HERE, exactly once (see this file's own
 *    KDoc "F-1" below) -- never again in [respondEmbedEventJson].
 * 3. `Content-Type` MUST be `application/json`, else `415` -- this also indirectly guarantees a
 *    real preflight already ran (a browser never sends a non-simple `Content-Type` without one).
 * 4. `Content-Length` pre-check, `> 4096` -> `413`, BEFORE any body byte is read. A MISSING
 *    `Content-Length` (chunked transfer) is deliberately let through here -- unlike
 *    `registerEventPublicRoutes`' stricter form-route guard -- and left to step 5's bounded read
 *    to cap it, same posture `EmbedDonationRoutes` already establishes for its own checkout route.
 * 5. [readCappedBody] -- `null` (over budget while streaming) -> `413`.
 * 6. The GENEROUS [attemptRateLimiter] (30/60min) -- BEFORE the JSON decode, a cheap flood/DoS
 *    backstop.
 * 7. JSON decode (`runCatching`) -- failure -> `400 BAD_REQUEST`.
 * 8. Honeypot (`kommentar` non-blank) -- immediate `200 ACCEPTED` (byte-identical to a real one), no DB access, never logged,
 *    consumes NEITHER the strict rate limiter NOR any DB write. Checked BEFORE slug resolution
 *    (see "F-8" below) -- a bot gets the SAME 200 regardless of whether the slug it guessed is
 *    real, an honest caller with an unknown slug gets 404.
 * 9. Validation: `guestName` non-blank and `<= EventPolicy.MAX_GUEST_NAME_LENGTH`;
 *    `EventPolicy.normalizeGuestEmail(guestEmail)` non-null and
 *    `<= EventPolicy.MAX_GUEST_EMAIL_LENGTH` -- else `400 BAD_REQUEST`.
 * 10. Slug resolution + visibility check, in ONE `transaction {}` (a deliberate, documented
 *    micro-divergence from `registerEventPublicRoutes`' two separate transactions -- the SAME
 *    `ResultRow` `EventStore.getEventBySlugOrNull` returns already carries both `id` and
 *    `visibility`, so a second round-trip buys nothing). Unknown slug, DRAFT, CANCELLED,
 *    MEMBERS_ONLY, or any other non-`PUBLIC` visibility all render the SAME `404 NOT_AVAILABLE` --
 *    no existence oracle for an anonymous caller.
 * 11. The STRICT [registrationRateLimiter] (5/60min) -- only now, immediately before the DB/Stripe
 *    work, SHARED with `registerEventPublicRoutes`' own form route (same limiter instances, see
 *    `registerEmbedRoutes`' own call site) so the widget cannot be used to double a real
 *    registrant's hourly budget.
 * 12. [PublicGuestRegistration.register] (no message: the embed form has no message field).
 * 13. [embedEventResponseFor] -> [respondEmbedEventJson].
 */
internal fun Route.registerEmbedEventRoutes(
    config: EmbedConfig,
    checkoutGateways: Map<PaymentProvider, PspCheckoutGateway>,
    mailDispatcher: MailDispatcher,
    baseUrl: String,
    attemptRateLimiter: FederationInboxRateLimiter,
    registrationRateLimiter: FederationInboxRateLimiter,
    pageRateLimiter: FederationInboxRateLimiter,
    // V1.9.260 -- the SAME instance the form route uses; no default, so no second instance can silently double the notice-mail budget.
    noticeMailLimiter: EventNoticeMailLimiter,
    friendRegistrationConfig: FriendRegistrationConfig = FriendRegistrationConfig.load(),
) {
    val submission =
        EventRegistrationSubmission(
            checkoutGateways = checkoutGateways,
            baseUrl = baseUrl,
            mailDispatcher = mailDispatcher,
            embedConfig = config,
        )
    val guestRegistration =
        PublicGuestRegistration(
            submission = submission,
            noticeMailLimiter = noticeMailLimiter,
            friendRegistrationConfig = friendRegistrationConfig,
        )

    post("/api/embed/v1/event/{slug}/registration") {
        call.withEmbedEventErrorHandling {
            // 1. CORS first -- NoOriginHeader is ALSO 403 here (deviation from /api/embed/v1/session,
            // same reasoning EmbedDonationRoutes documents for its own checkout route): this
            // endpoint has no legitimate same-origin caller. `canonicalOrigin` -- the STORED
            // allowlist entry, never the raw request value -- is kept (Security-Review MINOR fix)
            // and threaded down into EventRegistrationSubmission.submit, which stores it on the
            // registration (V1.9.261); the mail-link checkout later copies it to payment_checkout_session
            // after re-validating it against the current allowlist.
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            val canonicalOrigin =
                when (cors) {
                    is EmbedCorsResult.Allowed -> cors.canonicalOrigin
                    EmbedCorsResult.Rejected, EmbedCorsResult.NoOriginHeader -> {
                        call.respondEmbedForbiddenOrigin()
                        return@withEmbedEventErrorHandling
                    }
                }

            // 2. nosniff -- set EXACTLY here, exactly once. See this file's KDoc "F-1": header() is
            // headers.append(), never a replace -- respondEmbedEventJson below must NOT set it again.
            call.response.header("X-Content-Type-Options", "nosniff")

            // 3. Content-Type MUST be application/json -- also guarantees a real preflight already
            // ran (a browser never sends a non-simple Content-Type without one first).
            if (!call.request.contentType().match(ContentType.Application.Json)) {
                call.respond(HttpStatusCode.UnsupportedMediaType)
                return@withEmbedEventErrorHandling
            }

            // 4. Content-Length pre-check, BEFORE any body byte is read. A MISSING header is
            // deliberately let through (chunked transfer) -- step 5's bounded read is the real
            // authority, same posture EmbedDonationRoutes already establishes for its own route
            // (OF-2, plan open question: this diverges from registerEventPublicRoutes' own,
            // stricter form-route guard, which rejects a missing Content-Length outright).
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength != null && declaredContentLength > MAX_EMBED_EVENT_REGISTRATION_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@withEmbedEventErrorHandling
            }

            // 5. Bounded streaming read.
            val bodyBytes = readCappedBody(call = call, maxBytes = MAX_EMBED_EVENT_REGISTRATION_BODY_BYTES)
            if (bodyBytes == null) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@withEmbedEventErrorHandling
            }

            // 6. GENEROUS flood/DoS gate -- BEFORE the JSON decode.
            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!attemptRateLimiter.checkAndRecord(rateLimitKey)) {
                val retryAfterSeconds = attemptRateLimiter.retryAfterSeconds(rateLimitKey)
                call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respondEmbedEventJson(
                    status = HttpStatusCode.TooManyRequests,
                    body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.RATE_LIMITED, retryAfterSeconds = retryAfterSeconds),
                )
                return@withEmbedEventErrorHandling
            }

            // 7. JSON decode.
            val request =
                runCatching {
                    EMBED_EVENT_JSON.decodeFromString(EmbedEventRegistrationRequest.serializer(), bodyBytes.toString(Charsets.UTF_8))
                }.getOrNull()
            if (request == null) {
                call.respondEmbedEventJson(
                    status = HttpStatusCode.BadRequest,
                    body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.BAD_REQUEST),
                )
                return@withEmbedEventErrorHandling
            }

            // 8. Honeypot -- BEFORE slug resolution (see this file's KDoc "F-8"): a bot gets the
            // SAME 200 ACCEPTED regardless of which slug it guessed, an honest caller with an
            // unknown slug gets 404 instead. No DB access, never logged, no rate-limiter
            // consumption beyond step 6.
            if (!request.kommentar.isNullOrBlank()) {
                call.respondEmbedEventJson(status = HttpStatusCode.OK, body = ACCEPTED_BODY)
                return@withEmbedEventErrorHandling
            }

            // 9. Validation.
            val guestName = request.guestName?.trim().orEmpty()
            val normalizedEmail = EventPolicy.normalizeGuestEmail(request.guestEmail)
            if (guestName.isBlank() ||
                guestName.length > EventPolicy.MAX_GUEST_NAME_LENGTH ||
                normalizedEmail == null ||
                normalizedEmail.length > EventPolicy.MAX_GUEST_EMAIL_LENGTH
            ) {
                call.respondEmbedEventJson(
                    status = HttpStatusCode.BadRequest,
                    body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.BAD_REQUEST),
                )
                return@withEmbedEventErrorHandling
            }

            // 10. Slug resolution + visibility -- ONE transaction (see this file's KDoc, deliberate
            // micro-divergence from registerEventPublicRoutes' two separate transactions). Unknown
            // slug/DRAFT/CANCELLED/MEMBERS_ONLY/any other non-PUBLIC visibility -> the SAME 404 --
            // no existence oracle.
            val slug = call.parameters["slug"]
            val eventId =
                slug?.let {
                    transaction {
                        val row = EventStore.getEventBySlugOrNull(it)
                        if (row != null && row[EventTable.visibility] == EventVisibility.PUBLIC) row[EventTable.id] else null
                    }
                }
            if (eventId == null) {
                call.respondEmbedEventJson(
                    status = HttpStatusCode.NotFound,
                    body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.NOT_AVAILABLE),
                )
                return@withEmbedEventErrorHandling
            }

            // 11. STRICT per-real-attempt budget -- only now, immediately before the DB/Stripe work,
            // SHARED with registerEventPublicRoutes' own form route (same limiter instances).
            if (!registrationRateLimiter.checkAndRecord(rateLimitKey)) {
                val retryAfterSeconds = registrationRateLimiter.retryAfterSeconds(rateLimitKey)
                call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respondEmbedEventJson(
                    status = HttpStatusCode.TooManyRequests,
                    body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.RATE_LIMITED, retryAfterSeconds = retryAfterSeconds),
                )
                return@withEmbedEventErrorHandling
            }

            // 12-13. Delegate to the fachlogik, map the result.
            val outcome =
                guestRegistration.register(
                    eventId = eventId,
                    guestName = guestName,
                    normalizedEmail = normalizedEmail,
                    message = null,
                    embedOrigin = canonicalOrigin,
                )
            val (status, body) = embedEventResponseFor(outcome)
            call.respondEmbedEventJson(status = status, body = body)
        }
    }

    // Exact mirror of EmbedDonationRoutes' own OPTIONS preflight shape, gated by the soft,
    // every-request pageRateLimiter (60/min) rather than either of the two registration budgets --
    // a browser's own preflight before every real POST must never eat into either.
    options("/api/embed/v1/event/{slug}/registration") {
        if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@options
        }
        val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
        if (cors is EmbedCorsResult.Rejected) {
            call.respondEmbedForbiddenOrigin()
        } else {
            call.respondEmbedPreflight(allowedMethods = "POST, OPTIONS")
        }
    }
}

private suspend fun ApplicationCall.respondEmbedEventJson(
    status: HttpStatusCode,
    body: EmbedEventRegistrationResponse,
) {
    // NO nosniff here -- the POST handler's own step 2 already set it, exactly once (see this
    // file's KDoc "F-1": response.header() is headers.append(), not a replace).
    respondText(
        text = EMBED_EVENT_RESPONSE_JSON.encodeToString(EmbedEventRegistrationResponse.serializer(), body),
        contentType = EMBED_EVENT_JSON_CONTENT_TYPE,
        status = status,
    )
}

/**
 * Same guarantee [network.lapis.cloud.server.routes.registerEventPublicRoutes]'s own
 * `withEventPublicErrorHandling` establishes for its route family -- plain `try`/`catch`, NOT
 * `runCatching` (`runCatching` also catches [CancellationException], which would silently swallow
 * a client-disconnect cancellation and then attempt a second, doomed `respond*` on an already-dead
 * call -- same fix, same reasoning that function's own KDoc documents). Logs the request PATH only,
 * never the full URI/query string and never `guestName`/`guestEmail` -- this handler never puts a
 * bearer-equivalent token in the query string the way the storno/zahlung routes do, but the same
 * house discipline applies regardless.
 */
private suspend fun ApplicationCall.withEmbedEventErrorHandling(handler: suspend () -> Unit) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Unhandled exception in the embed event registration handler (${request.path()})" }
        runCatching {
            respondEmbedEventJson(
                status = HttpStatusCode.InternalServerError,
                body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.UNAVAILABLE),
            )
        }
    }
}
