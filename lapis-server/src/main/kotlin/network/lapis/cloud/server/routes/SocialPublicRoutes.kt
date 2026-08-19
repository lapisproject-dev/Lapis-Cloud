package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.formFieldLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.LEGAL_REMOVAL_FALLBACK_REASON
import network.lapis.cloud.server.rpc.SocialPostWeight
import network.lapis.cloud.server.rpc.SocialReadPipeline
import network.lapis.cloud.server.rpc.SocialReportSubmission
import network.lapis.cloud.server.rpc.SocialVisibility
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialThreadDto
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Feste Seitengröße -- der öffentliche Pfad bietet KEINEN client-steuerbaren `limit`-Parameter (der
 * authentifizierte hat `MAX_TIMELINE_LIMIT` = 100; kontenlos gibt es keinen Grund, die Arbeitsmenge
 * einem anonymen Aufrufer in die Hand zu geben).
 */
private const val PUBLIC_PAGE_SIZE = 20

/** Höchste erreichbare Seite -> offset <= 480, sicher unter `SocialReadCaps.PUBLIC.workingSetRows` (500). */
private const val PUBLIC_MAX_PAGES = 25

/**
 * Security-Audit-Fund S-3 (2026-08-18): the ONLY query parameter name `GET /s` accepts -- any other
 * parameter present on the request (e.g. `?x=<random>`) is a canonicalization candidate, see
 * [hasOnlyAllowedQueryParams]/[canonicalRedirectIfNeeded].
 */
private val TIMELINE_ALLOWED_QUERY_PARAMS = setOf("page")

/**
 * Security-Audit-Fund MAJOR-1 (Runde 1, 2026-08-19): `POST /s/{id}/report`'s hard ceiling on
 * `Content-Length` -- checked in [reportBodyExceedsLimit] BEFORE `call.receiveParameters()` ever
 * buffers a byte. The domain fields this route accepts max out at `description` (4000 chars,
 * [network.lapis.cloud.server.rpc.SocialReportSubmission]'s `MAX_DESCRIPTION_LENGTH`) +
 * `reporterContact` (320 chars) + a short `category` enum name + a `goodFaith`/`website` checkbox
 * value -- comfortably under 5 KB even accounting for URL-encoding overhead and field-name
 * repetition. 16 KiB leaves generous headroom for that without coming anywhere close to Ktor's
 * default 50 MiB `formFieldLimit` (`io.ktor.server.request.formFieldLimit`), which an unauthenticated
 * caller could otherwise use to force tens-of-megabytes allocations per request before the
 * EXISTING domain-level length check in `SocialReportSubmission.submitPublic` (which only runs
 * AFTER the body is already fully in memory) ever gets a chance to reject anything.
 */
private const val REPORT_MAX_BODY_BYTES = 16 * 1024L

/**
 * Security-Audit-Fund S-3 (2026-08-18): `GET /s/{id}` accepts NO query parameters at all -- every
 * piece of routing information lives in the path.
 */
private val THREAD_ALLOWED_QUERY_PARAMS = emptySet<String>()

/**
 * V1.1.3 Soziales Netzwerk "Öffentlicher SEO-Lesepfad" -- die ERSTEN unauthentifizierten,
 * öffentlich erreichbaren HTML-Routen dieses Servers. Registriert im `routing`-Block VOR
 * `staticFiles` (siehe `Application.kt`), aber wie jede andere Route unabhängig von der
 * KVision-SPA: `/s`, `/s/{id}`, `/s/assets/style.css`, `/sitemap.xml`, `/sitemap-{shard}.xml`,
 * `/robots.txt` -- keine Kollision mit dem hash-basierten Client-Routing (verifiziert:
 * `lapis-client`s Bundle enthält keine gleichnamigen Pfade, `Routing.init(useHash = true)`).
 *
 * **Keine neue globale Plugin-Installation.** Insbesondere KEIN globales
 * Content-Security-Policy-Plugin -- eine `default-src 'none'`-CSP über die gesamte Anwendung würde
 * die KVision-SPA sofort zerlegen. Alle Security-Header werden AUSSCHLIESSLICH innerhalb der
 * Handler dieser Datei gesetzt ([applyPublicPageHeaders]). Diese Trennung ist Absicht und darf
 * nicht "vereinheitlicht" werden.
 *
 * **Ablauf pro Handler (verbindliche Reihenfolge)**: der GESAMTE Handler-Körper läuft innerhalb von
 * [withPublicErrorHandling] (M1-Fix, Review-Runde 1) -> IP-Rate-Limit VOR jedem DB-Zugriff -> Pfad-/
 * Query-Parameter parsen (niemals werfend -- ungültige Eingabe ⇒ 404-Seite, nie 500) -> Canonical-
 * URL-Guard für `/s`/`/s/{id}` (Security-Audit-Fund S-3, 2026-08-18: ein unerwarteter Query-Parameter
 * -> 308-Redirect auf die kanonische URL, VOR jedem DB-Zugriff, siehe [hasOnlyAllowedQueryParams])
 * -> eine kurze `transaction { }` lädt über [SocialReadPipeline]/[network.lapis.cloud.server.routes
 * .SocialPublicSitemap] (gedeckelt über `SocialReadPipeline.SocialReadCaps.PUBLIC`) -> Transaktion
 * SCHLIESSEN -> Rendern AUSSERHALB der Transaktion (`SocialPublicHtml`/`SocialPublicSitemap`) ->
 * ETag/304 -> Security-Header auf JEDE Antwort inkl. 304/404/429/500. Niemals rendern oder auf den
 * Client schreiben, während eine Pool-Connection gehalten wird -- derselbe Mechanismus wie
 * Security-Audit-Fund S-A1 in `SocialReadPipeline`s eigener KDoc, und ein langsamer Leser auf einer
 * öffentlichen Route ist genau der Slowloris-Fall, den man sich damit nicht ins Haus holt.
 *
 * **Jede Exception, die aus einem Handler entkommen würde, wird von [withPublicErrorHandling]
 * abgefangen (M1-Fix, Review-Runde 1).** `StatusPages` (`Application.kt`) mappt ausschließlich
 * `UnauthenticatedException`/`ForbiddenException` -- für JEDE andere Exception (auch eine völlig
 * unerwartete) würde Ktors Default-Verhalten ein 500 OHNE jeden Security-Header und OHNE
 * `Cache-Control: no-store` ausliefern, das ein Proxy/CDN dann potenziell cacht. Deshalb läuft der
 * komplette Körper jedes einzelnen `get { }`-Blocks unten durch [withPublicErrorHandling] -- niemals
 * nur ein Teil davon, niemals mit einer Ausnahme "weil dieser Handler ja sicher ist".
 *
 * **`baseUrl` kommt ausschließlich aus [FederationConfig.publicBaseUrl]**, niemals aus
 * `call.request.host()`/dem `Host`-Header -- Host-Header-Injection würde sonst Canonical-/`og:url`-/
 * Sitemap-URLs vergiften. Berechnet EINMAL bei der Registrierung (Trailing Slash abgeschnitten),
 * nicht pro Request.
 *
 * **ETag = Weak-Content-Hash über den fertig gerenderten Body** (`W/"<32-Hex-SHA-256>"`), NICHT ein
 * aus DB-Aggregaten zusammengesetzter Fingerprint -- siehe Implementierungsplan § 5.1 für die volle
 * Begründung (nie stale/falsch-invalidiert, der reale Optimierungsvorteil eines 304 ist Bandbreite,
 * die ein Content-Hash vollständig einfängt). `Compression` ist global installiert
 * (`Application.kt`) -> `W/` (weak) + `Vary: Accept-Encoding` ist die für Proxies korrekte
 * Kombination, ein starker ETag würde eine byte-genaue Repräsentation behaupten, die je nach
 * Content-Coding abweicht.
 */
