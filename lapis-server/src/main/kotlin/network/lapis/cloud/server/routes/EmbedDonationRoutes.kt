package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.EmbedDonationLimits
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.embed.respondEmbedPreflight
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.AnonymousDonationCheckout
import network.lapis.cloud.server.payment.psp.AnonymousDonationResult
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import java.math.BigDecimal

/** Hard cap on a `POST /api/embed/v1/donation/checkout` body -- generous for `{"amount":"...", "kommentar":"..."}`, same DoS-guard reasoning as `PspWebhookRoutes`' own `MAX_WEBHOOK_BODY_BYTES`. */
private const val MAX_EMBED_DONATION_BODY_BYTES = 2048

private val EMBED_DONATION_JSON = Json { ignoreUnknownKeys = true }
private val EMBED_DONATION_JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

@Serializable
internal data class EmbedDonationCheckoutRequest(
    val amount: String,
    /** Honeypot field -- a real donor never fills this in; a bot filling every field does. See `AnonymousDonationCheckout` KDoc. */
    val kommentar: String? = null,
)

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- registers the anonymous
 * donation checkout endpoint (`POST /api/embed/v1/donation/checkout`) and the two Stripe return
 * pages (`GET /embed/v1/spende/danke`/`/abgebrochen`). Called from [registerEmbedRoutes] INSIDE the
 * `if (!config.enabled) return` gate -- same registration discipline every other route in that
 * function follows.
 *
 * **The checkout endpoint is the single most sensitive route this whole embed surface exposes**: it
 * is the ONLY unauthenticated endpoint in this codebase that, on a stranger's say-so, triggers a
 * real outbound Stripe API call and two DB `INSERT`s. Handler ordering below is deliberate and a
 * review blocker to reorder: CORS (incl. the `NoOriginHeader` -> 403 deviation from
 * `/api/embed/v1/session`, this endpoint moves money and has no legitimate same-origin caller) ->
 * `Content-Length` pre-check -> bounded body read -> rate limit -> JSON decode -> delegate to
 * [AnonymousDonationCheckout.create].
 *
 * **Two rate limiters (Fix, Review MAJOR #4, Welle V1.4.1b)**: [donationCheckoutAttemptRateLimiter]
 * is checked here, BEFORE the JSON decode -- a generous, cheap-flood/DoS backstop, same role the
 * single limiter used to play alone. [donationCheckoutRateLimiter] is the strict "3 real
 * attempts/hour" budget; it is no longer checked here at all -- [AnonymousDonationCheckout.create]
 * itself consults it, immediately before the actual Stripe call, so a pre-Stripe rejection
 * (honeypot, gateway unavailable, amount out of range, §25 PROHIBITED) never eats into that tight
 * budget (see that class's own KDoc "Zwei Limiter"). [AnonymousDonationResult.RateLimited] is the
 * response-mapping counterpart of that strict budget being exhausted.
 */
internal fun Route.registerEmbedDonationRoutes(
    config: EmbedConfig,
    pspConfigState: PspConfigState,
    checkoutClient: StripeCheckoutClient?,
    donationCheckoutRateLimiter: FederationInboxRateLimiter,
    donationCheckoutAttemptRateLimiter: FederationInboxRateLimiter,
    donationPageRateLimiter: FederationInboxRateLimiter,
    baseUrl: String,
    brandTitle: String,
) {
    val checkout =
        AnonymousDonationCheckout(
            pspConfigState = pspConfigState,
            checkoutClient = checkoutClient,
            baseUrl = baseUrl,
            checkoutRateLimiter = donationCheckoutRateLimiter,
        )

    post("/api/embed/v1/donation/checkout") {
        // 1. CORS first -- NoOriginHeader is ALSO 403 here (deviation from /api/embed/v1/session,
        // documented above): this endpoint has no legitimate same-origin caller.
        val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
        val canonicalOrigin =
            when (cors) {
                is EmbedCorsResult.Allowed -> cors.canonicalOrigin
                EmbedCorsResult.Rejected, EmbedCorsResult.NoOriginHeader -> {
                    call.respondEmbedForbiddenOrigin()
                    return@post
                }
            }

        // 2. Content-Length pre-check, BEFORE any body read.
        val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredContentLength != null && declaredContentLength > MAX_EMBED_DONATION_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }

        // 3. Bounded streaming read.
        val bodyBytes = readCappedBody(call = call, maxBytes = MAX_EMBED_DONATION_BODY_BYTES)
        if (bodyBytes == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }

        // 4. Rate-limit -- AFTER the body is bounded-read but BEFORE any Stripe call/DB write. This
        // is the GENEROUS admission gate (see class KDoc "Two rate limiters"), not the strict
        // per-real-attempt budget -- that one is consulted inside AnonymousDonationCheckout.create,
        // immediately before the Stripe call.
        val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        if (!donationCheckoutAttemptRateLimiter.checkAndRecord(rateLimitKey)) {
            val retryAfterSeconds = donationCheckoutAttemptRateLimiter.retryAfterSeconds(rateLimitKey)
            call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
            call.respondEmbedDonationJson(
                status = HttpStatusCode.TooManyRequests,
                body = """{"error":"RATE_LIMITED","retryAfterSeconds":$retryAfterSeconds}""",
            )
            return@post
        }

        // 5. JSON decode -- amount travels as a STRING, never a JSON number (house rule against
        // Double in the money path). Parse failure (malformed JSON OR non-numeric amount) -> 400.
        val request =
            runCatching {
                EMBED_DONATION_JSON.decodeFromString(EmbedDonationCheckoutRequest.serializer(), bodyBytes.toString(Charsets.UTF_8))
            }.getOrNull()
        val amount = request?.amount?.let { raw -> runCatching { BigDecimal(raw) }.getOrNull() }
        if (request == null || amount == null) {
            call.respondEmbedDonationJson(status = HttpStatusCode.BadRequest, body = """{"error":"BAD_REQUEST"}""")
            return@post
        }

        // 6. Delegate to the fachlogik.
        val result =
            checkout.create(
                amountEur = amount,
                honeypotValue = request.kommentar,
                canonicalOrigin = canonicalOrigin,
                rateLimitKey = rateLimitKey,
            )

        // 7. Response mapping -- see AnonymousDonationCheckout KDoc for what each branch means.
        when (result) {
            is AnonymousDonationResult.Success ->
                call.respondEmbedDonationJson(status = HttpStatusCode.OK, body = """{"redirectUrl":${jsonString(result.redirectUrl)}}""")
            is AnonymousDonationResult.HoneypotTripped ->
                call.respondEmbedDonationJson(
                    status = HttpStatusCode.OK,
                    body = """{"redirectUrl":${jsonString(result.redirectUrl)}}""",
                )
            AnonymousDonationResult.AmountOutOfRange -> {
                val effectiveMax =
                    (pspConfigState as? PspConfigState.Configured)
                        ?.config
                        ?.maxCheckoutAmountEur
                        ?.let { EmbedDonationLimits.effectiveMaxAmountEur(it) }
                        ?: EmbedDonationLimits.MAX_AMOUNT_EUR
                call.respondEmbedDonationJson(
                    status = HttpStatusCode.BadRequest,
                    body =
                        """{"error":"AMOUNT_OUT_OF_RANGE","minAmount":"${EmbedDonationLimits.MIN_AMOUNT_EUR}","maxAmount":"$effectiveMax"}""",
                )
            }
            // Never nennen, WELCHE Bedingung fehlt -- siehe PspWebhookRoutes-Präzedenz.
            AnonymousDonationResult.GatewayUnavailable ->
                call.respondEmbedDonationJson(status = HttpStatusCode.ServiceUnavailable, body = """{"error":"UNAVAILABLE"}""")
            is AnonymousDonationResult.ProhibitedByLaw ->
                // result.reason is logged inside AnonymousDonationCheckout already -- never delivered here.
                call.respondEmbedDonationJson(status = HttpStatusCode.Conflict, body = """{"error":"NOT_ACCEPTED"}""")
            is AnonymousDonationResult.StripeFailed ->
                // result.message (Stripe's own text) stays server-side only -- never delivered here.
                call.respondEmbedDonationJson(status = HttpStatusCode.BadGateway, body = """{"error":"GATEWAY_ERROR"}""")
            is AnonymousDonationResult.RateLimited -> {
                // Same response shape as step 4's early-gate 429 -- a client cannot and need not
                // distinguish which of the two limiters (see class KDoc) rejected the request.
                call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                call.respondEmbedDonationJson(
                    status = HttpStatusCode.TooManyRequests,
                    body = """{"error":"RATE_LIMITED","retryAfterSeconds":${result.retryAfterSeconds}}""",
                )
            }
        }
    }

    // Ein eigener, weicherer Preflight-Pfad -- verbrennt bewusst NICHT das 3/Stunde-Checkout-Budget
    // (ein Browser schickt vor dem POST immer ein OPTIONS; liefe das über denselben Limiter, hätte
    // ein Spender nach einem einzigen Versuch zwei Drittel seines Budgets verbraucht).
    options("/api/embed/v1/donation/checkout") {
        val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        if (!donationPageRateLimiter.checkAndRecord(rateLimitKey)) {
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

    get("/embed/v1/spende/danke") {
        respondDonationReturnPage(
            call = call,
            rateLimiter = donationPageRateLimiter,
            config = config,
            render = { origin -> EmbedDonationHtml.thanksPage(brandTitle = brandTitle, canonicalOrigin = origin) },
        )
    }

    get("/embed/v1/spende/abgebrochen") {
        respondDonationReturnPage(
            call = call,
            rateLimiter = donationPageRateLimiter,
            config = config,
            render = { origin -> EmbedDonationHtml.cancelledPage(brandTitle = brandTitle, canonicalOrigin = origin) },
        )
    }
}

/**
 * Shared handler body for both Stripe-return pages -- see [EmbedDonationHtml] KDoc for why an
 * unknown/missing `?origin=` renders WITHOUT a return link at `HTTP 200` rather than any error
 * status: the visitor has just paid, or just cancelled, and a 403 at this exact moment is the worst
 * possible failure this product could produce. `Cache-Control: no-store` is the FIRST statement,
 * and the whole body runs in `runCatching { }.onFailure { runCatching { } }` -- exactly the
 * `/embed/v1/login` handler's own pattern (`EmbedRoutes.kt`), so a late failure never escapes as a
 * bare 500 without this route's own security headers.
 */
private suspend fun respondDonationReturnPage(
    call: ApplicationCall,
    rateLimiter: FederationInboxRateLimiter,
    config: EmbedConfig,
    render: (String?) -> String,
) {
    runCatching {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.applyEmbedPageHeaders()
            call.respondText(text = render(null), contentType = HTML_CONTENT_TYPE, status = HttpStatusCode.TooManyRequests)
            return@runCatching
        }
        val rawOrigin = call.request.queryParameters["origin"]
        val canonicalOrigin = config.allowlist.canonicalize(rawOrigin)
        call.applyEmbedPageHeaders()
        call.respondText(text = render(canonicalOrigin), contentType = HTML_CONTENT_TYPE)
    }.onFailure {
        runCatching {
            call.applyEmbedPageHeaders()
            call.respond(HttpStatusCode.InternalServerError)
        }
    }
}

private suspend fun ApplicationCall.respondEmbedDonationJson(
    status: HttpStatusCode,
    body: String,
) {
    response.header("X-Content-Type-Options", "nosniff")
    respondText(text = body, contentType = EMBED_DONATION_JSON_CONTENT_TYPE, status = status)
}

/** Minimal, dependency-free JSON string escape for the handful of values interpolated above (a Stripe-hosted redirect URL). */
private fun jsonString(value: String): String {
    val escaped =
        buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    return escaped
}
