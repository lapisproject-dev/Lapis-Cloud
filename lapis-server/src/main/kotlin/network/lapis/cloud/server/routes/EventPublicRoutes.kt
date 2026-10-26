package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentDisposition
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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.events.EventCapacityGuard
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationMessage
import network.lapis.cloud.server.events.EventRegistrationMessageNotifier
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.events.QrCodeEncoder
import network.lapis.cloud.server.events.mailPromotion
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.payment.psp.PspCheckoutGateway
import network.lapis.cloud.server.pdf.EventTicketPdfGenerator
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.toOrganizationSettingsDto
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.share.EventShareMeta
import network.lapis.cloud.server.share.ShareConfig
import network.lapis.cloud.server.share.barFor
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventTicketCode
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Hard cap on `POST /veranstaltung/{slug}/anmeldung`'s form body -- generous for name+email+honeypot+message, same DoS-guard reasoning as
 * `SocialPublicRoutes.REPORT_MAX_BODY_BYTES`. V1.9.204: raised from 4096 -- a 500-character message of 3-byte UTF-8 characters is up to
 * 4500 bytes URL-encoded, the name (300 characters) up to 2700 more.
 */
private const val MAX_EVENT_REGISTRATION_BODY_BYTES = 16384L

/** `MAX_EVENT_REGISTRATION_BODY_BYTES`, but for Ktor's OWN form-field buffering cap (default 50 MiB) -- see `registerEventPublicRoutes` KDoc "Content-Length-Deckel". */
private const val EVENT_FORM_FIELD_LIMIT = MAX_EVENT_REGISTRATION_BODY_BYTES

private val logger = KotlinLogging.logger {}