fun Route.registerSocialPublicRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    sitemapRateLimiter: FederationInboxRateLimiter,
    /** Welle V1.1.5 -- `POST /s/{id}/report` (öffentlicher Melde-Weg), EIGENER, deutlich strengerer Limiter als [readRateLimiter]. */
    reportRateLimiter: FederationInboxRateLimiter,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider()

    get("/s") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val rawPage = call.request.queryParameters["page"]
            val page = parsePage(raw = rawPage)
            // S-3: an unexpected query parameter (e.g. `?x=<random>`) defeats
            // `Cache-Control: public, max-age=300` at any proxy/CDN whose cache key includes the
            // query string -- redirect to the canonical URL BEFORE any DB work, so the origin's cost
            // for such a request is a single cheap redirect, never a full render.
            //
            // S2-3 (Runde 2, 2026-08-18): [hasOnlyAllowedQueryParams] only checks the PARAMETER NAME
            // -- `?page=1`, `?page=01`, `?page=0000001` are all "allowed" by name alone, yet each is a
            // DIFFERENT cache key at any proxy/CDN whose key includes the raw query string, defeating
            // the very cache-key-collapsing this canonicalization guard exists for. [pageQueryValueNeedsCanonicalization]
            // closes that gap by also requiring the raw value to be EXACTLY the canonical decimal
            // string of the already-clamped [page] (and, for page 1, requiring the parameter to be
            // absent entirely -- the canonical `/s` URL never carries `?page=1`).
            if (!call.hasOnlyAllowedQueryParams(allowed = TIMELINE_ALLOWED_QUERY_PARAMS) ||
                pageQueryValueNeedsCanonicalization(raw = rawPage, page = page)
            ) {
                call.respondPublicCanonicalRedirect(canonicalUrl = timelineCanonicalUrl(baseUrl = baseUrl, page = page))
                return@withPublicErrorHandling
            }
            val now = DbClock.nowLocalDateTime()
            val horizon = rankingHorizon(now = now)
            val condition =
                SocialVisibility.publicReadableCondition() and
                    SocialPostTable.parentId.isNull() and
                    (SocialPostTable.publishedAt greaterEq horizon)
            val pageDto =
                transaction {
                    SocialReadPipeline.timelinePage(
                        condition = condition,
                        horizon = horizon,
                        limit = PUBLIC_PAGE_SIZE,
                        offset = (page - 1) * PUBLIC_PAGE_SIZE,
                        now = now,
                        viewerStatus = null,
                        caps = SocialReadPipeline.SocialReadCaps.PUBLIC,
                        ltrBalanceProvider = ltrBalanceProvider,
                    )
                }
            val view =
                PublicTimelineView(
                    posts = pageDto.posts.map { it.toPublicView() },
                    page = page,
                    hasNext = page * PUBLIC_PAGE_SIZE < pageDto.totalRankedCount,
                )
            val body = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            call.respondPublicCacheable(
                body = body,
                contentType = HTML_CONTENT_TYPE,
                cacheControl = "public, max-age=300, stale-while-revalidate=3600",
            )
        }
    }

    get("/s/{id}") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val postUuid = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (postUuid == null) {
                call.respondPublicNotFound(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            // S-3: same canonicalization as `/s` above -- `/s/{id}` accepts NO query parameters at
            // all, so ANY query string present redirects to the bare path BEFORE any DB work. Uses
            // the REQUESTED id, not a resolved root id -- if [postUuid] turns out to be a comment,
            // the follow-up (query-free) request still 308-redirects to its root via the normal K4
            // path below; this only removes an unexpected query string from the cache key one hop
            // earlier, without needing to resolve the root first.
            if (!call.hasOnlyAllowedQueryParams(allowed = THREAD_ALLOWED_QUERY_PARAMS)) {
                call.respondPublicCanonicalRedirect(canonicalUrl = "$baseUrl/s/$postUuid")
                return@withPublicErrorHandling
            }
            val now = DbClock.nowLocalDateTime()
            val resolution =
                transaction {
                    val row =
                        SocialPostTable
                            .selectAll()
                            .where { (SocialPostTable.id eq postUuid) and SocialVisibility.publicReadableCondition() }
                            .singleOrNull()
                    val rootId = row?.get(SocialPostTable.rootId)
                    when {
                        // Welle V1.1.5 (E-B): war die primaere Aufloesung leer, weil dieser Beitrag
                        // rechtlich entfernt wurde? resolveRemovalNotice deckt den weit
                        // ueberwiegenden Regelfall (unbekannte UUID, nicht-oeffentlicher Beitrag,
                        // vom Autor versteckter Beitrag) selbst ab und faellt dort auf NotFound zurueck.
                        rootId == null -> resolveRemovalNotice(postUuid = postUuid)
                        // K4: a non-root id (a comment) is 308-redirected to its root -- never rendered directly.
                        rootId != postUuid -> {
                            // G5-Fix (Review-Runde 1): only the COMMENT's own readability was checked
                            // above -- verify the ROOT is public-readable too before redirecting to
                            // it. Not exploitable under the current S5 write invariant (a comment can
                            // only be public-readable if its root already is), but defense-in-depth
                            // against that invariant ever being relaxed: without this check, a future
                            // write path that let a comment outlive/outrank its root's visibility
                            // would 308-redirect an anonymous visitor to a target that then 404s.
                            val rootReadable =
                                SocialPostTable
                                    .select(SocialPostTable.id)
                                    .where { (SocialPostTable.id eq rootId) and SocialVisibility.publicReadableCondition() }
                                    .firstOrNull() != null
                            if (rootReadable) PostResolution.Redirect(rootId = rootId) else PostResolution.NotFound
                        }
                        else -> {
                            val thread =
                                SocialReadPipeline.thread(
                                    rootUuid = postUuid,
                                    condition = SocialVisibility.publicReadableCondition(),
                                    nodeReadable = { v, s -> SocialVisibility.isPublicReadable(visibility = v, state = s) },
                                    now = now,
                                    viewerStatus = null,
                                    caps = SocialReadPipeline.SocialReadCaps.PUBLIC,
                                    ltrBalanceProvider = ltrBalanceProvider,
                                )
                            if (thread == null) PostResolution.NotFound else PostResolution.Found(thread = thread)
                        }
                    }
                }
            when (resolution) {
                is PostResolution.NotFound -> call.respondPublicNotFound(baseUrl = baseUrl)
                is PostResolution.LegallyRemoved -> call.respondPublicLegallyRemoved(view = resolution.view, baseUrl = baseUrl)
                is PostResolution.Redirect -> call.respondPublicRedirect(baseUrl = baseUrl, rootId = resolution.rootId)
                is PostResolution.Found -> {
                    val view = resolution.thread.toPublicThreadView()
                    val body = SocialPublicHtml.postPage(view = view, baseUrl = baseUrl)
                    call.respondPublicCacheable(
                        body = body,
                        contentType = HTML_CONTENT_TYPE,
                        cacheControl = "public, max-age=300, stale-while-revalidate=3600",
                    )
                }
            }
        }
    }

    // Welle V1.1.5 -- DSA Art. 16, öffentlicher Melde-Weg. Ein anonymer Leser hat keinen
    // Kilua-RPC-Client (die CSP dieses Pfads verbietet jedes Skript) und kann
    // ISocialNetworkService.reportPost folglich nicht aufrufen -- deshalb ein klassisches
    // HTML-<form method=post>, dieselbe Kernlogik (SocialReportSubmission) wie der authentifizierte
    // RPC-Pfad.
    get("/s/{id}/report") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val postUuid = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (postUuid == null) {
                call.respondPublicNotFound(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val exists =
                transaction {
                    SocialPostTable
                        .select(SocialPostTable.id)
                        .where { (SocialPostTable.id eq postUuid) and SocialVisibility.publicReadableCondition() }
                        .firstOrNull() != null
                }
            if (!exists) {
                call.respondPublicNotFound(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val body = SocialPublicHtml.reportFormPage(postId = postUuid.toString(), baseUrl = baseUrl)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyPublicPageHeaders()
            call.respondText(text = body, contentType = HTML_CONTENT_TYPE)
        }
    }

    post("/s/{id}/report") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            // EIGENER, deutlich strengerer Limiter -- nicht der 30/min-Lese-Limiter (Plan § 4.3).
            if (!reportRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            // MAJOR-1 (Security-Audit Runde 1, 2026-08-19): reject an oversized -- or length-less,
            // i.e. chunked-encoded -- body BEFORE `receiveParameters()` buffers anything. `null`
            // (no `Content-Length` header) is treated the SAME as "too large": a length-less body
            // would otherwise bypass this check entirely. See [reportBodyExceedsLimit]/
            // [REPORT_MAX_BODY_BYTES] KDoc.
            if (reportBodyExceedsLimit(contentLength = call.request.contentLength())) {
                call.respondPublicMalformedRequest(baseUrl = baseUrl, status = HttpStatusCode.PayloadTooLarge)
                return@withPublicErrorHandling
            }
            // MINOR-3 (Security-Audit Runde 1, 2026-08-19): `receiveParameters()` throws for any
            // non-form-encoded `Content-Type` (including a missing one -- `contentType()` then
            // returns `ContentType.Any`, which never `.match()`es a concrete pattern), and that
            // exception previously escaped all the way to `withPublicErrorHandling`'s catch-all,
            // logging an ERROR-level stack trace and returning a bare 500 for what is really a
            // trivial, cheap-to-reject client input error -- an unauthenticated caller could
            // trigger unbounded ERROR-level log writes at will. Rejecting explicitly here, before
            // `receiveParameters()` is ever called, keeps this a clean, unlogged 400.
            if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                call.respondPublicMalformedRequest(baseUrl = baseUrl, status = HttpStatusCode.BadRequest)
                return@withPublicErrorHandling
            }
            // Defense in depth (MAJOR-1, Security-Audit Runde 2 Fund N-1): caps Ktor's OWN form-field
            // buffering (default 50 MiB, see REPORT_MAX_BODY_BYTES KDoc). Currently UNREACHABLE, since
            // the content-type gate above already rejects anything but `FormUrlEncoded` -- including
            // `multipart/form-data` -- before this line runs. Kept anyway so the cap is already in
            // place the moment that gate is ever widened to also accept multipart bodies; if that
            // never happens, this line stays a no-op.
            call.formFieldLimit = REPORT_MAX_BODY_BYTES
            val postUuid = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val params = call.receiveParameters()
            // Honeypot: klassischer No-JS-Bot-Schutz -- ausgefuellt => stiller No-Op, IDENTISCHE
            // Antwort wie ein legitimer Submit (Enumeration-Haertung).
            val honeypotFilled = !params["website"].isNullOrBlank()
            if (postUuid != null && !honeypotFilled) {
                val category = params["category"]?.let { raw -> runCatching { SocialPostReportCategory.valueOf(raw) }.getOrNull() }
                val description = params["description"].orEmpty().trim()
                val contact = params["contact"]?.trim()?.takeIf { it.isNotBlank() }
                val goodFaith = params["goodFaith"] != null
                val now = DbClock.nowLocalDateTime()
                transaction {
                    SocialReportSubmission.submitPublic(
                        postId = postUuid,
                        category = category,
                        description = description,
                        reporterContact = contact,
                        goodFaithConfirmed = goodFaith,
                        now = now,
                    )
                }
            }
            // Antwort IMMER identisch (Plan § 4.3) -- ob gespeichert wurde, ob der Post existiert,
            // ob das Honeypot-Feld ausgefuellt war. KEIN CSRF-Token (kein Schreibpfad mit einer
            // Session/privilegierten Wirkung dahinter -- ein fremdgesteuertes Absenden ist
            // funktional identisch zu direktem Spam und wird vom IP-Limiter behandelt).
            val body = SocialPublicHtml.reportSubmittedPage(baseUrl = baseUrl)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyPublicPageHeaders()
            call.respondText(text = body, contentType = HTML_CONTENT_TYPE)
        }
    }

    // Static asset -- not rate-limited (constant, in-memory string, no DB access) and not gated by
    // readRateLimiter's read budget, unlike /s and /s/{id} -- see class KDoc "Ablauf pro Handler".
    // Still wrapped in withPublicErrorHandling (M1) -- "cheap and constant today" is not a reason to
    // exempt a handler from the blanket guarantee that NOTHING here can ever escape as a bare 500.
    get("/s/assets/style.css") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            call.respondPublicCacheable(
                body = SocialPublicHtml.STYLESHEET,
                contentType = ContentType.Text.CSS.withParameter("charset", "utf-8"),
                // Welle V1.1.5 Review-Runde 2: war zuvor "max-age=86400, immutable" -- das liess einen
                // bereits gecachten Stand des Stylesheets (samt sichtbarem statt per CSS ausgeblendetem
                // Honeypot-Feld, vor V1.1.5) bis zu 24 h ueberleben, ohne dass ein Client je erneut
                // nachfragt. Diese URL ist NICHT versioniert (kein Hash im Pfad) und darf deshalb kein
                // `immutable` tragen, solange ihr Inhalt sich mit kuenftigen Wellen aendern kann.
                cacheControl = "public, max-age=300",
            )
        }
    }

    get("/sitemap.xml") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!sitemapRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            // M2-Fix (Review-Runde 1): decide urlset-vs-index from a cheap SQL COUNT(*), never from
            // an in-memory list of up to MAX_TOTAL_URLS root ids -- see SocialPublicSitemap KDoc
            // "Review-Runde-1 finding M2" for the two failure modes (>65535 bind parameters,
            // non-deterministic selection) this replaces.
            val totalCount = transaction { SocialPublicSitemap.countPublicRoots() }
            val body =
                if (totalCount <= SocialPublicSitemap.MAX_URLS_PER_FILE) {
                    val entries = transaction { SocialPublicSitemap.loadEntriesForShard(shard = 1) }
                    SocialPublicSitemap.renderUrlset(entries = entries, baseUrl = baseUrl)
                } else {
                    // N7-Fix (Review-Runde 2): MAX_URLS_PER_FILE (45 000) x MAX_SHARDS (10) ==
                    // MAX_TOTAL_URLS exactly -- that many roots still fit losslessly across all 10
                    // shards, so `totalCount == MAX_TOTAL_URLS` is NOT truncation yet; only a total
                    // that exceeds the ceiling actually loses roots.
                    if (totalCount > SocialPublicSitemap.MAX_TOTAL_URLS) {
                        logger.warn {
                            "Public sitemap truncated at ${SocialPublicSitemap.MAX_TOTAL_URLS} URLs " +
                                "(MAX_SHARDS=${SocialPublicSitemap.MAX_SHARDS} x " +
                                "MAX_URLS_PER_FILE=${SocialPublicSitemap.MAX_URLS_PER_FILE}) -- " +
                                "consider raising the shard ceiling."
                        }
                    }
                    val shardCount =
                        minOf(
                            SocialPublicSitemap.MAX_SHARDS.toLong(),
                            (totalCount + SocialPublicSitemap.MAX_URLS_PER_FILE - 1) / SocialPublicSitemap.MAX_URLS_PER_FILE,
                        ).toInt()
                    SocialPublicSitemap.renderSitemapIndex(baseUrl = baseUrl, shardCount = shardCount)
                }
            call.respondPublicCacheable(body = body, contentType = XML_CONTENT_TYPE, cacheControl = "public, max-age=3600")
        }
    }

    get("/sitemap-{shard}.xml") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            if (!sitemapRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val shard = call.parameters["shard"]?.toIntOrNull()
            if (shard == null || shard < 1 || shard > SocialPublicSitemap.MAX_SHARDS) {
                call.respondPublicNotFound(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            // M2-Fix (Review-Runde 1): this shard's entries are the ONLY thing loaded -- SQL-side
            // orderBy/limit/offset, never a slice of an in-memory list holding every shard's worth of
            // roots. An empty result means shard is past the actual data -> 404.
            val entries = transaction { SocialPublicSitemap.loadEntriesForShard(shard = shard) }
            if (entries.isEmpty()) {
                call.respondPublicNotFound(baseUrl = baseUrl)
                return@withPublicErrorHandling
            }
            val body = SocialPublicSitemap.renderUrlset(entries = entries, baseUrl = baseUrl)
            call.respondPublicCacheable(body = body, contentType = XML_CONTENT_TYPE, cacheControl = "public, max-age=3600")
        }
    }

    // Not rate-limited -- same "cheap, constant, no DB access" reasoning as the CSS asset above.
    //
    // Die Disallow-Liste ist aus den tatsächlich registrierten Routenpräfixen abgeleitet
    // (verifiziert gegen Application.kt: /api/auth/*, /api/backup/*, /api/documents/*,
    // /api/dsgvo/*, /api/mailmerge/*, /api/conference/*, /federation/* inkl. /federation/oidc/*,
    // /.well-known/*, /rpc/* (Kilua-RPC-Endpunkt, initRpc { } in Application.kt) -- VIER Präfixe
    // decken alles ab (G2-Fix, Review-Runde 1: /rpc/ fehlte hier bisher, obwohl die KDoc bereits
    // "verifiziert" behauptete). Bei jeder neuen Routenfamilie unter einem fünften Präfix MUSS diese
    // Liste mitgepflegt werden.
    get("/robots.txt") {
        call.withPublicErrorHandling(baseUrl = baseUrl) {
            val body =
                """
                User-agent: *
                Allow: /s
                Disallow: /api/
                Disallow: /federation/
                Disallow: /.well-known/
                Disallow: /rpc/
                Disallow: /s/*/report

                Sitemap: $baseUrl/sitemap.xml
                """.trimIndent() + "\n"
            call.respondPublicCacheable(
                body = body,
                contentType = ContentType.Text.Plain.withParameter("charset", "utf-8"),
                cacheControl = "public, max-age=86400",
            )
        }
    }
}

