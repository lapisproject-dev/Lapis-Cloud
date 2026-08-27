package network.lapis.cloud.server.routes

import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.SocialReadPipeline
import network.lapis.cloud.server.rpc.SocialVisibility
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- `GET /transparenz`, a SECOND unauthenticated,
 * account-less public HTML route, sibling to `/s` (`SocialPublicRoutes`). Reuses THAT file's
 * shared plumbing verbatim -- [withPublicErrorHandling]/[applyPublicPageHeaders]/
 * [respondPublicCacheable]/[respondPublicTooManyRequests]/[respondPublicCanonicalRedirect]/
 * [hasOnlyAllowedQueryParams]/[HTML_CONTENT_TYPE]/[rateLimitKeyFor] -- widened from `private` to
 * `internal` there for exactly this reuse (see that file's own KDoc on each symbol). NOT a second,
 * independent copy of the CSP/security-header set: there is exactly ONE definition, in
 * `SocialPublicRoutes.kt`.
 *
 * **Ablauf** (identical shape to `/s`'s own class KDoc "Ablauf pro Handler"): the whole handler
 * body runs inside [withPublicErrorHandling] -> an OWN, dedicated IP rate limiter (never `/s`'s own
 * budget, see `readRateLimiter` KDoc below) -> a canonical-URL guard (this route accepts NO query
 * parameters at all -- ANY query string 308-redirects to the bare `$baseUrl/transparenz`, before
 * any DB work) -> ONE short `transaction {}` loading every section via
 * [PublicTransparencyReader] -> transaction CLOSED -> render OUTSIDE of it
 * ([PublicTransparencyHtml.page]) -> ETag/304 via [respondPublicCacheable].
 *
 * **`Cache-Control: public, max-age=60`, deliberately WITHOUT `stale-while-revalidate`** -- unlike
 * `/s`'s 300/3600 pair. A member's consent revocation (`IDsgvoService.revokePublicRankingConsent`)
 * must become invisible to the public within a bounded, short time; `stale-while-revalidate`
 * explicitly permits a proxy to keep serving an ALREADY-EXPIRED cached response for up to its own
 * window while revalidating in the background -- exactly the opposite of "a revocation takes
 * effect promptly". Plain `max-age=60` gives every proxy in front of this server a hard, known
 * upper bound instead.
 *
 * [MIN_RANKING_COHORT] (D11): [PublicTransparencyReader.loadTopLtrHolders]/[.loadTopDonors] each
 * return their section's `cohortSize` alongside its (possibly-empty) `rows`. Below this threshold,
 * [buildView] passes `null` for that section -- [PublicTransparencyHtml.page] then omits the
 * section ENTIRELY, including its jump-menu anchor -- so the mere absence of a section carries no
 * information about how many people almost-but-not-quite reached the threshold.
 */
private val MIN_RANKING_COHORT = 5L

fun Route.registerPublicTransparencyRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    /** V1.2.5 White-Label-Branding -- see `registerSocialPublicRoutes`'s own `brandTitle` KDoc. */
    brandTitle: String = BrandConfig.DEFAULT_TITLE,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')

    get("/transparenz") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = emptySet())) {
                call.respondPublicCanonicalRedirect(canonicalUrl = "$baseUrl/transparenz")
                return@withPublicErrorHandling
            }
            val view = transaction { buildView() }
            val body = PublicTransparencyHtml.page(view = view, baseUrl = baseUrl, brandTitle = brandTitle)
            call.respondPublicCacheable(body = body, contentType = HTML_CONTENT_TYPE, cacheControl = "public, max-age=60")
        }
    }
}

private fun buildView(): PublicTransparencyView {
    val stats = PublicTransparencyReader.loadStats()
    val board = PublicTransparencyReader.loadBoard()
    val topPosts = loadTopPosts()
    val ltrSection = PublicTransparencyReader.loadTopLtrHolders(limit = TOP_RANKING_LIMIT)
    val donationYear = DbClock.nowLocalDateTime().year
    val donationsSection = PublicTransparencyReader.loadTopDonors(year = donationYear, limit = TOP_RANKING_LIMIT)
    return PublicTransparencyView(
        stats = stats,
        board = board,
        topPosts = topPosts,
        ltr = ltrSection.takeIf { it.cohortSize >= MIN_RANKING_COHORT },
        donations = donationsSection.takeIf { it.cohortSize >= MIN_RANKING_COHORT },
        donationYear = donationYear,
    )
}

private const val TOP_RANKING_LIMIT = 10
private const val TOP_POSTS_LIMIT = 5

/**
 * Reuses [SocialReadPipeline.timelinePage] with the EXACT same `condition`/`caps`/ranking shape
 * `/s`'s own timeline handler uses ([SocialVisibility.publicReadableCondition] + root-only +
 * published-within-horizon, [SocialReadPipeline.SocialReadCaps.PUBLIC]) -- never a second,
 * independently-formulated ranking query. Deliberately without a horizon guard on the root-level
 * `condition` itself here (unlike `/s`, which restricts the FIRST page to the ranking horizon):
 * a five-post teaser on a transparency overview page showing "our best content ever", not just
 * "recent", is the more honest representation for this narrower use -- `SocialReadPipeline
 * .timelinePage`'s own weight-ranking (which already factors in recency via
 * `SocialPostWeight.ownWeightUnrounded`'s time-decay) still applies.
 */
private fun loadTopPosts(): List<PublicPostView> {
    val now = DbClock.nowLocalDateTime()
    val condition = SocialVisibility.publicReadableCondition() and SocialPostTable.parentId.isNull()
    val pageDto =
        SocialReadPipeline.timelinePage(
            condition = condition,
            horizon = null,
            limit = TOP_POSTS_LIMIT,
            offset = 0,
            now = now,
            viewerStatus = null,
            caps = SocialReadPipeline.SocialReadCaps.PUBLIC,
            ltrBalanceProvider = LedgerBackedLtrBalanceProvider(),
        )
    return pageDto.posts.map { post ->
        PublicPostView(
            id = post.id,
            depth = post.depth,
            authorDisplayName = post.authorDisplayName,
            contentLines = post.content.split("\n"),
            contentErased = post.contentErasedAt != null,
            totalWeightLtr = post.totalCurrentWeightLtr.toPlainString(),
            ownWeightLtr = post.ownCurrentWeightLtr.toPlainString(),
            publishedAtIso = post.publishedAt.toString(),
            publishedAtHuman = "%02d.%02d.%04d".format(post.publishedAt.dayOfMonth, post.publishedAt.monthNumber, post.publishedAt.year),
        )
    }
}
