package network.lapis.cloud.server.routes

import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Welle V1.4.6 "Öffentliche Startseite" -- `GET /`, the THIRD unauthenticated, account-less public
 * HTML route, sibling to `/s` ([SocialPublicRoutes]) and `/transparenz` ([PublicTransparencyRoutes]).
 * Reuses THOSE files' shared plumbing verbatim -- [withPublicErrorHandling]/[applyPublicPageHeaders]/
 * [respondPublicCacheable]/[respondPublicTooManyRequests]/[respondPublicCanonicalRedirect]/
 * [hasOnlyAllowedQueryParams]/[HTML_CONTENT_TYPE]/[rateLimitKeyFor] -- NEVER a second, driftable copy
 * of the CSP/security-header set: there is exactly ONE definition, in `SocialPublicRoutes.kt`.
 *
 * **Ablauf** (identical shape to `/s`'s and `/transparenz`'s own "Ablauf" KDoc): the whole handler
 * body runs inside [withPublicErrorHandling] -> an OWN, dedicated IP rate limiter ([readRateLimiter],
 * never `/s`'s or `/transparenz`'s own budget) -> a canonical-URL guard (this route accepts NO query
 * parameters at all -- ANY query string 308-redirects to the bare `$baseUrl/`, before any DB work) ->
 * [renderCachedBody] returns the memoized body if still fresh, otherwise ONE short `transaction {}`
 * via [buildView] -> transaction CLOSED -> render OUTSIDE of it ([PublicLandingHtml.page]) -> ETag/304
 * via [respondPublicCacheable].
 *
 * **Body memoization (Security-Audit-Fund MAJOR, Welle V1.4.6 Review-Nachzug)**: `GET /` -- the
 * single most-requested URL of any installation -- used to pay the FULL aggregation cost
 * ([buildView]: a `MemberTable` COUNT, a `LtrLedgerEntryTable` SUM, [SocialPublicSitemap
 * .countPublicRoots], and, once any public post exists, a full [network.lapis.cloud.server.rpc
 * .SocialReadPipeline.timelinePage] ranking pass over up to `workingSetRows` + `descendantRows` +
 * `boostRows` rows) on EVERY non-conditional request, INCLUDING one carrying `If-None-Match` --
 * [respondPublicCacheable] only computes the ETag from the already-rendered body, so a 304 never
 * skips the render. `Cache-Control: public, max-age=300` only helps behind a shared cache that
 * actually caches `/`; the documented production topology (Caddy `reverse_proxy`) does not by
 * default. [readRateLimiter]'s 60/min-per-IP budget bounds ONE source, never a distributed one
 * (many fresh IPs, e.g. a crawler fleet or a viral link) -- each such source can independently drive
 * a full aggregation every request, all against the SAME Exposed connection pool the authenticated
 * member area also depends on; pool exhaustion there takes down the whole application, not just this
 * page. [renderCachedBody] closes this with a short, in-memory, per-[registerPublicLandingRoutes]-
 * call TTL memo of the RENDERED BODY (never the raw stats -- the body is what every caller actually
 * needs) -- [LANDING_CACHE_TTL] stays well inside the already-promised 300 s `Cache-Control` window,
 * so no caller-visible contract changes, and [PublicLandingHtml]'s own "Datensparsamkeit"/determinism
 * KDoc (points 3-5) plus this file's `PublicLandingRoutesTest` Test 7 (byte-identity of two immediate
 * calls) already establish the precondition a memo needs: the render is a pure, request-independent
 * function of DB state. A concurrent cache-miss race may render twice -- both renders are identical
 * and idempotent, so this only ever costs one extra aggregation, never a correctness issue.
 *
 * **`Cache-Control: public, max-age=300`, deliberately WITHOUT `stale-while-revalidate`, and 300
 * (not `/transparenz`'s 60)**: this page carries no consent-revocable PII at all (see
 * [PublicLandingHtml]'s own "Datensparsamkeit" KDoc) -- there is no 60-second revocation promise to
 * honor here, so there is no reason to match `/transparenz`'s shorter window. `stale-while-revalidate`
 * still stays OFF (unlike `/s`'s 300/3600 pair): the operator should see a bounded, known worst case
 * for how long a stat/post change can take to reach a proxy-cached visitor, not an unbounded one.
 *
 * **`robots: "index,follow"`, deliberately the OPPOSITE of `/transparenz`'s `noindex,follow`** -- this
 * carries the entire rationale for rendering this page server-side (Option B) instead of simply
 * moving the SPA dashboard onto `/`: a search engine can index it in full, precisely because it shows
 * nothing a revocable consent governs.
 */
fun Route.registerPublicLandingRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    /** Welle V1.2.5 White-Label-Branding -- see `registerSocialPublicRoutes`'s own `brandTitle` KDoc. */
    brandTitle: String = BrandConfig.DEFAULT_TITLE,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    // See class KDoc "Body memoization" -- a fresh holder PER [registerPublicLandingRoutes] call
    // (never a file-level/companion singleton), so production gets exactly one shared cache for the
    // route's lifetime while every `testApplication { routing { registerPublicLandingRoutes(...) } }`
    // call in `PublicLandingRoutesTest` still starts from a clean, un-poisoned cache -- each test
    // calls this function anew, which allocates a brand-new [AtomicReference] closed over by the
    // `get("/")` handler below.
    val cachedBody = AtomicReference<CachedLandingBody?>(null)

    get("/") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = emptySet())) {
                call.respondPublicCanonicalRedirect(canonicalUrl = "$baseUrl/")
                return@withPublicErrorHandling
            }
            val body = renderCachedBody(cachedBody = cachedBody, baseUrl = baseUrl, brandTitle = brandTitle)
            call.respondPublicCacheable(
                body = body,
                contentType = HTML_CONTENT_TYPE,
                cacheControl = "public, max-age=300",
                // Plan § 1.2 "Hash-Bridge": the ONE caller in this whole codebase that requests
                // script-src 'self' -- /s and /transparenz never pass this, and stay script-free.
                scriptSrcSelf = true,
            )
        }
    }
}