private val HTML_CONTENT_TYPE = ContentType.Text.Html.withParameter("charset", "utf-8")
private val XML_CONTENT_TYPE = ContentType.Application.Xml.withParameter("charset", "utf-8")

private sealed interface PostResolution {
    data class Found(
        val thread: SocialThreadDto,
    ) : PostResolution

    data class Redirect(
        val rootId: Uuid,
    ) : PostResolution

    /** Welle V1.1.5 (E-B). */
    data class LegallyRemoved(
        val view: PublicRemovalNoticeView,
    ) : PostResolution

    data object NotFound : PostResolution
}

/**
 * Welle V1.1.5 (E-B). Zweite, ENGE Abfrage für den Fall, dass die primäre Auflösung unter
 * [SocialVisibility.publicReadableCondition] leer blieb: war diese ID ein öffentlicher Beitrag, der
 * rechtlich entfernt wurde? Wenn nein -- und das ist der überwältigende Regelfall (unbekannte UUID,
 * nicht-öffentlicher Beitrag, vom Autor versteckter Beitrag) -- bleibt es beim 404.
 *
 * Läuft bewusst NICHT über `SocialReadPipeline.post`/`.thread` (Stolperfalle 23): beide geben für
 * eine nicht-`VISIBLE` Wurzel hart `null` zurück und laden zusätzlich den ganzen Thread inkl.
 * Gewichtsaggregation -- für eine Seite ohne jeden Inhalt und ohne jede Zahl ist beides falsch.
 * Eine einzelne Zeile, schlanke Projektion, keine Aggregation.
 */
