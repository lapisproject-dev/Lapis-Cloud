package network.lapis.cloud.server.routes

import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- `GET /vorstand`, `GET /politiker`, `GET /landesverbaende`.
 * Same shared plumbing as [registerPublicArticlesOverviewRoutes] ([withPublicErrorHandling],
 * [respondPublicCacheable], the canonical-URL guard with `lang` as the only allowed query
 * parameter, an OWN per-page IP rate limiter), with THREE deliberate differences:
 *
 * 1. **No body memo and `Cache-Control: no-store`.** `/aktuelles` memoizes its body for 30 s and
 *    sends `public, max-age=300`. Here that would break the promise behind the consents
 *    ("withdrawal takes effect immediately"): a withdrawn photo, bio or listing must be gone from the
 *    very next request. Copying the `/aktuelles` caching by reflex is the trap this KDoc exists for.
 *    Only the VISIBILITY OF THE NAV TAB is cached (30 s, [PublicNavAvailabilityProvider]) -- never
 *    data.
 * 2. **`imgSrcSelf` is always `true`.** Photos and crests are same-origin `<img>`; without
 *    `img-src 'self'` in the CSP the browser would silently block every one of them.
 * 3. **An empty list is a 200 with an empty state**, never a 404 -- the tab may lag up to 30 s.
 *
 * The data comes from [PublicProfilesReader] -- the SAME loaders the nav availability check and the
 * embed feeds use. One short `transaction {}`, closed before rendering.
 */
fun Route.registerPublicBoardOverviewRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
) {
    registerProfilesPage(
        path = "/vorstand",
        readRateLimiter = readRateLimiter,
        branding = branding,
        navAvailability = navAvailability,
    ) { baseUrl, lang, nav ->
        val cards = transaction { PublicProfilesReader.loadBoardCards() }
        PublicProfilesHtml.boardPage(cards = cards, baseUrl = baseUrl, branding = branding, lang = lang, nav = nav)
    }
}

fun Route.registerPublicPoliticiansOverviewRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
) {
    registerProfilesPage(
        path = "/politiker",
        readRateLimiter = readRateLimiter,
        branding = branding,
        navAvailability = navAvailability,
    ) { baseUrl, lang, nav ->
        val cards = transaction { PublicProfilesReader.loadPoliticianCards() }
        PublicProfilesHtml.politiciansPage(cards = cards, baseUrl = baseUrl, branding = branding, lang = lang, nav = nav)
    }
}

fun Route.registerPublicChaptersOverviewRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
) {
    registerProfilesPage(
        path = "/landesverbaende",
        readRateLimiter = readRateLimiter,
        branding = branding,
        navAvailability = navAvailability,
    ) { baseUrl, lang, nav ->
        val cards = transaction { PublicProfilesReader.loadChapterCards() }
        PublicProfilesHtml.chaptersPage(cards = cards, baseUrl = baseUrl, branding = branding, lang = lang, nav = nav)
    }
}

private fun Route.registerProfilesPage(
    path: String,
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
    render: (baseUrl: String, lang: PublicLanguage, nav: PublicNavAvailability) -> String,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    get(path) {
        call.withPublicErrorHandling(baseUrl = baseUrl, branding = branding) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl, branding = branding, lang = call.resolvePublicLanguage())
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = setOf("lang")) || call.publicLangNeedsCanonicalization()) {
                call.respondPublicCanonicalRedirect(
                    canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = path, lang = call.resolvePublicLanguage()),
                )
                return@withPublicErrorHandling
            }
            val lang = call.resolvePublicLanguage()
            // Sampled BEFORE the data transaction, never from inside it -- see PublicNavAvailabilityProvider.current KDoc "S2".
            val nav = navAvailability.current()
            val body = render(baseUrl, lang, nav)
            call.respondPublicCacheable(
                body = body,
                contentType = HTML_CONTENT_TYPE,
                cacheControl = "no-store",
                imgSrcSelf = true,
            )
        }
    }
}