/** `GET /veranstaltung.ics` -- RFC 5545, the standard media type calendar clients (Google/Apple/Outlook) expect for a URL-based calendar subscription. */
private val ICS_CONTENT_TYPE = ContentType("text", "calendar").withParameter("charset", "utf-8")

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
    checkoutGateways: Map<PaymentProvider, PspCheckoutGateway>,
    baseUrl: String,
    mailDispatcher: MailDispatcher,
    // V1.9.128 -- the resolved branding (title + logo + website) replaces the former bare `brandTitle`: the public event page now
    // carries the full Lapis header ([PublicChrome]), unless called with `?embed=1`.
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
    // V1.9.128 -- the EMBED variant (`?embed=1`) may only be framed by these origins, and only while the embed feature is on.
    embedConfig: EmbedConfig,
    pageRateLimiter: FederationInboxRateLimiter,
    attemptRateLimiter: FederationInboxRateLimiter,
    registrationRateLimiter: FederationInboxRateLimiter,
    // Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- see EventTicketRoutes' own KDoc block
    // below for the rationale (soft per-IP page budget vs. a failures-only code-guessing guard).
    ticketPageRateLimiter: FederationInboxRateLimiter,
    ticketCodeFailureLimiter: LoginRateLimiter,
    // Welle V1.4.1c "iCal-Feed für öffentliche Veranstaltungen" -- soft per-IP budget for
    // `GET /veranstaltung.ics`, same `FederationInboxRateLimiter`-shape as [pageRateLimiter] (an
    // every-request, not failures-only, budget -- a calendar client polling its subscription is a
    // normal request, not a "failed attempt").
    icsFeedRateLimiter: FederationInboxRateLimiter,
    // V1.9.204 -- tells the event's creator about a guest registration that carries a message; no-op by default.
    messageNotifier: EventRegistrationMessageNotifier = EventRegistrationMessageNotifier.DISABLED,
    // V1.9.217 -- share bar on the detail page; DISABLED keeps every response byte-identical to V1.9.216. Application.kt wires the real one.
    shareConfig: ShareConfig = ShareConfig.DISABLED,
) {
    val frameAncestors = embedFrameAncestors(embedConfig)

    /** The per-request context -- always the FIRST thing a handler builds, never inside a transaction (see [PublicNavAvailabilityProvider.current]). */
    fun ApplicationCall.pageContext(): EventPageContext =
        eventPageContext(baseUrl = baseUrl, branding = branding, navAvailability = navAvailability, frameAncestors = frameAncestors)

    val submission =
        EventRegistrationSubmission(
            checkoutGateways = checkoutGateways,
            baseUrl = baseUrl,
            mailDispatcher = mailDispatcher,
            messageNotifier = messageNotifier,
        )

    get("/veranstaltung/{slug}") {
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"]
            val view = slug?.let { loadPublicEventView(slug = it, baseUrl = baseUrl) }
            if (view == null) {
                call.respondEventNotFound(ctx)
                return@withEventPublicErrorHandling
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            // The cover image is same-origin (`/veranstaltung/{slug}/bild`), already covered by `img-src 'self'`.
            val shareUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/veranstaltung/${view.slug}", lang = ctx.lang)
            val share =
                if (ctx.isEmbed) {
                    null
                } else {
                    shareConfig.barFor(url = shareUrl, rawTitle = view.title, rawText = EventShareMeta.shareText(view))
                }
            // Open Graph + JSON-LD only where the base URL is shareable (https or loopback); DISABLED (default) emits neither.
            val ogMeta =
                if (ctx.isEmbed || !shareConfig.baseUrlShareable) {
                    null
                } else {
                    val image = EventShareMeta.ogImage(view = view, baseUrl = baseUrl, branding = ctx.branding)
                    EventPublicHtml.EventOgMeta(
                        description = EventShareMeta.ogDescription(view),
                        image = image,
                        jsonLd =
                            EventShareMeta.jsonLd(
                                view = view,
                                shareUrl = shareUrl,
                                image = EventShareMeta.jsonLdImage(view = view, baseUrl = baseUrl, branding = ctx.branding),
                                branding = ctx.branding,
                                baseUrl = baseUrl,
                                orgZone = OrganizationTimeZone.current(),
                            ),
                    )
                }
            call.applyEventPageHeaders(ctx = ctx, scriptSrcSelf = share?.needsScript == true)
            call.respondText(
                text = EventPublicHtml.eventPage(ctx = ctx, view = view, share = share, ogMeta = ogMeta),
                contentType = HTML_CONTENT_TYPE,
            )
        }
    }

    // Welle V1.4.1c "iCal-Feed für öffentliche Veranstaltungen" -- registered as a literal route
    // ABOVE `/veranstaltung/{slug}` in this function's own source order has no bearing here (Ktor
    // routing matches by structure, not declaration order, and "veranstaltung.ics" can never match
    // the `{slug}` segment of "/veranstaltung/{slug}" anyway -- different path shapes entirely).
    // Kein Secret/Token -- see EventIcsFeed KDoc "Sichtbarkeitsregel".
    get("/veranstaltung.ics") {
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!icsFeedRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val now = DbClock.nowLocalDateTime()
            // Follow-up wave "Wiederkehrende Veranstaltungen: iCal-Feed" -- `loadSeriesRenderData`
            // needs its own DB reads (EventStore/EventSeriesTable), so it runs in the SAME
            // transaction as `loadUpcomingPublicPublished` (see EventIcsFeed KDoc "Nicht gestreamt"
            // -- `render` itself stays entirely outside any transaction).
            val (rows, seriesData) =
                transaction {
                    val loadedRows = EventIcsFeed.loadUpcomingPublicPublished(wallNow = OrganizationTimeZone.wallNowOf(now))
                    val loadedSeriesData =
                        EventIcsFeed.loadSeriesRenderData(loadedRows.mapNotNull { it[EventTable.seriesId] })
                    loadedRows to loadedSeriesData
                }
            if (rows.size >= EventIcsFeed.MAX_EVENTS) {
                logger.warn { "Public iCal feed truncated at ${EventIcsFeed.MAX_EVENTS} events -- consider raising the cap." }
            }
            val body = EventIcsFeed.render(rows = rows, baseUrl = baseUrl, brandTitle = branding.title, seriesData = seriesData)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline
                    .withParameter(ContentDisposition.Parameters.FileName, "veranstaltungen.ics")
                    .toString(),
            )
            call.respondPublicCacheable(body = body, contentType = ICS_CONTENT_TYPE, cacheControl = "public, max-age=900")
        }
    }

    post("/veranstaltung/{slug}/anmeldung") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            val slug = call.parameters["slug"]
            if (slug == null) {
                call.respondEventNotFound(ctx)
                return@withEventPublicErrorHandling
            }
            // 1. Generous flood/DoS gate -- BEFORE any body read.
            if (!attemptRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            // 2. Content-Length pre-check, before any body byte is read.
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength == null || declaredContentLength > MAX_EVENT_REGISTRATION_BODY_BYTES) {
                call.respondEventMalformed(ctx = ctx, status = HttpStatusCode.PayloadTooLarge)
                return@withEventPublicErrorHandling
            }
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondEventMalformed(ctx = ctx, status = HttpStatusCode.BadRequest)
                return@withEventPublicErrorHandling
            }
            call.formFieldLimit = EVENT_FORM_FIELD_LIMIT
            val params = call.receiveParameters()
            // 3. Honeypot -- silent no-op, identical response shape as a real submission would use
            // for the "cancelled" outcome, no DB write, no rate-limiter consumption beyond step 1.
            val honeypotFilled = !params["kommentar"].isNullOrBlank()
            if (honeypotFilled) {
                call.respondEventRedirect(ctx = ctx, location = ctx.href("/veranstaltung/$slug/abgebrochen"))
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
                call.respondEventInvalidInput(ctx = ctx, slug = slug)
                return@withEventPublicErrorHandling
            }
            // V1.9.204 -- the optional message; checked HERE, before the strict registration budget is spent (no echo of the text).
            val guestMessage =
                when (val outcome = EventRegistrationMessage.normalize(params["nachricht"])) {
                    is EventRegistrationMessage.Outcome.Ok -> outcome.value
                    EventRegistrationMessage.Outcome.TooLong -> {
                        call.respondEventInvalidInput(ctx = ctx, slug = slug)
                        return@withEventPublicErrorHandling
                    }
                }
            val eventId = transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
            if (eventId == null) {
                call.respondEventNotFound(ctx)
                return@withEventPublicErrorHandling
            }
            val visible =
                transaction {
                    val row = EventStore.getEventOrNull(eventId)
                    row != null && row[EventTable.visibility] == EventVisibility.PUBLIC
                }
            if (!visible) {
                // DRAFT/CANCELLED/MEMBERS_ONLY/unknown all render the SAME 404 -- no existence oracle.
                call.respondEventNotFound(ctx)
                return@withEventPublicErrorHandling
            }
            // 4. Strict per-real-attempt budget -- only now, immediately before the DB/Stripe work.
            if (!registrationRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val result =
                submission.submit(
                    eventId = eventId,
                    participant = EventParticipant.Guest(name = guestName, normalizedEmail = normalizedEmail),
                    message = guestMessage,
                )
            when (result) {
                is EventRegistrationResult.Confirmed ->
                    call.respondEventRedirect(
                        ctx = ctx,
                        location = ctx.href("/veranstaltung/$slug/danke?r=${result.registrationId}"),
                    )
                is EventRegistrationResult.PaymentRequired ->
                    call.respondEventPaymentRequired(
                        ctx = ctx,
                        slug = slug,
                        redirectUrl = result.redirectUrl,
                    )
                is EventRegistrationResult.Waitlisted ->
                    call.respondEventRedirect(
                        ctx = ctx,
                        location = ctx.href("/veranstaltung/$slug/warteliste"),
                    )
                EventRegistrationResult.AlreadyRegistered ->
                    call.respondEventRedirect(
                        ctx = ctx,
                        location = ctx.href("/veranstaltung/$slug/danke"),
                    )
                EventRegistrationResult.EventNotAvailable -> {
                    call.respondEventNotFound(ctx)
                }
                EventRegistrationResult.WaitlistFull ->
                    call.respondEventRedirect(
                        ctx = ctx,
                        location = ctx.href("/veranstaltung/$slug/abgebrochen"),
                    )
                EventRegistrationResult.GatewayUnavailable ->
                    call.respondEventRedirect(
                        ctx = ctx,
                        location = ctx.href("/veranstaltung/$slug/abgebrochen"),
                    )
                is EventRegistrationResult.PaymentFailed ->
                    call.respondEventRedirect(
                        ctx = ctx,
                        location = ctx.href("/veranstaltung/$slug/abgebrochen"),
                    )
            }
        }
    }

    get("/veranstaltung/{slug}/danke") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.respondEventReturnPage(ctx = ctx, rateLimiter = pageRateLimiter) {
            EventPublicHtml.thanksPage(ctx = ctx, slug = call.parameters["slug"].orEmpty(), registrationId = call.parameters["r"])
        }
    }

    get("/veranstaltung/{slug}/warteliste") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.respondEventReturnPage(ctx = ctx, rateLimiter = pageRateLimiter) {
            EventPublicHtml.waitlistPage(ctx = ctx, slug = call.parameters["slug"].orEmpty())
        }
    }

    get("/veranstaltung/{slug}/abgebrochen") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.respondEventReturnPage(ctx = ctx, rateLimiter = pageRateLimiter) {
            EventPublicHtml.cancelledPage(ctx = ctx, slug = call.parameters["slug"].orEmpty())
        }
    }

    get("/veranstaltung/{slug}/storno") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val token = call.parameters["token"].orEmpty()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyEventPageHeaders(ctx = ctx)
            // GET never mutates (a mail-client link-prefetch must never storno a registration on its
            // own) -- always the confirmation form, even for an unknown/expired token; the actual
            // lookup happens only on POST.
            call.respondText(
                text = EventPublicHtml.cancelConfirmPage(ctx = ctx, slug = slug, token = token),
                contentType = HTML_CONTENT_TYPE,
            )
        }
    }

    post("/veranstaltung/{slug}/storno") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!attemptRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength == null || declaredContentLength > MAX_EVENT_REGISTRATION_BODY_BYTES) {
                call.respondEventMalformed(ctx = ctx, status = HttpStatusCode.PayloadTooLarge)
                return@withEventPublicErrorHandling
            }
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondEventMalformed(ctx = ctx, status = HttpStatusCode.BadRequest)
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
            call.applyEventPageHeaders(ctx = ctx)
            call.respondText(
                text = EventPublicHtml.cancelledSuccessPage(ctx = ctx, slug = call.parameters["slug"].orEmpty()),
                contentType = HTML_CONTENT_TYPE,
            )
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
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val token = call.parameters["token"].orEmpty()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyEventPageHeaders(ctx = ctx)
            call.respondText(
                text = EventPublicHtml.paymentConfirmPage(ctx = ctx, slug = slug, token = token),
                contentType = HTML_CONTENT_TYPE,
            )
        }
    }

    post("/veranstaltung/{slug}/zahlung") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!attemptRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredContentLength == null || declaredContentLength > MAX_EVENT_REGISTRATION_BODY_BYTES) {
                call.respondEventMalformed(ctx = ctx, status = HttpStatusCode.PayloadTooLarge)
                return@withEventPublicErrorHandling
            }
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondEventMalformed(ctx = ctx, status = HttpStatusCode.BadRequest)
                return@withEventPublicErrorHandling
            }
            call.formFieldLimit = EVENT_FORM_FIELD_LIMIT
            val slug = call.parameters["slug"].orEmpty()
            val params = call.receiveParameters()
            val token = params["token"].orEmpty()
            if (token.isBlank()) {
                call.respondEventRedirect(ctx = ctx, location = ctx.href("/veranstaltung/$slug/abgebrochen"))
                return@withEventPublicErrorHandling
            }
            val eventId = transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
            if (eventId == null) {
                call.respondEventNotFound(ctx)
                return@withEventPublicErrorHandling
            }
            val tokenHash = sha256Hex(token.toByteArray(Charsets.US_ASCII))
            val registration = transaction { EventStore.findByCancelTokenHash(eventId = eventId, cancelTokenSha256 = tokenHash) }
            // Same neutral "cancelled" outcome for every dead-end (unknown token, wrong status,
            // hash mismatch) -- no oracle for an anonymous caller, same posture the rest of this
            // file's public surface establishes.
            if (registration == null || registration[EventRegistrationTable.status] != EventRegistrationStatus.PENDING_PAYMENT) {
                call.respondEventRedirect(ctx = ctx, location = ctx.href("/veranstaltung/$slug/abgebrochen"))
                return@withEventPublicErrorHandling
            }
            val storedHash = registration[EventRegistrationTable.cancelTokenSha256]
            if (storedHash == null ||
                !MessageDigest.isEqual(tokenHash.toByteArray(Charsets.US_ASCII), storedHash.toByteArray(Charsets.US_ASCII))
            ) {
                call.respondEventRedirect(ctx = ctx, location = ctx.href("/veranstaltung/$slug/abgebrochen"))
                return@withEventPublicErrorHandling
            }
            // Strict per-real-attempt budget -- only now, immediately before the Stripe call, same
            // "cheap gates first, expensive/network-bound work last" ordering `/anmeldung` establishes.
            if (!registrationRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val registrationId = registration[EventRegistrationTable.id]
            val result = submission.resumeCheckout(eventId = eventId, registrationId = registrationId)
            when (result) {
                is EventRegistrationResult.PaymentRequired ->
                    call.respondEventPaymentRequired(
                        ctx = ctx,
                        slug = slug,
                        redirectUrl = result.redirectUrl,
                    )
                else -> call.respondEventRedirect(ctx = ctx, location = ctx.href("/veranstaltung/$slug/abgebrochen"))
            }
        }
    }

    // ── Ticket routes (Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes") ─────────────────────
    //
    // Unlike every route above, a ticket code is a BEARER CREDENTIAL scoped to one specific
    // registration (not a name+email the caller already knows) -- so the code-lookup failure path
    // gets its OWN, stricter guard ([ticketCodeFailureLimiter], failures-only, `LoginRateLimiter`-
    // shaped) layered UNDER the generic per-IP page budget ([ticketPageRateLimiter], every-request,
    // `FederationInboxRateLimiter`-shaped): a ticket holder who reloads their own valid ticket
    // twenty times must never be punished the way a code-guessing attacker is. See
    // `resolveTicket`'s own KDoc for the full lookup order.
    //
    // **No `<script>`, no inline styles for the QR fill** -- `EventTicketSvg` renders fill color as
    // an SVG attribute for exactly this reason (see that object's own KDoc), so
    // `applyEventTicketPageHeaders`' `img-src 'self'` addition never needs an `unsafe-inline` CSP
    // relaxation alongside it.

    get("/veranstaltung/{slug}/ticket") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!ticketPageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTooManyRequests(ctx)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val remoteKey = "ip:${rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)}"
            when (
                val resolution =
                    resolveTicket(
                        slug = slug,
                        rawCode = call.parameters["code"],
                        remoteKey = remoteKey,
                        failureLimiter = ticketCodeFailureLimiter,
                        baseUrl = baseUrl,
                    )
            ) {
                TicketResolution.TooManyRequests -> call.respondEventTooManyRequests(ctx)
                TicketResolution.NotFound -> call.respondEventNotFound(ctx)
                is TicketResolution.NotConfirmedYet -> {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.applyEventPageHeaders(ctx = ctx)
                    call.respondText(
                        text = EventPublicHtml.ticketNotConfirmedPage(ctx = ctx, slug = slug, status = resolution.status),
                        contentType = HTML_CONTENT_TYPE,
                    )
                }
                is TicketResolution.Ticket -> {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.applyEventPageHeaders(ctx = ctx)
                    call.respondText(
                        text = EventPublicHtml.ticketPage(ctx = ctx, view = resolution.toView()),
                        contentType = HTML_CONTENT_TYPE,
                    )
                }
            }
        }
    }

    get("/veranstaltung/{slug}/ticket.svg") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!ticketPageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.TooManyRequests)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val remoteKey = "ip:${rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)}"
            val resolution =
                resolveTicket(
                    slug = slug,
                    rawCode = call.parameters["code"],
                    remoteKey = remoteKey,
                    failureLimiter = ticketCodeFailureLimiter,
                    baseUrl = baseUrl,
                )
            val ticket = resolution as? TicketResolution.Ticket
            if (resolution == TicketResolution.TooManyRequests) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.TooManyRequests)
                return@withEventPublicErrorHandling
            }
            if (ticket == null) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.NotFound)
                return@withEventPublicErrorHandling
            }
            val content = EventTicketPolicy.ticketUrl(baseUrl = baseUrl, slug = slug, rawCode = ticket.canonicalCode)
            val svg = EventTicketSvg.render(matrix = QrCodeEncoder.encode(content), sizePx = 320)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("Content-Security-Policy", "default-src 'none'")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondText(text = svg, contentType = ContentType.Image.SVG)
        }
    }

    get("/veranstaltung/{slug}/ticket.pdf") {
        call.markEventNoIndex()
        val ctx = call.pageContext()
        call.withEventPublicErrorHandling(ctx = ctx) {
            if (!ticketPageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.TooManyRequests)
                return@withEventPublicErrorHandling
            }
            val slug = call.parameters["slug"].orEmpty()
            val remoteKey = "ip:${rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)}"
            val resolution =
                resolveTicket(
                    slug = slug,
                    rawCode = call.parameters["code"],
                    remoteKey = remoteKey,
                    failureLimiter = ticketCodeFailureLimiter,
                    baseUrl = baseUrl,
                )
            if (resolution == TicketResolution.TooManyRequests) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.TooManyRequests)
                return@withEventPublicErrorHandling
            }
            val ticket = resolution as? TicketResolution.Ticket
            if (ticket == null) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.NotFound)
                return@withEventPublicErrorHandling
            }
            val content = EventTicketPolicy.ticketUrl(baseUrl = baseUrl, slug = slug, rawCode = ticket.canonicalCode)
            val matrix = QrCodeEncoder.encode(content)
            val organization =
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                        ?.toOrganizationSettingsDto()
                }
            if (organization == null) {
                call.respondEventTicketBinaryStatus(HttpStatusCode.InternalServerError)
                return@withEventPublicErrorHandling
            }
            val pdfBytes =
                EventTicketPdfGenerator.generate(
                    eventTitle = ticket.eventTitle,
                    startsAt = ticket.startsAt,
                    endsAt = ticket.endsAt,
                    locationText = ticket.locationText,
                    onlineUrl = ticket.onlineUrl ?: ticket.onlineJoinUrl,
                    displayCode = EventTicketCode.formatForDisplay(ticket.canonicalCode),
                    qr = matrix,
                    organization = organization,
                )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "Ticket-$slug.pdf")
                    .toString(),
            )
            call.respondBytes(bytes = pdfBytes, contentType = ContentType.Application.Pdf)
        }
    }
}