private fun resolveRemovalNotice(postUuid: Uuid): PostResolution {
    val row =
        SocialPostTable
            .select(
                SocialPostTable.rootId,
                SocialPostTable.stateReason,
                SocialPostTable.stateChangedAt,
                SocialPostTable.publishedAt,
            ).where { (SocialPostTable.id eq postUuid) and SocialVisibility.publicRemovalNoticeCondition() }
            .singleOrNull() ?: return PostResolution.NotFound
    // Defense in depth, exakt das Muster des bestehenden G5-Fix: ein Nicht-Wurzel-Hinweis wird nur
    // ausgeliefert, wenn die WURZEL des Threads ebenfalls PUBLIC ist. Bewusst OHNE state-Bedingung --
    // die Wurzel darf selbst REMOVED_LEGAL sein (ganzer Thread entfernt).
    val rootId = row[SocialPostTable.rootId]
    if (rootId != postUuid) {
        val rootIsPublic =
            SocialPostTable
                .select(SocialPostTable.id)
                .where { (SocialPostTable.id eq rootId) and (SocialPostTable.visibility eq SocialPostVisibility.PUBLIC) }
                .firstOrNull() != null
        if (!rootIsPublic) return PostResolution.NotFound
    }
    val removedAt = row[SocialPostTable.stateChangedAt] ?: row[SocialPostTable.publishedAt]
    val reason = row[SocialPostTable.stateReason]?.takeIf { it.isNotBlank() } ?: LEGAL_REMOVAL_FALLBACK_REASON
    return PostResolution.LegallyRemoved(
        view =
            PublicRemovalNoticeView(
                postId = postUuid.toString(),
                reasonLines = reason.split("\n"),
                removedAtIso = removedAt.toString(),
                removedAtHuman = removedAt.toHumanDate(),
            ),
    )
}

