package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.member.MemberCardEligibility
import network.lapis.cloud.server.member.MemberCardResolution
import network.lapis.cloud.server.member.MemberCardStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.MemberCardCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- `GET /ausweis?code=...`, the unauthenticated page a
 * scanned card QR code opens. Registered before `staticFiles`, same "literal beats catch-all"
 * reasoning [registerEventPublicRoutes]/[registerLegalRoutes] document for their own routes.
 *
 * **Strictly read-only.** The previous wave's security audit found a real DoS here: an earlier
 * `MemberCardStore.resolve()` allocated a `member_number` as a side effect, which meant this very
 * GET took exclusive row locks on a per-year sequence row shared by every member. `resolve()` is
 * now side-effect-free by construction (see its KDoc) and this route must keep it that way -- no
 * write of any kind belongs on an endpoint whose only credential is a value printed on a card.
 *
 * **One negative answer for four different negatives.** Unknown code, revoked code, malformed code
 * and "code resolves, but the holder is no longer a member" all render the identical page with the
 * identical status -- see [MemberCardPublicHtml] KDoc for the enumeration/oracle argument, and
 * [MemberCardEligibility] KDoc for why membership status is re-checked at all rather than trusting
 * the code row.
 *
 * **HTTP 200 for the negative page, not 404.** The negative page is a real answer to a
 * well-formed question ("is this card valid?" -- "no"), not a missing resource, and a distinct
 * status code would itself be the oracle the identical page bodies exist to avoid: an attacker
 * scripting code guesses reads the status line, not the prose.
 *
 * **Two rate limiters, the same pair [registerEventPublicRoutes]' ticket routes use**, for the same
 * reason: [pageRateLimiter] is a generous per-IP budget on every request (a member re-scanning
 * their own card at a desk must not be punished), [codeFailureLimiter] is a strict failures-only
 * guard that only a caller producing negative answers ever consumes. Guessing an 80-bit
 * Crockford-Base32 code is not a realistic attack; enumerating or scraping IS the realistic one,
 * and the failures-only limiter is what caps it.
 */
internal fun Route.registerMemberCardPublicRoutes(
    brandTitle: String,
    pageRateLimiter: FederationInboxRateLimiter,
    codeFailureLimiter: LoginRateLimiter,
) {
    get("/ausweis") {
        call.withMemberCardErrorHandling(brandTitle = brandTitle) {
            val remoteKey = "ip:${rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)}"
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondMemberCardPage(
                    body = MemberCardPublicHtml.tooManyRequestsPage(brandTitle),
                    status = HttpStatusCode.TooManyRequests,
                )
                return@withMemberCardErrorHandling
            }
            if (!codeFailureLimiter.checkAllowed(remoteKey)) {
                call.respondMemberCardPage(
                    body = MemberCardPublicHtml.tooManyRequestsPage(brandTitle),
                    status = HttpStatusCode.TooManyRequests,
                )
                return@withMemberCardErrorHandling
            }

            // Canonicalization BEFORE any database access -- a malformed code never reaches a
            // query (the first line of DoS defense [MemberCardCode.canonicalize] KDoc describes).
            val canonical = MemberCardCode.canonicalize(call.request.queryParameters["code"])
            val resolution = canonical?.let { code -> transaction { MemberCardStore.resolve(code) } }
            val valid =
                (resolution as? MemberCardResolution.Valid)
                    ?.takeIf { MemberCardEligibility.isEligible(it.status) }

            if (valid == null) {
                codeFailureLimiter.recordFailure(remoteKey)
                call.respondMemberCardPage(body = MemberCardPublicHtml.invalidPage(brandTitle), status = HttpStatusCode.OK)
                return@withMemberCardErrorHandling
            }
            // A successful lookup clears this IP's failure budget -- otherwise a shared NAT/office
            // egress IP could lock out its own legitimate users after twenty stale scans.
            codeFailureLimiter.reset(remoteKey)
            call.respondMemberCardPage(
                body =
                    MemberCardPublicHtml.validPage(
                        brandTitle = brandTitle,
                        card =
                            MemberCardPublicHtml.ValidCard(
                                displayName = valid.displayName,
                                memberNumber = valid.memberNumber,
                                statusLabel = MemberCardEligibility.statusLabel(valid.status),
                                joinedAt = valid.joinedAt,
                            ),
                    ),
                status = HttpStatusCode.OK,
            )
        }
    }
}

/**
 * `no-store` unconditionally, on every outcome: the response body is keyed by a bearer credential
 * in the query string, so no shared cache may ever hold it. Reuses [applyPublicPageHeaders] rather
 * than re-declaring this route family's CSP, so the header set can never drift from the other
 * public pages.
 */
private suspend fun ApplicationCall.respondMemberCardPage(
    body: String,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    if (status == HttpStatusCode.TooManyRequests) response.header(HttpHeaders.RetryAfter, "60")
    applyPublicPageHeaders()
    respondText(text = body, contentType = HTML_CONTENT_TYPE, status = status)
}

/**
 * No exception -- expected or not -- escapes as Ktor's bare, header-less default 500; same
 * discipline `SocialPublicRoutes.withPublicErrorHandling`/`EventPublicRoutes
 * .withEventPublicErrorHandling` establish for their own route families. Plain `try`/`catch`, not
 * `runCatching`, so a [CancellationException] propagates instead of being converted into a second
 * `respond*` on an already-dead call (the same INFO-level security-audit fix those two carry).
 *
 * The thrown exception is logged WITHOUT the request's query string -- that string contains a card
 * code, and an exception log is not a place to keep bearer credentials.
 */
private suspend fun ApplicationCall.withMemberCardErrorHandling(
    brandTitle: String,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "GET /ausweis failed" }
        runCatching {
            respondMemberCardPage(
                body = MemberCardPublicHtml.errorPage(brandTitle),
                status = HttpStatusCode.InternalServerError,
            )
        }
    }
}