/** Resolved-vs-rejected outcome of a ticket-code lookup -- shared by all three ticket routes above. */
private sealed interface TicketResolution {
    data class Ticket(
        val eventTitle: String,
        val startsAt: LocalDateTime,
        val endsAt: LocalDateTime,
        val locationText: String?,
        val onlineUrl: String?,
        val slug: String,
        val canonicalCode: String,
        /** V1.9.113 -- link to the online participation page for a Lapis online event, else null. */
        val onlineJoinUrl: String? = null,
    ) : TicketResolution {
        fun toView(): EventPublicHtml.TicketView =
            EventPublicHtml.TicketView(
                title = eventTitle,
                startsAt = startsAt,
                endsAt = endsAt,
                locationText = locationText,
                onlineUrl = onlineUrl,
                slug = slug,
                canonicalCode = canonicalCode,
                onlineJoinUrl = onlineJoinUrl,
            )
    }

    data class NotConfirmedYet(
        val status: EventRegistrationStatus,
    ) : TicketResolution

    data object NotFound : TicketResolution

    data object TooManyRequests : TicketResolution
}

/**
 * The single lookup path all three ticket routes share -- **verbindliche Reihenfolge**:
 * 1. [failureLimiter.checkAllowed] -- checked BEFORE any canonicalization/DB work, so an
 *    already-exhausted attacker's further attempts cost this server nothing beyond a map lookup.
 * 2. [EventTicketCode.extractAndCanonicalize] -- failure records a [failureLimiter] hit and returns
 *    [TicketResolution.NotFound], WITHOUT any database access (same DoS-defense-first posture
 *    `EventCheckIn.byCode` establishes server-side for the authenticated door-scan path).
 * 3. Hash lookup (global, via [EventStore.findByTicketCodeHash]) + [MessageDigest.isEqual] defense-
 *    in-depth -- a miss records a [failureLimiter] hit and returns [TicketResolution.NotFound].
 * 4. The looked-up registration's event must resolve AND its slug must equal [slug] -- a mismatch
 *    (a code from a DIFFERENT event's ticket) records a [failureLimiter] hit and returns
 *    [TicketResolution.NotFound] -- no oracle over which event a code actually belongs to.
 * 5. Non-CONFIRMED status -> [TicketResolution.NotConfirmedYet] (NO failure recorded -- the code
 *    itself was genuine, the caller is simply not there yet; see class KDoc for why this differs
 *    from the neutral 404 above).
 */