/**
 * Review-Fund S1 (2026-08-18)-Muster, hier für den öffentlichen Pfad wiederholt: [now] minus
 * [SocialPostWeight.RANKING_HORIZON_DAYS], via `Instant`-Differenz gegen eine feste
 * [TimeZone.UTC]-Referenz -- dieselbe zwei-Zeilen-Berechnung wie `SocialNetworkService
 * .rankingHorizon`. Absichtlich hier dupliziert statt geteilt: es ist reine Datumsarithmetik, kein
 * Teil der Aggregations-Pipeline, deren Duplikation das eigentliche Risiko dieser Welle wäre (siehe
 * [SocialReadPipeline] KDoc).
 */
private fun rankingHorizon(now: LocalDateTime): LocalDateTime =
    (now.toInstant(TimeZone.UTC) - SocialPostWeight.RANKING_HORIZON_DAYS.days).toLocalDateTime(TimeZone.UTC)

/**
 * Security-Audit-Fund S-3 (2026-08-18): the canonical `/s` URL for [page] -- used by the
 * canonical-URL guard in `registerSocialPublicRoutes`'s `/s` handler to redirect away any unexpected
 * query parameter. Same shape as `SocialPublicHtml`'s own PRIVATE `timelineCanonicalUrl` (used there
 * for its `nav` pagination links) -- deliberately duplicated rather than shared across the two
 * files/visibility boundaries: this is a two-line, non-domain-logic URL-shape decision, not part of
 * the rendering/aggregation pipeline whose duplication would be this welle's actual risk (same
 * reasoning already used for [rankingHorizon] above).
 */
private fun timelineCanonicalUrl(
    baseUrl: String,
    page: Int,
): String = if (page <= 1) "$baseUrl/s" else "$baseUrl/s?page=$page"

/**
 * `raw` wird NIE werfend geparst (T16) -- jede ungültige/fehlende/außerhalb-des-Bereichs-Eingabe
 * wird stillschweigend auf den nächstgültigen Wert geklemmt, niemals ein 404/500. `?page=abc`,
 * `?page=1e9` -> `toIntOrNull()` liefert `null` -> Seite 1. `?page=0`/`?page=-1` -> `coerceIn` ->
 * Seite 1. `?page=999999` -> `coerceIn` -> [PUBLIC_MAX_PAGES].
 */
private fun parsePage(raw: String?): Int = (raw?.toIntOrNull() ?: 1).coerceIn(1, PUBLIC_MAX_PAGES)

/**
 * Security-Audit-Fund S2-3 (Runde 2, 2026-08-18): `true` iff the raw `page` query VALUE is not the
 * canonical representation of the already-clamped [page] -- see the call site's KDoc for why this is
 * needed IN ADDITION to [hasOnlyAllowedQueryParams] (which only ever looks at parameter NAMES, never
 * values, by design). Two cases:
 *
 * - [raw] is `null` (parameter absent entirely) -> never needs canonicalization, regardless of
 *   [page]. This is the only way page 1 is ever reached without a redirect.
 * - [raw] is present -> for page 1, ANY presence of `?page=...` is non-canonical (the canonical `/s`
 *   URL for page 1 carries no `page` parameter at all, see [timelineCanonicalUrl]); for page > 1, the
 *   raw string must be EXACTLY [page]'s decimal representation (`"2"`, not `"02"`/`"2.0"`/etc.) --
 *   anything else (leading zeros, an out-of-range value [parsePage] clamped, a non-numeric value
 *   [parsePage] defaulted, `"1e9"`, ...) redirects to the one true canonical URL for the page it
 *   resolves to. Never throws -- consistent with [parsePage]'s own "never werfend geparst" contract
 *   (T16): an invalid value canonicalizes via redirect, it never 404s/500s.
 */
