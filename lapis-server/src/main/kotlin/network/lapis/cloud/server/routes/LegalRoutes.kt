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
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')

    val imprintBodies: Map<PublicLanguage, String> =
        PublicLanguage.entries.associateWith { lang ->
            LegalHtml.imprintPage(legal = legal, baseUrl = baseUrl, branding = branding, lang = lang)
        }
    val privacyBodies: Map<PublicLanguage, String> =
        PublicLanguage.entries.associateWith { lang ->
            LegalHtml.privacyPage(legal = legal, baseUrl = baseUrl, branding = branding, lang = lang)
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
