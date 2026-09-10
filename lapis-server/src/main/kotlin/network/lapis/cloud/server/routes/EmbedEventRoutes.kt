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
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Hard cap on a `POST /api/embed/v1/event/{slug}/registration` body -- generous for
 * `{"guestName":"...","guestEmail":"...","kommentar":"..."}`, same DoS-guard reasoning as
 * `EmbedDonationRoutes`' own `MAX_EMBED_DONATION_BODY_BYTES`.
 */
private const val MAX_EMBED_EVENT_REGISTRATION_BODY_BYTES = 4096

private val EMBED_EVENT_JSON = Json { ignoreUnknownKeys = true }

// explicitNulls = false -- a null redirectUrl/retryAfterSeconds is OMITTED from the wire body
// entirely, never serialized as a literal `"redirectUrl":null`. See embedEventResponseFor's own
// KDoc "Byte-identity" for why that matters.
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
    val redirectUrl: String? = null,
    val retryAfterSeconds: Long? = null,
)

/**
 * The four visitor-facing outcomes this endpoint's wire contract distinguishes, plus the three
 * pure operator diagnoses (see `embedEventResponseFor`'s status-code mapping in this file's own
 * KDoc).
 */
internal object EmbedEventOutcome {
    const val CONFIRMED = "CONFIRMED"
    const val WAITLISTED = "WAITLISTED"
    const val PAYMENT_REQUIRED = "PAYMENT_REQUIRED"
    const val NOT_AVAILABLE = "NOT_AVAILABLE"
    const val WAITLIST_FULL = "WAITLIST_FULL"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val GATEWAY_ERROR = "GATEWAY_ERROR"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val RATE_LIMITED = "RATE_LIMITED"
}

/**
 * REINE Projektion, kein Serializer: [EventRegistrationResult] -> (Status, Wire-Body). Bewusst
 * verlustbehaftet -- [EventRegistrationResult.AlreadyRegistered] ist byte-identisch zu
 * [EventRegistrationResult.Confirmed], und weder `registrationId` noch die Wartelisten-`position`
 * erreichen je die Leitung (kein Enumerations-Orakel über bestehende Anmeldungen für einen
 * unauthentifizierten Aufrufer -- eine Parteiveranstaltung, deren Anmeldungsliste sich per HTTP
 * erfragen ließe, wäre Mitgliedschaftsaufklärung). Ausgelagert und `internal`, damit alle acht
 * Zweige (inkl. `WaitlistFull`, das end-to-end 500 Wartelisten-Zeilen bräuchte) als reiner
 * Unit-Test prüfbar sind, siehe `EmbedEventOutcomeMappingTest`.
 *
 * **Byte-identity gilt NUR für gebührenfreie Veranstaltungen** (Security-Review MINOR fix --
 * vorher stand hier fälschlich eine uneingeschränkte Garantie): `Confirmed`/`AlreadyRegistered`/ein
 * Honeypot-Treffer liefern bei `feeAmount == 0` alle drei exakt `{"outcome":"CONFIRMED"}` --
 * `EMBED_EVENT_RESPONSE_JSON` (nicht handgeschriebene Interpolation wie
 * `EmbedDonationRoutes.jsonString`) escaped `redirectUrl` korrekt UND garantiert diese
 * Byte-Identität, weil `explicitNulls = false` das `redirectUrl`-/`retryAfterSeconds`-Feld bei
 * `null` vollständig weglässt statt es als `null`-Literal zu serialisieren. Auf einer KOSTENPFLICHTIGEN
 * Veranstaltung dagegen unterscheiden sich die beiden Fälle sehr wohl auf der Leitung:
 * `AlreadyRegistered` bleibt `200 {"outcome":"CONFIRMED"}`, eine echte neue Anmeldung wird
 * `200 {"outcome":"PAYMENT_REQUIRED","redirectUrl":...}` -- ein unauthentifizierter Aufrufer kann
 * damit per `curl` (unter Nutzung einer der öffentlich im ausgelieferten Widget-Bundle stehenden
 * Partner-Origins) für eine bekannte E-Mail-Adresse erfragen, ob sie bereits für eine
 * kostenpflichtige Parteiveranstaltung angemeldet ist -- begrenzt durch den strikten
 * `registrationRateLimiter` (5 Versuche/60min pro Quell-IP), der eine gezielte Einzelabfrage aber
 * nicht verhindert. Dieselbe Unterscheidung existiert unverändert auch auf der
 * server-gerenderten Formular-Route (`EventPublicRoutes`, Redirect nach `/danke` vs. Stripe-URL) --
 * keine Regression dieser Welle, aber eine Lücke, die ein künftiger Fix schließen sollte (z. B.
 * durch einen Zahlungs-Wiederaufnahme-Redirect statt `CONFIRMED` für `AlreadyRegistered` auf einem
 * kostenpflichtigen Event), siehe auch `docs/api/embed-widgets.adoc`.
 */