/**
 * Genau EIN `transaction {}`-Aufruf des Callers (siehe [registerPublicLandingRoutes] "Ablauf"). Lädt
 * [PublicTransparencyReader.loadStats] (bereits `internal` -- kein Sichtbarkeits-Fund dieser Welle,
 * siehe Plan § 0) und, nur wenn mindestens ein öffentlicher Beitrag existiert, die Top-Beiträge via
 * [loadTopPosts] (in [PublicTransparencyRoutes] auf `internal` erweitert, siehe dessen KDoc).
 *
 * **Leerzustand ist Pflicht (Kare/Jobs)**: [PublicLandingView.stats] wird `null`, wenn die Installation
 * VOLLSTÄNDIG leer ist (0 Mitglieder UND 0 öffentliche Beiträge) -- der Kennzahlenblock entfällt dann
 * GANZ, statt drei Nullen zu zeigen. Der `publicPostCount == 0L`-Guard vor [loadTopPosts] spart
 * zusätzlich die komplette `SocialReadPipeline.timelinePage`-Abfrage auf einer frischen Installation.
 */
private fun buildView(): PublicLandingView {
    val stats = PublicTransparencyReader.loadStats()
    val posts = if (stats.publicPostCount == 0L) emptyList() else loadTopPosts(limit = LANDING_TOP_POSTS_LIMIT)
    val showStats = !(stats.activeMemberCount == 0L && stats.publicPostCount == 0L)
    return PublicLandingView(stats = stats.takeIf { showStats }, topPosts = posts)
}

private const val LANDING_TOP_POSTS_LIMIT = 5

/**
 * Security-Audit-Fund MAJOR (Welle V1.4.6 Review-Nachzug) -- see [registerPublicLandingRoutes] class
 * KDoc "Body memoization" for the full rationale. Deliberately short: well inside the already-promised
 * 300 s `Cache-Control: public, max-age=300` (so no caller ever observes staler data than the response
 * header already allows for), yet long enough to collapse the [SocialReadPipeline.timelinePage]-driven
 * cost of a burst/crawler/distributed-fresh-IP request pattern down to roughly one full render per
 * [LANDING_CACHE_TTL] instead of one per request.
 */
private val LANDING_CACHE_TTL = 30.seconds

/** Immutable snapshot: the fully rendered `GET /` body plus the instant its memo entry goes stale. */
private data class CachedLandingBody(
    val body: String,
    val expiresAt: Instant,
)

/**
 * Returns [cachedBody]'s current body if it is still fresh, otherwise renders a new one (via
 * [buildView] inside a fresh `transaction {}`, exactly as before this memoization was added) and
 * publishes it as the new cache entry. [AtomicReference.get]/[AtomicReference.set] rather than a
 * lock -- see [registerPublicLandingRoutes] class KDoc "Body memoization" for why a concurrent
 * cache-miss race (two callers both rendering once) is harmless here: both renders are byte-identical
 * and idempotent, so the only cost of the race is one redundant aggregation, never a correctness
 * issue. Not a `suspend fun` -- exactly like the `transaction {}` call it wraps, this blocks the
 * calling thread for the duration of the (short) DB work, unchanged from the pre-memoization behavior.
 */
private fun renderCachedBody(
    cachedBody: AtomicReference<CachedLandingBody?>,
    baseUrl: String,
    brandTitle: String,
): String {
    val now = Clock.System.now()
    cachedBody.get()?.let { cached -> if (cached.expiresAt > now) return cached.body }
    val view = transaction { buildView() }
    val body = PublicLandingHtml.page(view = view, baseUrl = baseUrl, brandTitle = brandTitle)
    cachedBody.set(CachedLandingBody(body = body, expiresAt = now + LANDING_CACHE_TTL))
    return body
}