private fun resolveTicket(
    slug: String,
    rawCode: String?,
    remoteKey: String,
    failureLimiter: LoginRateLimiter,
    baseUrl: String,
): TicketResolution {
    if (!failureLimiter.checkAllowed(remoteKey)) return TicketResolution.TooManyRequests
    val canonical = EventTicketCode.extractAndCanonicalize(rawCode)
    if (canonical == null) {
        failureLimiter.recordFailure(remoteKey)
        return TicketResolution.NotFound
    }
    val hash = sha256Hex(canonical.toByteArray(Charsets.US_ASCII))
    return transaction {
        val registration = EventStore.findByTicketCodeHash(hash)
        val storedHash = registration?.get(EventRegistrationTable.ticketCodeSha256)
        if (registration == null ||
            storedHash == null ||
            !MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), storedHash.toByteArray(Charsets.US_ASCII))
        ) {
            failureLimiter.recordFailure(remoteKey)
            return@transaction TicketResolution.NotFound
        }
        val event = EventStore.getEventOrNull(registration[EventRegistrationTable.eventId])
        if (event == null || event[EventTable.slug] != slug) {
            failureLimiter.recordFailure(remoteKey)
            return@transaction TicketResolution.NotFound
        }
        val status = registration[EventRegistrationTable.status]
        if (status != EventRegistrationStatus.CONFIRMED) {
            return@transaction TicketResolution.NotConfirmedYet(status)
        }
        TicketResolution.Ticket(
            eventTitle = event[EventTable.title],
            startsAt = event[EventTable.startsAt],
            endsAt = event[EventTable.endsAt],
            locationText = event[EventTable.locationText],
            onlineUrl = event[EventTable.onlineUrl],
            slug = slug,
            canonicalCode = canonical,
            onlineJoinUrl = onlineJoinUrlFor(row = event, baseUrl = baseUrl),
        )
    }
}