private fun pageQueryValueNeedsCanonicalization(
    raw: String?,
    page: Int,
): Boolean {
    if (raw == null) return false
    return if (page <= 1) true else raw != page.toString()
}

private fun SocialPostDto.toPublicView(): PublicPostView =
    PublicPostView(
        id = id,
        depth = depth,
        authorDisplayName = authorDisplayName,
        contentLines = content.split("\n"),
        // Welle V1.1.5 -- das Flag, an dem der Renderer erkennt, dass `content` kein Nutzertext
        // mehr ist, sondern einer der beiden SocialContentTombstone-Marker.
        contentErased = contentErasedAt != null,
        totalWeightLtr = totalCurrentWeightLtr.toPlainString(),
        ownWeightLtr = ownCurrentWeightLtr.toPlainString(),
        publishedAtIso = publishedAt.toString(),
        publishedAtHuman = publishedAt.toHumanDate(),
    )

private fun SocialThreadDto.toPublicThreadView(): PublicThreadView {
    val views = nodes.map { it.toPublicView() }
    return PublicThreadView(root = views.first(), descendants = views.drop(1), truncated = truncated)
}

private fun LocalDateTime.toHumanDate(): String = "%02d.%02d.%04d".format(dayOfMonth, monthNumber, year)

/**
 * Security-Header für JEDE Antwort des öffentlichen Lesepfads. **Ausschließlich hier, niemals als
 * globales Plugin**: eine `default-src 'none'`-CSP über die gesamte Anwendung würde die
 * KVision-SPA (Inline-Bootstrap-Styles, `main.bundle.js`, Font-Awesome-Webfont) sofort zerlegen.
 * Diese Trennung ist Absicht und darf nicht "vereinheitlicht" werden.
 *
 * `style-src 'self'` (statt eines Inline-Erlaubnis-Schlüsselworts) ist möglich, WEIL das CSS unter
 * `/s/assets/style.css` ausgeliefert wird und nirgends inline steht -- das ist der eigentliche Grund
 * für die separate CSS-Route. **`form-action 'self'` seit Welle V1.1.5** (hochgezogen von `'none'`)
 * -- das Melde-Formular (`GET`/`POST /s/{id}/report`) ist ein klassisches HTML-`<form>` ohne
 * jedes JavaScript, `'self'` ist die engstmögliche Direktive, die es zulässt. Alle anderen
 * Direktiven bleiben unverändert, insbesondere `default-src 'none'` und `frame-ancestors 'none'`.
 * Kein HSTS hier -- das ist Sache des Reverse Proxy (Caddy setzt es bei automatischem HTTPS selbst).
 * Kein `Cross-Origin-Resource-Policy` -- für Top-Level-Navigation wirkungslos und ein potenzieller
 * Stolperstein für Social-Media-Vorschau-Scraper.
 */
private fun ApplicationCall.applyPublicPageHeaders() {
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    )
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Frame-Options", "DENY")
    response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
}

/** `MessageDigest.getInstance` ist NICHT thread-safe -- pro Aufruf neu instanziieren (Haus-Prüfliste, Präzedenzfall `FederationRoutes.kt`/`DocumentArchiving.kt`). */
private fun computeETag(body: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString(separator = "") { "%02x".format(it) }.take(32)
    return "W/\"$hex\""
}

/**
 * Weak-Vergleichssemantik (§ 5.1): auf beiden Seiten das optionale `W/`-Präfix und die
 * Anführungszeichen abstreifen, dann vergleichen; `If-None-Match: *` ⇒ immer Treffer; mehrere
 * komma-separierte Werte ⇒ Treffer, wenn einer passt.
 */
private fun ifNoneMatchHits(
    headerValue: String?,
    etag: String,
): Boolean {
    if (headerValue == null) return false
    if (headerValue.trim() == "*") return true
    val target = etag.removePrefix("W/").trim('"')
    return headerValue.split(",").map { it.trim() }.any { candidate -> candidate.removePrefix("W/").trim('"') == target }
}

private suspend fun ApplicationCall.respondPublicCacheable(
    body: String,
    contentType: ContentType,
    cacheControl: String,
) {
    val etag = computeETag(body = body)
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    response.header(HttpHeaders.CacheControl, cacheControl)
    applyPublicPageHeaders()
    if (ifNoneMatchHits(headerValue = request.headers[HttpHeaders.IfNoneMatch], etag = etag)) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondText(text = body, contentType = contentType)
    }
}

/** `no-store` (§ 5.2): verhindert, dass Caddy/ein CDN ein 404 festhält, das nach einer späteren Moderations-/DSGVO-Aktion nicht mehr gilt. */
private suspend fun ApplicationCall.respondPublicNotFound(baseUrl: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyPublicPageHeaders()
    respondText(text = SocialPublicHtml.notFoundPage(baseUrl = baseUrl), contentType = HTML_CONTENT_TYPE, status = HttpStatusCode.NotFound)
}

/**
 * Welle V1.1.5 (E-B). RFC 7725 -- die Ressource existiert, ist aber aus rechtlichen Gründen nicht
 * auslieferbar, und der Antwortkörper trägt die Erklärung. Ktor hat keine `HttpStatusCode`-Konstante
 * dafür; konstruiert wie das bestehende 308 in [respondPublicCanonicalRedirect].
 *
 * `no-store` aus demselben Grund wie [respondPublicNotFound]: der Zustand darf sich jederzeit ändern
 * (Korrektur der Begründung, spätere vollständige Löschung), und ein CDN, das diese Antwort
 * festhält, würde die Änderung überdauern. **KEIN ETag** -- niemals über [respondPublicCacheable]
 * (Stolperfalle 22): ein Besucher, der noch den ETag der alten, inhaltstragenden 200-Antwort im
 * `If-None-Match` schickt, kann strukturell kein 304 bekommen -- die Anfrage erreicht
 * [respondPublicCacheable] gar nicht mehr.
 *
 * Kein `Link: <…>; rel="blocked-by"`-Header (RFC 7725 § 3) -- dieser Header benennt die blockierende
 * Instanz; hier ist das der Betreiber selbst, der auf der Seite ohnehin namentlich steht. Ein
 * Selbstverweis wäre reines Rauschen.
 */
private suspend fun ApplicationCall.respondPublicLegallyRemoved(
    view: PublicRemovalNoticeView,
    baseUrl: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyPublicPageHeaders()
    respondText(
        text = SocialPublicHtml.legallyRemovedPage(view = view, baseUrl = baseUrl),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode(451, "Unavailable For Legal Reasons"),
    )
}

