package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.formFieldLimit
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventCapacityGuard
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.mailPromotion
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.security.MessageDigest

/** Hard cap on `POST /veranstaltung/{slug}/anmeldung`'s form body -- generous for name+email+honeypot, same DoS-guard reasoning as `SocialPublicRoutes.REPORT_MAX_BODY_BYTES`. */
private const val MAX_EVENT_REGISTRATION_BODY_BYTES = 4096L

/** `MAX_EVENT_REGISTRATION_BODY_BYTES`, but for Ktor's OWN form-field buffering cap (default 50 MiB) -- see `registerEventPublicRoutes` KDoc "Content-Length-Deckel". */
private const val EVENT_FORM_FIELD_LIMIT = MAX_EVENT_REGISTRATION_BODY_BYTES

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- the unauthenticated, server-rendered public surface: event
 * detail + registration form (`/veranstaltung/{slug}`), the four always-200 return pages
 * (`/danke`/`/warteliste`/`/abgebrochen`/`/storno`), and nothing else. Registered before
 * `staticFiles` in `Application.kt`, same "literal beats catch-all" reasoning
 * `registerSocialPublicRoutes` KDoc documents.
 *
 * **Deliberately a classic `<form method=post>` flow, no JavaScript, no Kilua RPC client** -- see
 * `EventPublicHtml` KDoc. The JSON variant Design-Spec point 19 envisioned
 * (`POST /api/public/v1/event/{slug}/registration`) is intentionally NOT built in this wave (design-
 * team plan open question OF-3): it would have no consumer yet (no embed widget), and is an
 * additional unauthenticated, cross-origin money endpoint -- exactly the surface the security loop
 * exists to scrutinize. Deferred to the wave that actually builds the widget.
 *
 * **Missbrauchsschutz, vier Schichten** (see design-team plan §6.3):
 * 1. `uq_event_registration_active_participant` (`V18__events.sql`) -- the primary, DB-level guard.
 * 2. Honeypot (`kommentar` field, [EventPublicHtml] KDoc) -- a hit is a silent no-op, HTTP 200,
 *    redirect to `/abgebrochen`, consumes NEITHER rate limiter, no DB write, no log entry with the
 *    submitted value.
 * 3. Two rate limiters: [attemptRateLimiter] (generous, checked BEFORE the body is even read --
 *    pure flood/DoS backstop) and [registrationRateLimiter] (strict, checked immediately before the
 *    DB/Stripe work in `EventRegistrationSubmission` -- honeypot hits and closed/unknown events never
 *    consume it). [pageRateLimiter] is a third, separate, soft limiter for the read-only GET routes.
 * 4. [MAX_EVENT_REGISTRATION_BODY_BYTES] content-length cap, checked BEFORE any body byte is read,
 *    plus [EVENT_FORM_FIELD_LIMIT] on Ktor's own (50 MiB default) form-field buffering.
 *
 * Every handler body runs inside [withEventPublicErrorHandling] -- no exception, expected or not,
 * ever escapes as Ktor's bare, header-less default 500 (same discipline `SocialPublicRoutes
 * .withPublicErrorHandling` establishes for its own route family; that function is not reused
 * directly here because its error page is `SocialPublicHtml`-specific).
 */
internal fun Route.registerEventPublicRoutes(
    pspConfigState: PspConfigState,
    checkoutClient: StripeCheckoutClient?,
    baseUrl: String,
    mailDispatcher: MailDispatcher,
    brandTitle: String,
    pageRateLimiter: FederationInboxRateLimiter,
    attemptRateLimiter: FederationInboxRateLimiter,
    registrationRateLimiter: FederationInboxRateLimiter,
) {
    val submission =
        EventRegistrationSubmission(
            pspConfigState = pspConfigState,
            checkoutClient = checkoutClient,
            baseUrl = baseUrl,
            mailDispatcher = mailDispatcher,
        )

    get("/veranstaltung/{slug}") {
        call.withEventPublicErrorHandling(brandTitle = brandTitle) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"]
            val view = slug?.let { loadPublicEventView(it) }
            if (view == null) {
                call.respondEventNotFound(brandTitle)
                return@withEventPublicErrorHandling
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyEventPublicPageHeaders()
            call.respondText(text = EventPublicHtml.eventPage(brandTitle = brandTitle, view = view), contentType = HTML_CONTENT_TYPE)
        }
    }

    post("/veranstaltung/{slug}/anmeldung") {
        call.withEventPublicErrorHandling(brandTitle = brandTitle) {
            val slug = call.parameters["slug"]
            if (slug == null) {
                call.respondEventNotFound(brandTitle)
                return@withEventPublicErrorHandling
            }
            // 1. Generous flood/DoS gate -- BEFORE any body read.
            if (!attemptRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            // 2. Content-Length pre-check, before any body byte is read.
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength == null || declaredContentLength > MAX_EVENT_REGISTRATION_BODY_BYTES) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.PayloadTooLarge)
                return@withEventPublicErrorHandling
            }
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.BadRequest)
                return@withEventPublicErrorHandling
            }
            call.formFieldLimit = EVENT_FORM_FIELD_LIMIT
            val params = call.receiveParameters()
            // 3. Honeypot -- silent no-op, identical response shape as a real submission would use
            // for the "cancelled" outcome, no DB write, no rate-limiter consumption beyond step 1.
            val honeypotFilled = !params["kommentar"].isNullOrBlank()
            if (honeypotFilled) {
                call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
                return@withEventPublicErrorHandling
            }
            val guestName = params["guestName"]?.trim().orEmpty()
            val rawEmail = params["guestEmail"]
            val normalizedEmail = EventPolicy.normalizeGuestEmail(rawEmail)
            if (guestName.isBlank() ||
                guestName.length > EventPolicy.MAX_GUEST_NAME_LENGTH ||
                normalizedEmail == null ||
                normalizedEmail.length > EventPolicy.MAX_GUEST_EMAIL_LENGTH
            ) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.BadRequest)
                return@withEventPublicErrorHandling
            }
            val eventId = transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
            if (eventId == null) {
                call.respondEventNotFound(brandTitle)
                return@withEventPublicErrorHandling
            }
            val visible =
                transaction {
                    val row = EventStore.getEventOrNull(eventId)
                    row != null && row[EventTable.visibility] == EventVisibility.PUBLIC
                }
            if (!visible) {
                // DRAFT/CANCELLED/MEMBERS_ONLY/unknown all render the SAME 404 -- no existence oracle.
                call.respondEventNotFound(brandTitle)
                return@withEventPublicErrorHandling
            }
            // 4. Strict per-real-attempt budget -- only now, immediately before the DB/Stripe work.
            if (!registrationRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val result =
                submission.submit(
                    eventId = eventId,
                    participant = EventParticipant.Guest(name = guestName, normalizedEmail = normalizedEmail),
                )
            when (result) {
                is EventRegistrationResult.Confirmed -> call.respondEventRedirect("/veranstaltung/$slug/danke?r=${result.registrationId}")
                is EventRegistrationResult.PaymentRequired -> call.respondEventRedirect(result.redirectUrl)
                is EventRegistrationResult.Waitlisted -> call.respondEventRedirect("/veranstaltung/$slug/warteliste")
                EventRegistrationResult.AlreadyRegistered -> call.respondEventRedirect("/veranstaltung/$slug/danke")
                EventRegistrationResult.EventNotAvailable -> {
                    call.respondEventNotFound(brandTitle)
                }
                EventRegistrationResult.WaitlistFull -> call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
                EventRegistrationResult.GatewayUnavailable -> call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
                is EventRegistrationResult.StripeFailed -> call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
            }
        }
    }

    get("/veranstaltung/{slug}/danke") {
        call.respondEventReturnPage(brandTitle = brandTitle, rateLimiter = pageRateLimiter) {
            EventPublicHtml.thanksPage(brandTitle = brandTitle, registrationId = call.parameters["r"])
        }
    }

    get("/veranstaltung/{slug}/warteliste") {
        call.respondEventReturnPage(brandTitle = brandTitle, rateLimiter = pageRateLimiter) {
            EventPublicHtml.waitlistPage(brandTitle = brandTitle)
        }
    }

    get("/veranstaltung/{slug}/abgebrochen") {
        call.respondEventReturnPage(brandTitle = brandTitle, rateLimiter = pageRateLimiter) {
            EventPublicHtml.cancelledPage(brandTitle = brandTitle)
        }
    }

    get("/veranstaltung/{slug}/storno") {
        call.withEventPublicErrorHandling(brandTitle = brandTitle) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val token = call.parameters["token"].orEmpty()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyEventPublicPageHeaders()
            // GET never mutates (a mail-client link-prefetch must never storno a registration on its
            // own) -- always the confirmation form, even for an unknown/expired token; the actual
            // lookup happens only on POST.
            call.respondText(
                text = EventPublicHtml.cancelConfirmPage(brandTitle = brandTitle, slug = slug, token = token),
                contentType = HTML_CONTENT_TYPE,
            )
        }
    }

    post("/veranstaltung/{slug}/storno") {
        call.withEventPublicErrorHandling(brandTitle = brandTitle) {
            if (!attemptRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength == null || declaredContentLength > MAX_EVENT_REGISTRATION_BODY_BYTES) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.PayloadTooLarge)
                return@withEventPublicErrorHandling
            }
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.BadRequest)
                return@withEventPublicErrorHandling
            }
            call.formFieldLimit = EVENT_FORM_FIELD_LIMIT
            val slug = call.parameters["slug"].orEmpty()
            val params = call.receiveParameters()
            val token = params["token"].orEmpty()
            val now = DbClock.nowLocalDateTime()
            if (token.isNotBlank()) {
                val eventId = transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
                if (eventId != null) {
                    val tokenHash = sha256Hex(token.toByteArray(Charsets.US_ASCII))
                    val registration = transaction { EventStore.findByCancelTokenHash(eventId = eventId, cancelTokenSha256 = tokenHash) }
                    if (registration != null) {
                        val registrationId = registration[EventRegistrationTable.id]
                        // Constant-time compare against the row we just looked up BY its own hash --
                        // this second comparison is defense-in-depth (the DB lookup above already
                        // matched by hash), never trust a raw `==` on a secret-derived value.
                        val storedHash = registration[EventRegistrationTable.cancelTokenSha256]
                        if (storedHash != null &&
                            MessageDigest.isEqual(tokenHash.toByteArray(Charsets.US_ASCII), storedHash.toByteArray(Charsets.US_ASCII))
                        ) {
                            val (_, promotions) =
                                EventCapacityGuard.withEventLock(eventId = eventId, now = now) { _ ->
                                    EventStore.cancelRegistration(id = registrationId, now = now)
                                }
                            // Review MAJOR fix: this used to build its own ad-hoc, detail-free mail
                            // inline instead of using the shared `mailPromotion` every OTHER promotion
                            // trigger already goes through -- a promotion caused by a public-route
                            // storno got a DIFFERENT (and, for a paid event, unusable -- no payment
                            // link at all) message than the exact same promotion caused via the
                            // authenticated RPC path. See `EventWaitlist.mailPromotion` KDoc.
                            promotions.forEach { it.mailPromotion(mailDispatcher) }
                        }
                    }
                }
            }
            // ALWAYS the same neutral success page -- unknown/expired token, already-cancelled
            // registration, or a genuine cancellation all look identical to the caller.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyEventPublicPageHeaders()
            call.respondText(text = EventPublicHtml.cancelledSuccessPage(brandTitle = brandTitle), contentType = HTML_CONTENT_TYPE)
        }
    }

    // Review MAJOR fix -- the missing counterpart to `EventWaitlist.promoteWhileCapacityFree`'s
    // PENDING_PAYMENT promotion on a paid event: without this route pair, a waitlist-promoted
    // registrant had NO way to ever pay for the seat they were offered. Reached via the token
    // `WaitlistPromotion.mailPromotion` embeds in its mail -- the SAME token/column `/storno` uses
    // (`EventStore.findByCancelTokenHash`), rotated fresh at promotion time (see
    // `EventWaitlist.promoteWhileCapacityFree` KDoc), so it supersedes any earlier storno link for
    // the same registration. Same GET-renders-confirmation/POST-mutates split as `/storno` above --
    // a mail-client link-prefetch must never start a Stripe checkout on its own.
    get("/veranstaltung/{slug}/zahlung") {
        call.withEventPublicErrorHandling(brandTitle = brandTitle) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val token = call.parameters["token"].orEmpty()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyEventPublicPageHeaders()
            call.respondText(
                text = EventPublicHtml.paymentConfirmPage(brandTitle = brandTitle, slug = slug, token = token),
                contentType = HTML_CONTENT_TYPE,
            )
        }
    }

    post("/veranstaltung/{slug}/zahlung") {
        call.withEventPublicErrorHandling(brandTitle = brandTitle) {
            if (!attemptRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength == null || declaredContentLength > MAX_EVENT_REGISTRATION_BODY_BYTES) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.PayloadTooLarge)
                return@withEventPublicErrorHandling
            }
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondEventMalformed(brandTitle = brandTitle, status = HttpStatusCode.BadRequest)
                return@withEventPublicErrorHandling
            }
            call.formFieldLimit = EVENT_FORM_FIELD_LIMIT
            val slug = call.parameters["slug"].orEmpty()
            val params = call.receiveParameters()
            val token = params["token"].orEmpty()
            if (token.isBlank()) {
                call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
                return@withEventPublicErrorHandling
            }
            val eventId = transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
            if (eventId == null) {
                call.respondEventNotFound(brandTitle)
                return@withEventPublicErrorHandling
            }
            val tokenHash = sha256Hex(token.toByteArray(Charsets.US_ASCII))
            val registration = transaction { EventStore.findByCancelTokenHash(eventId = eventId, cancelTokenSha256 = tokenHash) }
            // Same neutral "cancelled" outcome for every dead-end (unknown token, wrong status,
            // hash mismatch) -- no oracle for an anonymous caller, same posture the rest of this
            // file's public surface establishes.
            if (registration == null || registration[EventRegistrationTable.status] != EventRegistrationStatus.PENDING_PAYMENT) {
                call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
                return@withEventPublicErrorHandling
            }
            val storedHash = registration[EventRegistrationTable.cancelTokenSha256]
            if (storedHash == null ||
                !MessageDigest.isEqual(tokenHash.toByteArray(Charsets.US_ASCII), storedHash.toByteArray(Charsets.US_ASCII))
            ) {
                call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
                return@withEventPublicErrorHandling
            }
            // Strict per-real-attempt budget -- only now, immediately before the Stripe call, same
            // "cheap gates first, expensive/network-bound work last" ordering `/anmeldung` establishes.
            if (!registrationRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(brandTitle)
                return@withEventPublicErrorHandling
            }
            val registrationId = registration[EventRegistrationTable.id]
            val result = submission.resumeCheckout(eventId = eventId, registrationId = registrationId)
            when (result) {
                is EventRegistrationResult.PaymentRequired -> call.respondEventRedirect(result.redirectUrl)
                else -> call.respondEventRedirect("/veranstaltung/$slug/abgebrochen")
            }
        }
    }
}

/**
 * Shared handler body for the three always-200 return pages (`danke`/`warteliste`/`abgebrochen`) --
 * same "never an error status at the exact moment the visitor just registered/cancelled" reasoning
 * `EmbedDonationRoutes.respondDonationReturnPage` KDoc documents. Wrapped in its own `runCatching`
 * (not just the caller's [withEventPublicErrorHandling]) so a late failure here still gets this
 * file's own security headers rather than Ktor's bare default.
 */
private suspend fun ApplicationCall.respondEventReturnPage(
    brandTitle: String,
    rateLimiter: FederationInboxRateLimiter,
    render: () -> String,
) {
    runCatching {
        response.header(HttpHeaders.CacheControl, "no-store")
        if (!rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = request.origin.remoteHost))) {
            applyEventPublicPageHeaders()
            respondText(text = render(), contentType = HTML_CONTENT_TYPE, status = HttpStatusCode.TooManyRequests)
            return@runCatching
        }
        applyEventPublicPageHeaders()
        respondText(text = render(), contentType = HTML_CONTENT_TYPE)
    }.onFailure {
        runCatching { respondEventServerError(brandTitle) }
    }
}

private fun loadPublicEventView(slug: String): EventPublicHtml.View? =
    transaction {
        val row = EventStore.getEventBySlugOrNull(slug) ?: return@transaction null
        if (row[EventTable.visibility] != EventVisibility.PUBLIC || row[EventTable.status] != EventStatus.PUBLISHED) return@transaction null
        val eventId = row[EventTable.id]
        val now = DbClock.nowLocalDateTime()
        val capacity = row[EventTable.capacity]
        val occupied = EventStore.countOccupied(eventId = eventId, now = now)
        val full = capacity != null && occupied >= capacity
        val fee = row[EventTable.feeAmount]
        val feeLabel = if (fee.compareTo(BigDecimal.ZERO) == 0) "kostenlos" else "$fee ${row[EventTable.feeCurrency]}"
        val registrationOpen =
            EventPolicy.isRegistrationOpen(
                status = row[EventTable.status],
                registrationClosesAt = row[EventTable.registrationClosesAt],
                startsAt = row[EventTable.startsAt],
                now = now,
            )
        EventPublicHtml.View(
            title = row[EventTable.title],
            slug = row[EventTable.slug],
            description = row[EventTable.description],
            locationText = row[EventTable.locationText],
            onlineUrl = row[EventTable.onlineUrl],
            startsAt = row[EventTable.startsAt],
            endsAt = row[EventTable.endsAt],
            feeLabel = feeLabel,
            full = full,
            registrationOpen = registrationOpen,
        )
    }

/** Same header set `SocialPublicRoutes.applyPublicPageHeaders` establishes -- no `<script>` on this surface either, so an identical, maximally strict CSP applies. */
internal fun ApplicationCall.applyEventPublicPageHeaders() {
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    )
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Frame-Options", "DENY")
    response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
}

private suspend fun ApplicationCall.respondEventRedirect(location: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPublicPageHeaders()
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode(303, "See Other"))
}

private suspend fun ApplicationCall.respondEventNotFound(brandTitle: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPublicPageHeaders()
    respondText(text = EventPublicHtml.notFoundPage(brandTitle), contentType = HTML_CONTENT_TYPE, status = HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.respondEventTooManyRequests(brandTitle: String) {
    response.header(HttpHeaders.RetryAfter, "60")
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPublicPageHeaders()
    respondText(
        text = EventPublicHtml.tooManyRequestsPage(brandTitle),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.TooManyRequests,
    )
}

private suspend fun ApplicationCall.respondEventMalformed(
    brandTitle: String,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPublicPageHeaders()
    respondText(text = EventPublicHtml.malformedRequestPage(brandTitle), contentType = HTML_CONTENT_TYPE, status = status)
}

private suspend fun ApplicationCall.respondEventServerError(brandTitle: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPublicPageHeaders()
    respondText(
        text = EventPublicHtml.serverErrorPage(brandTitle),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.InternalServerError,
    )
}

/** Same guarantee `SocialPublicRoutes.withPublicErrorHandling` gives its own route family -- see that function's KDoc. Not reused directly: its error page is `SocialPublicHtml`-specific. */
private suspend fun ApplicationCall.withEventPublicErrorHandling(
    brandTitle: String,
    handler: suspend () -> Unit,
) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Security-audit fix: the path ONLY, never the full URI/query string -- GET
        // /veranstaltung/{slug}/zahlung|storno both carry a bearer-equivalent payment/cancel token
        // in the query string, which the full request.uri would otherwise leak into the log on any
        // unhandled exception.
        logger.error(e) { "Unhandled exception in a public event handler (${request.path()})" }
        runCatching { respondEventServerError(brandTitle) }
    }
}