/** Minimal, no-body status response for the two binary ticket endpoints ([ticket.svg]/[ticket.pdf]) -- neither has an HTML error page to render. */
private suspend fun ApplicationCall.respondEventTicketBinaryStatus(status: HttpStatusCode) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status)
}

/**
 * Shared handler body for the three always-200 return pages (`danke`/`warteliste`/`abgebrochen`) --
 * same "never an error status at the exact moment the visitor just registered/cancelled" reasoning
 * `EmbedDonationRoutes.respondDonationReturnPage` KDoc documents. Wrapped in its own `try`/`catch`
 * (not just the caller's [withEventPublicErrorHandling]) so a late failure here still gets this
 * file's own security headers rather than Ktor's bare default.
 *
 * Plain `try`/`catch`, not `runCatching` (Security-audit INFO fix): `runCatching` catches
 * [CancellationException] too, and this used to `.onFailure` into `respondEventServerError` on a
 * cancelled call as well -- e.g. the client aborting the connection while this handler is still
 * running. That silently swallows the cancellation (violating structured concurrency -- the
 * coroutine hierarchy never learns this call was cancelled) AND then attempts a second `respond*`
 * on an already-dead call, which itself fails and only obscures the original cancellation in the
 * logs. Same fix, same reasoning [withEventPublicErrorHandling] below already applies.
 */
