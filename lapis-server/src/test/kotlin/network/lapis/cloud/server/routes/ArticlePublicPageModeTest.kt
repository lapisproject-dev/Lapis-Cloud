package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.130 -- the STANDARD/EMBED presentation of `GET /aktuelles/{slug}`: shell, headers, title/lead rule, meta line, SEO, error pages. */
class ArticlePublicPageModeTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val articleIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (articleIds.isNotEmpty()) ArticleTable.deleteWhere { id inList articleIds }
                if (memberIds.isNotEmpty()) MemberTable.deleteWhere { id inList memberIds }
            }
        }

        fun createArticle(
            title: String = "Mein Artikel",
            excerpt: String = "Ein Auszug",
            body: String = "Der Textbeginn.\n\nMehr Inhalt.",
            status: ArticleStatus = ArticleStatus.PUBLISHED,
            coverImageId: Uuid? = null,
            byline: String? = null,
        ): String? {
            val author = Uuid.random()
            val id = Uuid.random()
            val slug = if (status == ArticleStatus.PUBLISHED) "page-mode-test-$id" else null
            val now = DbClock.nowLocalDateTime()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = author
                    it[displayName] = "ArticlePublicPageModeTest Mitglied"
                    it[email] = "article-page-mode-$author@example.org"
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = slug
                    it[ArticleTable.title] = title
                    it[ArticleTable.excerpt] = excerpt
                    it[ArticleTable.body] = body
                    it[ArticleTable.coverImageId] = coverImageId
                    it[authorId] = author
                    it[ArticleTable.status] = status
                    it[submittedAt] = now
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[rejectionReason] = null
                    it[publishedAt] = if (status == ArticleStatus.PUBLISHED) LocalDateTime(2030, 9, 28, 10, 0) else null
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ArticleTable.byline] = byline
                    it[bylineKind] = if (byline != null) ArticleBylineKind.IMPORTED else null
                }
            }
            memberIds += author
            articleIds += id
            return slug
        }

        fun limiter(maxRequests: Int = 10_000) = FederationInboxRateLimiter(maxRequests = maxRequests, window = 1.minutes)

        suspend fun withApp(
            embedConfig: EmbedConfig = eventTestEmbedConfig(),
            pageLimiter: FederationInboxRateLimiter = limiter(),
            extraRoutes: io.ktor.server.routing.Route.() -> Unit = {},
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerArticlePublicRoutes(
                            baseUrl = "https://lapis.example",
                            branding = ResolvedBranding(title = "Testverein", logoAvailable = false, logoPath = null, websiteUrl = null),
                            navAvailability = eventTestNav(),
                            embedConfig = embedConfig,
                            coverStorage = EventCoverStorage(createTempDirectory("article-page-mode").toFile()),
                            pageRateLimiter = pageLimiter,
                            coverReadRateLimiter = limiter(),
                        )
                        extraRoutes()
                    }
                }
                block()
            }
        }

        fun count(
            haystack: String,
            needle: String,
        ) = haystack.windowed(needle.length).count { it == needle }

        test("STANDARD: chrome + footer, wide body class, no embed shell, lang attribute follows ?lang") {
            val slug = createArticle()
            withApp {
                val html = client.get("/aktuelles/$slug").bodyAsText()
                html shouldContain "class=\"chrome\""
                html shouldContain "has-chrome article-wide"
                html shouldNotContain "embed-page"
                html shouldContain "<html lang=\"de\">"
                val en = client.get("/aktuelles/$slug?lang=en").bodyAsText()
                en shouldContain "<html lang=\"en\">"
                en shouldContain "<div lang=\"de\">"
            }
        }

        test("EMBED: no chrome, slim shell, noindex, canonical without lang, one h1, no brand line") {
            val slug = createArticle()
            withApp {
                val html = client.get("/aktuelles/$slug?embed=1").bodyAsText()
                html shouldNotContain "class=\"chrome\""
                html shouldContain "embed-page article-embed"
                html shouldContain "<meta name=\"robots\" content=\"noindex\">"
                html shouldContain "<link href=\"https://lapis.example/aktuelles/$slug\" rel=\"canonical\">"
                html shouldNotContain "hreflang"
                count(haystack = html, needle = "<h1") shouldBe 1
            }
        }

        test("foreign embed values fall back to STANDARD and are never reflected") {
            val slug = createArticle()
            withApp {
                for (q in listOf("embed=0", "embed=true", "embed=1&embed=1", "embed=%3Cscript%3E", "embed=1x")) {
                    val html = client.get("/aktuelles/$slug?$q").bodyAsText()
                    withClue(q) {
                        html shouldNotContain "embed-page"
                        html shouldContain "has-chrome article-wide"
                        html shouldNotContain "<script"
                        html shouldNotContain "%3Cscript"
                        html shouldNotContain "embed="
                    }
                }
            }
        }

        fun HttpResponse.csp() = headers["Content-Security-Policy"]

        test("frame headers: STANDARD never frameable; EMBED only with an enabled allowlist (page, 404, 429, 500)") {
            val slug = createArticle()
            val enabled = eventTestEmbedConfigEnabled("https://partner.example")
            withApp(
                embedConfig = enabled,
                pageLimiter = limiter(maxRequests = 3),
                extraRoutes = {
                    get("/test-500") {
                        val ctx =
                            call.articlePageContext(
                                baseUrl = "https://lapis.example",
                                branding = eventTestBranding(),
                                navAvailability = eventTestNav(),
                                frameAncestors = embedFrameAncestors(enabled),
                            )
                        call.withArticlePublicErrorHandling(ctx = ctx) { error("boom") }
                    }
                },
            ) {
                fun HttpResponse.assertNone() {
                    csp() shouldContain "frame-ancestors 'none'"
                    headers["X-Frame-Options"] shouldBe "DENY"
                }

                fun HttpResponse.assertAllowed() {
                    csp() shouldContain "frame-ancestors 'self' https://partner.example"
                    headers["X-Frame-Options"] shouldBe null
                }
                client.get("/aktuelles/$slug").assertNone()
                client.get("/aktuelles/$slug?embed=1").assertAllowed()
                client.get("/aktuelles/unknown-slug?embed=1").assertAllowed()
                client.get("/aktuelles/unknown-slug").assertNone()
                val limited = client.get("/aktuelles/$slug?embed=1")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.assertAllowed()
                client.get("/test-500").assertNone()
                val failed = client.get("/test-500?embed=1")
                failed.status shouldBe HttpStatusCode.InternalServerError
                failed.assertAllowed()
                failed.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
            withApp {
                client.get("/aktuelles/$slug?embed=1").assertNoneDisabled()
            }
        }

        test("CSP: exact strings per mode, Permissions-Policy, no-store") {
            val slug = createArticle()
            withApp(embedConfig = eventTestEmbedConfigEnabled("https://partner.example")) {
                val standard = client.get("/aktuelles/$slug")
                standard.csp() shouldBe
                    "default-src 'none'; img-src 'self' data:; style-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
                val embed = client.get("/aktuelles/$slug?embed=1")
                embed.csp() shouldBe
                    "default-src 'none'; img-src 'self'; style-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; " +
                    "frame-ancestors 'self' https://partner.example"
                for (r in listOf(standard, embed)) {
                    r.headers["Permissions-Policy"] shouldBe "geolocation=(), microphone=(), camera=()"
                    r.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    r.headers["X-Content-Type-Options"] shouldBe "nosniff"
                }
            }
        }

        test("the title appears exactly once as h1; <title> is 'Titel – Marke'") {
            val slug = createArticle(title = "Mein Artikel")
            withApp {
                for (q in listOf("", "?embed=1")) {
                    val html = client.get("/aktuelles/$slug$q").bodyAsText()
                    count(haystack = html, needle = "<h1") shouldBe 1
                    html shouldNotContain "<h2>Mein Artikel</h2>"
                    html shouldContain "<title>Mein Artikel – Testverein</title>"
                }
            }
        }

        test("lead: shown when the excerpt differs from the body start, suppressed when the body already starts with it") {
            val different = createArticle(excerpt = "Ein eigener Auszug", body = "Voellig anderer Anfang.")
            val same = createArticle(excerpt = "Es droht ein harter Aufprall.", body = "**Es droht ein harter Aufprall.**\n\nText")
            val truncated = createArticle(excerpt = "Es droht ein harter Aufprall...", body = "Es droht ein harter Aufprall auf dem Boden.")
            withApp {
                for (q in listOf("", "?embed=1")) {
                    withClue(q) {
                        count(haystack = client.get("/aktuelles/$different$q").bodyAsText(), needle = "lapis-article-lead") shouldBe 1
                        val sameHtml = client.get("/aktuelles/$same$q").bodyAsText()
                        sameHtml shouldNotContain "lapis-article-lead"
                        count(haystack = sameHtml, needle = "Es droht ein harter Aufprall.") shouldBe 2 // og:description + body, no lead
                        client.get("/aktuelles/$truncated$q").bodyAsText() shouldNotContain "lapis-article-lead"
                    }
                }
            }
        }

        test("date is a <time> element; byline and no 'Veröffentlicht am'") {
            val withByline = createArticle(byline = "Max Muster")
            val without = createArticle()
            withApp {
                val html = client.get("/aktuelles/$withByline").bodyAsText()
                html shouldContain "<time datetime=\"2030-09-28T"
                html shouldContain "28. September 2030</time>"
                html shouldContain "<span class=\"article-byline\">Von Max Muster</span> · <time"
                html shouldNotContain "Veröffentlicht am"
                val plain = client.get("/aktuelles/$without?embed=1").bodyAsText()
                plain shouldNotContain "article-byline"
                plain shouldNotContain "Von "
            }
        }

        test("cover: decorative (alt=\"\"), same-origin src; no img without a cover") {
            val coverId = Uuid.random()
            val withCover = createArticle(coverImageId = coverId)
            val without = createArticle()
            withApp {
                for (q in listOf("", "?embed=1")) {
                    val html = client.get("/aktuelles/$withCover$q").bodyAsText()
                    html shouldContain "alt=\"\""
                    html shouldContain "class=\"lapis-article-cover\""
                    html shouldContain "/aktuelles/$withCover/bild?v=${coverId.toString().take(8)}"
                }
                val plain = client.get("/aktuelles/$without").bodyAsText().substringAfter("<main")
                plain shouldNotContain "<img"
            }
        }

        test("SEO: og block unchanged, canonical with lang, hreflang only in STANDARD") {
            val slug = createArticle(title = "Mein Artikel", excerpt = "Ein Auszug")
            withApp {
                val html = client.get("/aktuelles/$slug").bodyAsText()
                html shouldContain
                    "<meta property=\"og:title\" content=\"Mein Artikel\">" +
                    "<meta property=\"og:description\" content=\"Ein Auszug\">" +
                    "<meta property=\"og:type\" content=\"article\">" +
                    "<meta property=\"article:published_time\" content=\""
                html shouldNotContain "og:image"
                html shouldContain "<link href=\"https://lapis.example/aktuelles/$slug\" rel=\"canonical\">"
                html shouldContain "hreflang=\"en\""
                client.get("/aktuelles/$slug?lang=en").bodyAsText() shouldContain
                    "<link href=\"https://lapis.example/aktuelles/$slug?lang=en\" rel=\"canonical\">"
            }
        }

        test("links: STANDARD back link on the same origin; EMBED links outside the body all target _top") {
            val slug = createArticle()
            withApp {
                client.get("/aktuelles/$slug?lang=en").bodyAsText() shouldContain
                    "href=\"https://lapis.example/aktuelles?lang=en\">← Alle Neuigkeiten"
                val embed = client.get("/aktuelles/$slug?embed=1").bodyAsText()
                val outside = embed.substringBefore("<article").plus(embed.substringAfter("</article>"))
                val anchors = Regex("<a [^>]*>").findAll(outside).map { it.value }.toList()
                (anchors.size >= 4) shouldBe true
                anchors.forEach { it shouldContain "target=\"_top\"" }
            }
        }

        test("error pages: shell per mode, 404 byte-identical across slugs and without the slug") {
            withApp(pageLimiter = limiter(maxRequests = 4)) {
                for (q in listOf("", "?embed=1")) {
                    val a = client.get("/aktuelles/does-not-exist-1$q")
                    val b = client.get("/aktuelles/Bad_Slug!$q")
                    a.status shouldBe HttpStatusCode.NotFound
                    a.bodyAsText() shouldBe b.bodyAsText()
                    a.bodyAsText() shouldContain "Nicht gefunden"
                    a.bodyAsText() shouldNotContain "does-not-exist-1"
                    a.csp() shouldBe b.csp()
                }
            }
            withApp(pageLimiter = limiter(maxRequests = 1)) {
                client.get("/aktuelles/x-1")
                val limited = client.get("/aktuelles/x-1")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.bodyAsText() shouldContain "Zu viele Anfragen"
                limited.bodyAsText() shouldContain "class=\"chrome\""
                limited.csp() shouldContain "default-src 'none'"
                limited.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
        }

        test("XSS: script and javascript: link in the body are inert in both modes") {
            val slug = createArticle(body = "Text <script>alert(1)</script> [Link](javascript:alert(2))")
            withApp {
                for (q in listOf("", "?embed=1")) {
                    val html = client.get("/aktuelles/$slug$q").bodyAsText()
                    html shouldNotContain "<script>alert(1)</script>"
                    html shouldNotContain "javascript:alert(2)"
                }
            }
        }

        test("event header core is unchanged: applyEventPageHeaders == applyPageModeHeaders for all four combinations") {
            testApplication {
                application {
                    routing {
                        for (embed in listOf(false, true)) {
                            for (ancestors in listOf(emptyList(), listOf("https://partner.example"))) {
                                val key = "$embed-${ancestors.size}"
                                get("/old-$key") {
                                    call.applyEventPageHeaders(ctx = eventTestContext(embed = embed, frameAncestors = ancestors))
                                    call.respondTextOk()
                                }
                                get("/new-$key") {
                                    call.applyPageModeHeaders(isEmbed = embed, frameAncestors = ancestors)
                                    call.respondTextOk()
                                }
                            }
                        }
                    }
                }
                val names =
                    listOf("Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options", "Permissions-Policy")
                for (key in listOf("false-0", "false-1", "true-0", "true-1")) {
                    val old = client.get("/old-$key")
                    val new = client.get("/new-$key")
                    withClue(key) { names.forEach { old.headers[it] shouldBe new.headers[it] } }
                }
                client.get("/old-true-0").csp() shouldBe
                    "default-src 'none'; img-src 'self'; style-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
            }
        }

        test("render-level: ArticleMarkdown output is the only unsafe content (smoke)") {
            val html = ArticlePublicHtml.articlePage(ctx = articleTestContext(), view = viewOf("<p>x</p>"))
            html shouldContain "<article class=\"lapis-article-body\"><p>x</p></article>"
            ArticleMarkdown.render(markdown = "a") shouldContain "a"
        }

        test("V1.9.140: an own absolute link leaves the frame (_top) in EMBED, but opens a new window (_blank) in STANDARD") {
            val slug =
                createArticle(
                    body = "[eigen](https://lapis.example/impressum) und [fremd](https://other.example/x)",
                )
            withApp {
                val embed = client.get("/aktuelles/$slug?embed=1").bodyAsText()
                embed shouldContain "href=\"https://lapis.example/impressum\" target=\"_top\""
                embed shouldContain "href=\"https://other.example/x\" target=\"_blank\""
                val standard = client.get("/aktuelles/$slug").bodyAsText()
                standard shouldContain "href=\"https://lapis.example/impressum\" target=\"_blank\""
                standard shouldNotContain "href=\"https://lapis.example/impressum\" target=\"_top\""
            }
        }
    })

private fun HttpResponse.assertNoneDisabled() {
    headers["Content-Security-Policy"]!! shouldContain "frame-ancestors 'none'"
    headers["X-Frame-Options"] shouldBe "DENY"
}

private suspend fun io.ktor.server.application.ApplicationCall.respondTextOk() {
    respondText("ok")
}

private fun viewOf(bodyHtml: String) =
    ArticlePublicHtml.View(
        title = "T",
        slug = "t",
        excerpt = "E",
        renderedBodyHtml = bodyHtml,
        coverImageUrl = null,
        publishedAt = LocalDateTime(2030, 9, 28, 10, 0),
        publishedAtIso = "2030-09-28T10:00:00Z",
    )
