package network.lapis.cloud.server.routes

import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.legal.LegalConfig

/**
 * V1.4.7 "Rechtstexte" -- `GET /impressum` and `GET /datenschutz`, the FOURTH and FIFTH
 * unauthenticated, account-less public HTML route of this codebase. Reuses the existing plumbing
 * from `SocialPublicRoutes.kt` verbatim ([withPublicErrorHandling]/[applyPublicPageHeaders]/
 * [respondPublicCacheable]/[respondPublicTooManyRequests]/[respondPublicCanonicalRedirect]/
 * [hasOnlyAllowedQueryParams]/[HTML_CONTENT_TYPE]/[rateLimitKeyFor]) -- never a second, driftable
 * copy of the CSP/security-header set.
 *
 * **No `transaction {}`, no DB access at all.** The page content is a pure function of
 * ([LegalConfig], [ResolvedBranding], [PublicLanguage]) -- all three are fixed at startup time.
 * Because of that, all 2 × 8 render variants are precomputed ONCE at registration time (below)
 * instead of per request: this makes the byte-identity [respondPublicCacheable] relies on trivially
 * guaranteed, and needs no invalidation, unlike [PublicLandingRoutes]'s TTL memo.
 *
 * `noindex,follow` (like `/transparenz`): a legal-text page must be reachable FROM the page the
 * visitor is on, not from a search index. No `hreflang` alternates set -- the full text exists in
 * German only.
 */
fun Route.registerLegalRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    legal: LegalConfig,
    /** V1.6.1: renders the KI-assistance privacy paragraph -- only for an installation where the AI layer is operational. */
    aiAssistantEnabled: Boolean = false,
    /** V1.7.1: renders the Keycloak-login privacy paragraph -- only for an installation with Keycloak login enabled. */
    keycloakEnabled: Boolean = false,
    /** V1.8.1: renders the MCP-access privacy paragraph -- only for an installation where the MCP layer is operational. */
    mcpEnabled: Boolean = false,
    /**
     * Welle V1.9.11 -- see [PublicNavAvailabilityProvider] KDoc. No default -- every caller
     * (production, tests) must pass one explicitly. **Sampled ONCE, at registration time** (below),
     * NOT per request -- unlike every other `register*PublicRoutes` in this codebase: this file's own
     * class KDoc "no DB access at all" precomputes all 2 × 8 render variants once, and a per-request
     * `navAvailability.current()` call would either break that precomputation (falling back to a
     * per-request render) or silently freeze the FIRST snapshot forever -- the SAME thing this
     * one-time sampling already does, just made explicit rather than accidental. A production
     * installation may therefore show `/impressum`/`/datenschutz`'s two optional tabs briefly out of
     * step with `/`/`/s`/`/transparenz`/`/aktuelles`/`/veranstaltungen` immediately after the FIRST
     * article/event is ever published -- a full restart (which any operator already does for other
     * configuration changes) refreshes it. Documented limitation, not a defect.
     */
    navAvailability: PublicNavAvailabilityProvider,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    val nav = navAvailability.current()

    val imprintBodies: Map<PublicLanguage, String> =
        PublicLanguage.entries.associateWith { lang ->
            LegalHtml.imprintPage(legal = legal, baseUrl = baseUrl, branding = branding, lang = lang, nav = nav)
        }
    val privacyBodies: Map<PublicLanguage, String> =
        PublicLanguage.entries.associateWith { lang ->
            LegalHtml.privacyPage(
                legal = legal,
                baseUrl = baseUrl,
                branding = branding,
                lang = lang,
                nav = nav,
                aiAssistantEnabled = aiAssistantEnabled,
                keycloakEnabled = keycloakEnabled,
                mcpEnabled = mcpEnabled,
            )
        }

    get("/impressum") {
        call.withPublicErrorHandling(baseUrl = baseUrl, branding = branding) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl, branding = branding, lang = call.resolvePublicLanguage())
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = setOf("lang")) || call.publicLangNeedsCanonicalization()) {
                call.respondPublicCanonicalRedirect(
                    canonicalUrl =
                        PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/impressum", lang = call.resolvePublicLanguage()),
                )
                return@withPublicErrorHandling
            }
            val lang = call.resolvePublicLanguage()
            call.respondPublicCacheable(
                body = imprintBodies.getValue(lang),
                contentType = HTML_CONTENT_TYPE,
                cacheControl = "public, max-age=3600",
                imgSrcSelf = branding.logoAvailable,
            )
        }
    }

    get("/datenschutz") {
        call.withPublicErrorHandling(baseUrl = baseUrl, branding = branding) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl, branding = branding, lang = call.resolvePublicLanguage())
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = setOf("lang")) || call.publicLangNeedsCanonicalization()) {
                call.respondPublicCanonicalRedirect(
                    canonicalUrl =
                        PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/datenschutz", lang = call.resolvePublicLanguage()),
                )
                return@withPublicErrorHandling
            }
            val lang = call.resolvePublicLanguage()
            call.respondPublicCacheable(
                body = privacyBodies.getValue(lang),
                contentType = HTML_CONTENT_TYPE,
                cacheControl = "public, max-age=3600",
                imgSrcSelf = branding.logoAvailable,
            )
        }
    }
}