internal fun embedEventResponseFor(result: EventRegistrationResult): Pair<HttpStatusCode, EmbedEventRegistrationResponse> =
    when (result) {
        is EventRegistrationResult.Confirmed ->
            HttpStatusCode.OK to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.CONFIRMED)
        EventRegistrationResult.AlreadyRegistered ->
            HttpStatusCode.OK to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.CONFIRMED)
        is EventRegistrationResult.Waitlisted ->
            HttpStatusCode.OK to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.WAITLISTED)
        is EventRegistrationResult.PaymentRequired ->
            HttpStatusCode.OK to
                EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.PAYMENT_REQUIRED, redirectUrl = result.redirectUrl)
        EventRegistrationResult.EventNotAvailable ->
            HttpStatusCode.NotFound to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.NOT_AVAILABLE)
        EventRegistrationResult.WaitlistFull ->
            HttpStatusCode.Conflict to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.WAITLIST_FULL)
        EventRegistrationResult.GatewayUnavailable ->
            HttpStatusCode.ServiceUnavailable to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.UNAVAILABLE)
        is EventRegistrationResult.StripeFailed ->
            // result.message (Stripe's own text) stays server-side only -- never delivered here,
            // same posture EmbedDonationRoutes' own StripeFailed branch establishes.
            HttpStatusCode.BadGateway to EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.GATEWAY_ERROR)
    }

/**
 * Welle V1.4.3.3 "Veranstaltungs-Anmeldung als einbettbares Website-Widget" -- registers
 * `POST /api/embed/v1/event/{slug}/registration` and its `OPTIONS` preflight. Called from
 * [registerEmbedRoutes] INSIDE the `if (!config.enabled) return` gate, directly after
 * `registerEmbedDonationRoutes`, same registration discipline every other route in that function
 * follows. Builds exactly ONE [EventRegistrationSubmission] at registration time (like
 * `registerEventPublicRoutes` does), never per request.
 *
 * **Verbindliche Reihenfolge inside the `POST` handler, any reordering is a review blocker:**
 * 0. The entire body runs inside [withEmbedEventErrorHandling] -- no exception, expected or not,
 *    ever escapes as Ktor's bare, header-less default 500.
 * 1. [applyEmbedCors] -- `NoOriginHeader` AND `Rejected` both mean 403 here (same deviation from
 *    `/api/embed/v1/session` that `EmbedDonationRoutes` already establishes: this endpoint has no
 *    legitimate same-origin caller). The `Allowed.canonicalOrigin` value itself does NOT shape the
 *    eventual redirect (that still comes from
 *    [network.lapis.cloud.server.payment.psp.StripeReturnUrls], never from the widget's own
 *    origin) -- but it IS carried through to step 12's [EventRegistrationSubmission.submit] as
 *    `embedOrigin` (Security-Review MINOR fix), so a paid registration's
 *    `payment_checkout_session` row records which embed partner it came through, same as
 *    `AnonymousDonationCheckout` already does for the donation path.
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
 * 8. Honeypot (`kommentar` non-blank) -- immediate `200 CONFIRMED`, no DB access, never logged,
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
 * 12. [EventRegistrationSubmission.submit].
 * 13. [embedEventResponseFor] -> [respondEmbedEventJson].
 */
internal fun Route.registerEmbedEventRoutes(
    config: EmbedConfig,
    pspConfigState: PspConfigState,
    checkoutClient: StripeCheckoutClient?,
    mailDispatcher: MailDispatcher,
    baseUrl: String,
    attemptRateLimiter: FederationInboxRateLimiter,
    registrationRateLimiter: FederationInboxRateLimiter,
    pageRateLimiter: FederationInboxRateLimiter,
) {
    val submission =
        EventRegistrationSubmission(
            pspConfigState = pspConfigState,
            checkoutClient = checkoutClient,
            baseUrl = baseUrl,
            mailDispatcher = mailDispatcher,
        )

    post("/api/embed/v1/event/{slug}/registration") {
        call.withEmbedEventErrorHandling {
            // 1. CORS first -- NoOriginHeader is ALSO 403 here (deviation from /api/embed/v1/session,
            // same reasoning EmbedDonationRoutes documents for its own checkout route): this
            // endpoint has no legitimate same-origin caller. `canonicalOrigin` -- the STORED
            // allowlist entry, never the raw request value -- is kept (Security-Review MINOR fix)
            // and threaded down into EventRegistrationSubmission.submit so a paid registration's
            // payment_checkout_session row records which embed partner it came through, mirroring
            // AnonymousDonationCheckout's own canonicalOrigin -> embedOrigin handling.
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
            // SAME 200 CONFIRMED regardless of which slug it guessed, an honest caller with an
            // unknown slug gets 404 instead. No DB access, never logged, no rate-limiter
            // consumption beyond step 6.
            if (!request.kommentar.isNullOrBlank()) {
                call.respondEmbedEventJson(
                    status = HttpStatusCode.OK,
                    body = EmbedEventRegistrationResponse(outcome = EmbedEventOutcome.CONFIRMED),
                )
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
            val result =
                submission.submit(
                    eventId = eventId,
                    participant = EventParticipant.Guest(name = guestName, normalizedEmail = normalizedEmail),
                    embedOrigin = canonicalOrigin,
                )
            val (status, body) = embedEventResponseFor(result)
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