private suspend fun ApplicationCall.respondEventReturnPage(
    ctx: EventPageContext,
    rateLimiter: FederationInboxRateLimiter,
    render: () -> String,
) {
    try {
        response.header(HttpHeaders.CacheControl, "no-store")
        if (!rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = request.origin.remoteHost))) {
            applyEventPageHeaders(ctx = ctx)
            respondText(text = render(), contentType = HTML_CONTENT_TYPE, status = HttpStatusCode.TooManyRequests)
            return
        }
        applyEventPageHeaders(ctx = ctx)
        respondText(text = render(), contentType = HTML_CONTENT_TYPE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { respondEventServerError(ctx) }
    }
}

private fun loadPublicEventView(
    slug: String,
    baseUrl: String,
): EventPublicHtml.View? =
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
                wallNow = OrganizationTimeZone.wallNowOf(now),
            )
        val wallNow = OrganizationTimeZone.wallNowOf(now)
        EventPublicHtml.View(
            title = row[EventTable.title],
            slug = row[EventTable.slug],
            description = row[EventTable.description],
            locationText = row[EventTable.locationText],
            // V1.9.82 (F1): the online link appears on the public page ONLY after an explicit opt-in, only as https, and only
            // while the event has not ended (same rule as the feeds: a past event's meeting link is never published).
            onlineUrl =
                row[EventTable.onlineUrl]?.takeIf {
                    row[EventTable.onlineUrlPublic] && EventText.isHttpsUrl(it) && row[EventTable.endsAt] > wallNow
                },
            startsAt = row[EventTable.startsAt],
            endsAt = row[EventTable.endsAt],
            feeLabel = feeLabel,
            feeAmount = fee,
            feeCurrency = row[EventTable.feeCurrency],
            registrationClosesAt = row[EventTable.registrationClosesAt],
            full = full,
            registrationOpen = registrationOpen,
            hasEnded = row[EventTable.endsAt] <= wallNow,
            onlineJoinUrl = if (row[EventTable.endsAt] > wallNow) onlineJoinUrlFor(row = row, baseUrl = baseUrl) else null,
            coverImageAlt = row[EventTable.coverImageAlt],
            coverImageUrl =
                EventCoverPolicy.coverImageUrl(
                    baseUrl = baseUrl,
                    slug = row[EventTable.slug],
                    coverImageId = row[EventTable.coverImageId],
                ),
        )
    }