/**
 * Security-Audit-Fund MAJOR-1 (Runde 1, 2026-08-19): `true` iff [contentLength] should cause the
 * request to be rejected BEFORE `receiveParameters()` buffers anything -- either it exceeds
 * [REPORT_MAX_BODY_BYTES], or it is `null` (no `Content-Length` header at all, e.g. chunked
 * transfer encoding), which would otherwise bypass a length check entirely. `internal` so
 * `SocialPublicRoutesTest` can unit-test the boundary directly, without needing to fabricate a
 * real chunked-encoded HTTP request through the Ktor test client to exercise the `null` branch.
 */
internal fun reportBodyExceedsLimit(contentLength: Long?): Boolean = contentLength == null || contentLength > REPORT_MAX_BODY_BYTES

/**
 * MAJOR-1/MINOR-3 (Security-Audit Runde 1, 2026-08-19): shared generic responder for a malformed
 * request `POST /s/{id}/report` rejects BEFORE touching the domain -- oversized body, missing
 * `Content-Length`, wrong `Content-Type`. Deliberately the SAME fixed body text for every one of
 * these reasons (never distinguishing which check failed) -- same "do not leak internals to an
 * anonymous caller" discipline as [respondPublicServerError]. `no-store` for the same reason as
 * [respondPublicNotFound] -- nothing here should ever be cached by a proxy/CDN.
 */
private suspend fun ApplicationCall.respondPublicMalformedRequest(
    baseUrl: String,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyPublicPageHeaders()
    respondText(text = SocialPublicHtml.malformedRequestPage(baseUrl = baseUrl), contentType = HTML_CONTENT_TYPE, status = status)
}

/** `no-store` + `Retry-After` (§ 5.2): ein 429, das nur eine IP betraf, darf nicht für andere Leser gecacht werden. */
private suspend fun ApplicationCall.respondPublicTooManyRequests(baseUrl: String) {
    response.header(HttpHeaders.RetryAfter, "60")
    response.header(HttpHeaders.CacheControl, "no-store")
    applyPublicPageHeaders()
    respondText(
        text = SocialPublicHtml.tooManyRequestsPage(baseUrl = baseUrl),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.TooManyRequests,
    )
}

/** 308 (nicht 301): erhält die Methode, relevant für HEAD (siehe Implementierungsplan § 2.1). Kein Anker -- Fragmente werden ohnehin nicht an den Server geschickt. */
private suspend fun ApplicationCall.respondPublicRedirect(
    baseUrl: String,
    rootId: Uuid,
) {
    respondPublicCanonicalRedirect(canonicalUrl = "$baseUrl/s/$rootId")
}

/**
 * Security-Audit-Fund S-3 (2026-08-18): shared 308-redirect responder, used both by
 * [respondPublicRedirect] (K4's comment-to-root redirect) and by the canonical-URL guards in
 * [registerSocialPublicRoutes] (`/s`/`/s/{id}`'s unexpected-query-parameter redirect). Same
 * `Cache-Control: public, max-age=3600` and security headers on every 308 this file emits, whatever
 * triggered it.
 */
private suspend fun ApplicationCall.respondPublicCanonicalRedirect(canonicalUrl: String) {
    response.header(HttpHeaders.Location, canonicalUrl)
    response.header(HttpHeaders.CacheControl, "public, max-age=3600")
    applyPublicPageHeaders()
    respond(HttpStatusCode(308, "Permanent Redirect"))
}

/**
 * Security-Audit-Fund S-3 (2026-08-18): `true` iff every query parameter NAME present on this
 * request is contained in [allowed] -- VALUES are deliberately ignored (T16's `?page=2&page=3`
 * duplicate-value case must keep resolving normally, only an unexpected parameter NAME triggers
 * canonicalization). Used to strip tracking-/cache-busting-style query parameters (`?x=<random>`)
 * BEFORE any DB work happens: without this guard, a proxy/CDN's `Cache-Control: public,
 * max-age=300` on the canonical URL could be trivially defeated by varying an unknown parameter,
 * with every variant paying the origin's full render cost (see class KDoc "Ablauf pro Handler").
 */
private fun ApplicationCall.hasOnlyAllowedQueryParams(allowed: Set<String>): Boolean = request.queryParameters.names().all { it in allowed }

/**
 * `no-store` (§ 5.2, M1-Fix Review-Runde 1): an unexpected 500 must never be cached by a proxy/CDN --
 * it may no longer be true moments later (e.g. after a retry, or after whatever transient condition
 * caused it clears).
 *
 * `internal` (not `private`, N1-Fix Review-Runde 2) so `SocialPublicRoutesTest` can exercise
 * [withPublicErrorHandling] -- and therefore this function -- directly against a minimal test route,
 * without needing a real handler failure deep inside [registerSocialPublicRoutes] to trigger it.
 */
internal suspend fun ApplicationCall.respondPublicServerError(baseUrl: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyPublicPageHeaders()
    respondText(
        text = SocialPublicHtml.serverErrorPage(baseUrl = baseUrl),
        contentType = HTML_CONTENT_TYPE,
        status = HttpStatusCode.InternalServerError,
    )
}

/**
 * M1-Fix (Review-Runde 1): wraps a SINGLE route handler's ENTIRE body so that literally no
 * exception -- expected or truly unforeseen -- can ever escape into Ktor's default handling, which
 * would respond with a bare 500 carrying NONE of this file's security headers and NO
 * `Cache-Control: no-store`, letting a reverse proxy/CDN potentially cache that broken response.
 * `StatusPages` (`Application.kt`) does not help here: it maps only `UnauthenticatedException`/
 * `ForbiddenException`, both irrelevant to a route family that requires no authentication and never
 * throws either of them.
 *
 * [CancellationException] is deliberately RETHROWN, never turned into a 500 -- it is how structured
 * concurrency cancels an in-flight request (e.g. the client disconnecting mid-response) and MUST
 * keep propagating; the same rethrow-before-catch-broadly idiom already used in
 * `SecretBallotStreamGuard.kt`. Every OTHER [Exception] is logged (never with the raw exception
 * message forwarded to the client -- see [SocialPublicHtml.serverErrorPage], a fixed, generic
 * string) and turned into a security-header-complete, `no-store` 500 via
 * [respondPublicServerError].
 *
 * **N5-Fix (Review-Runde 2)**: [respondPublicServerError] itself is wrapped in `runCatching` -- a
 * normal handler failure can never escape past this function, but writing the error response back to
 * the client is a SEPARATE I/O operation that can fail on its own (e.g. a broken pipe if the client
 * already disconnected). Should that secondary write fail, the resulting exception is swallowed here
 * rather than propagating into Ktor's unprotected default handling -- the one case this function's
 * guarantee cannot cover is a JVM-level [Error] (e.g. `StackOverflowError`), which is not caught by
 * `catch (e: Exception)` in the first place and is out of scope for the same reason those always are.
 *
 * **Security-Audit-Fund S-1 (2026-08-18) -- decision: `catch (e: Exception)` stays as-is, NOT
 * widened to `catch (e: Throwable)`.** The finding's underlying scenario was an anonymous
 * `OutOfMemoryError` escaping this handler unprotected (no security headers, no `Cache-Control:
 * no-store`) via a crafted, huge thread body. Two options were weighed:
 *   1. Widen the catch clause to `Throwable` so an `OutOfMemoryError`/`StackOverflowError` at least
 *      gets a best-effort, header-complete 500 instead of Ktor's bare default.
 *   2. Fix the ROOT CAUSE so the render can no longer produce an unbounded body in the first place
 *      (`SocialPublicHtml.THREAD_DESCENDANTS_BYTE_BUDGET` + `SocialReadPipeline.SocialReadCaps
 *      .PUBLIC.threadMaxNodes` lowered to 300, both landed alongside this fix).
 * Option 2 is the one actually implemented; option 1 was deliberately NOT taken, for two reasons.
 * First, it no longer needs to be: with the body size hard-capped, this handler cannot legitimately
 * allocate the tens-of-MB working set that produced the `OutOfMemoryError` in the first place --
 * the attack this finding described no longer reaches an OOM condition here at all. Second, `catch
 * (e: Throwable)` is a known anti-pattern specifically for `OutOfMemoryError`/`StackOverflowError`:
 * both indicate the JVM (or at least this thread's stack/the heap) was ALREADY in an unreliable
 * state at the moment of the throw -- the catch block's own subsequent work (`logger.error`, string
 * formatting for the response body, another heap allocation for the response) can itself throw the
 * same `Error` again, or mask a condition that genuinely warrants the request/connection dying
 * rather than limping on. Preventing the resource exhaustion at its source (this fix) is strictly
 * safer than attempting to recover gracefully AFTER the JVM is already in that state -- so the
 * pre-existing "this is out of scope" documentation above remains accurate and is intentionally left
 * unchanged.
 *
 * `internal` (not `private`, N1-Fix Review-Runde 2) so `SocialPublicRoutesTest` can register a
 * throwing test route and drive this function directly, closing the test gap Review-Runde 2 found:
 * this is the security-critical new code of the whole welle and had ZERO direct test coverage.
 */
internal suspend fun ApplicationCall.withPublicErrorHandling(
    baseUrl: String,
    handler: suspend () -> Unit,
) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Unhandled exception in a public social read handler (${request.uri})" }
        runCatching { respondPublicServerError(baseUrl = baseUrl) }
    }
}

/**
 * Rate-Limit-Schlüssel aus [remoteHost]. IPv4 (und jede nicht als IPv6 erkennbare Eingabe) bleibt
 * unverändert; eine IPv6-Adresse wird auf ihr /64-Präfix reduziert, weil ein einzelner
 * Endkunden-Anschluss dort routinemäßig ein ganzes /64 besitzt -- pro Einzeladresse zu limitieren
 * hieße, für IPv6 gar nicht zu limitieren (X6). Wirft NIE (unparsebare Eingabe -> Rohstring als
 * Schlüssel) und löst NIE eine DNS-Auflösung aus: dieser Parser ist reine String-/Array-Arbeit ohne
 * [java.net.InetAddress]-Aufruf. Zone-Id (`%eth0`) wird IMMER vorher abgeschnitten -- sowohl auf dem
 * erfolgreichen IPv6-Pfad als auch im Fallback-Zweig (G6-Fix, Review-Runde 1: der Fallback baute den
 * Schlüssel vorher aus dem ORIGINAL-[remoteHost] inklusive Zone-Id, im Widerspruch zu genau dieser
 * KDoc-Aussage -- zwei Requests, die sich nur in der Zone-Id unterscheiden, aber sonst identische,
 * nicht als IPv6-Literal parsebare Eingaben liefern, landeten so in getrennten Buckets).
 */
internal fun rateLimitKeyFor(remoteHost: String): String {
    val withoutZone = remoteHost.substringBefore('%')
    val prefix = ipv6Slash64Prefix(address = withoutZone)
    return if (prefix != null) "ip6:$prefix" else "ip:$withoutZone"
}

/**
 * Parst [address] als IPv6-Literal (Standard- und `::`-komprimierte Notation) und liefert die
 * ersten vier Hextets (die /64-Netz-ID) als kleingeschriebenen, führende-Nullen-freien String --
 * oder `null`, wenn [address] kein wohlgeformtes IPv6-Literal ist (IPv4, Hostname, IPv4-eingebettete
 * Form wie `::ffff:192.0.2.1`, oder Unsinn). Der IPv4-eingebettete Fall wird bewusst als `null`
 * (nicht als Sonderfall geparst) behandelt -- ein für diesen seltenen Fall etwas gröberes
 * Rate-Limiting (Fallback auf den vollen `remoteHost`-String als Schlüssel) ist ein akzeptabler,
 * dokumentierter Kompromiss gegenüber einem zweiten Parsing-Pfad.
 */
private fun ipv6Slash64Prefix(address: String): String? {
    if (!address.contains(':')) return null
    val parts = address.split("::", limit = 3)
    if (parts.size > 2) return null
    val head = parts[0].split(':').filter { it.isNotEmpty() }
    val tail = if (parts.size == 2) parts[1].split(':').filter { it.isNotEmpty() } else emptyList()
    if (head.any { !isHextet(it) } || tail.any { !isHextet(it) }) return null
    val hasCompression = parts.size == 2
    if (!hasCompression && head.size != 8) return null
    val missing = 8 - (head.size + tail.size)
    if (hasCompression && missing < 0) return null
    val groups = head + List(if (hasCompression) missing else 0) { "0" } + tail
    if (groups.size != 8) return null
    return groups.take(4).joinToString(separator = ":") { hextet -> normalizeHextet(hextet) }
}

private fun isHextet(value: String): Boolean =
    value.isNotEmpty() && value.length <= 4 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

private fun normalizeHextet(value: String): String = value.lowercase().trimStart('0').ifEmpty { "0" }