/**
 * The security headers of every HTML response of the `/veranstaltung/...` family (V1.9.128). Since V1.9.130 the header core is shared
 * with the article page via [applyPageModeHeaders]; this is a plain delegation, the emitted headers are unchanged.
 */
internal fun ApplicationCall.applyEventPageHeaders(
    ctx: EventPageContext,
    scriptSrcSelf: Boolean = false,
) {
    applyPageModeHeaders(isEmbed = ctx.isEmbed, frameAncestors = ctx.frameAncestors, scriptSrcSelf = scriptSrcSelf)
}

private suspend fun ApplicationCall.respondEventRedirect(
    ctx: EventPageContext,
    location: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode(303, "See Other"))
}

/**
 * STANDARD: `303` to the PSP checkout, as before. EMBED: the checkout cannot be framed, so a `200` page offers a link that opens it in the
 * top-level window (see [EventPublicHtml.paymentHandoffPage]).
 */
private suspend fun ApplicationCall.respondEventPaymentRequired(
    ctx: EventPageContext,
    slug: String,
    redirectUrl: String,
) {
    if (!ctx.isEmbed) {
        respondEventRedirect(ctx = ctx, location = redirectUrl)
        return
    }
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    respondText(
        text = EventPublicHtml.paymentHandoffPage(ctx = ctx, slug = slug, redirectUrl = redirectUrl),
        contentType = HTML_CONTENT_TYPE,
    )
}

private suspend fun ApplicationCall.respondEventNotFound(ctx: EventPageContext) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    respondText(text = EventPublicHtml.notFoundPage(ctx), contentType = HTML_CONTENT_TYPE, status = HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.respondEventTooManyRequests(ctx: EventPageContext) {
    response.header(HttpHeaders.RetryAfter, "60")
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    respondText(
        text = EventPublicHtml.tooManyRequestsPage(ctx),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.TooManyRequests,
    )
}

private suspend fun ApplicationCall.respondEventMalformed(
    ctx: EventPageContext,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    respondText(text = EventPublicHtml.malformedRequestPage(ctx), contentType = HTML_CONTENT_TYPE, status = status)
}

/** Validation failure of the registration form: still `400`, but with a human message and a link back. Nothing the visitor typed is reflected. */
private suspend fun ApplicationCall.respondEventInvalidInput(
    ctx: EventPageContext,
    slug: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    respondText(
        text = EventPublicHtml.invalidInputPage(ctx = ctx, slug = slug),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.BadRequest,
    )
}

private suspend fun ApplicationCall.respondEventServerError(ctx: EventPageContext) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyEventPageHeaders(ctx = ctx)
    respondText(
        text = EventPublicHtml.serverErrorPage(ctx),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.InternalServerError,
    )
}

/** Same guarantee `SocialPublicRoutes.withPublicErrorHandling` gives its own route family -- see that function's KDoc. Not reused directly: its error page is `SocialPublicHtml`-specific. */
internal suspend fun ApplicationCall.withEventPublicErrorHandling(
    ctx: EventPageContext,
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
        runCatching { respondEventServerError(ctx) }
    }
}

/**
 * V1.9.113 -- the link to the SPA page that handles the online participation of a Lapis online event
 * (`<base>/#/event-join/<slug>`), or `null` for an event without one. It is the PAGE (public, the slug is public anyway), never a
 * room id, a token or an external meeting link -- who may enter and when is decided there and by the server.
 */
private fun onlineJoinUrlFor(
    row: ResultRow,
    baseUrl: String,
): String? =
    // Only LAPIS_CONFERENCE: the public guest page answers FORBIDDEN for every other kind (an encounter link would be a dead end).
    if (row[EventTable.onlineKind] == EventOnlineKind.LAPIS_CONFERENCE && row[EventTable.status] == EventStatus.PUBLISHED) {
        "${baseUrl.trimEnd('/')}/#/event-join/${row[EventTable.slug]}"
    } else {
        null
    }

/**
 * V1.9.140 -- token and follow-up pages (registration POST, thanks, waitlist, cancelled, storno, zahlung, ticket) are never to be indexed.
 * Called as the FIRST statement of each handler, before the rate limit and the error handling, so that 404/429/500/303 carry the header
 * too. The detail page (200), the `.ics` feed and the cover image never call it.
 */
private fun ApplicationCall.markEventNoIndex() {
    response.header("X-Robots-Tag", "noindex, nofollow")
}
