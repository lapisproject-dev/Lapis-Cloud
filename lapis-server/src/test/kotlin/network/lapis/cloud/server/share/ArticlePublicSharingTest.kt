package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.eventTestEmbedConfig
import network.lapis.cloud.server.routes.eventTestNav
import network.lapis.cloud.server.routes.registerArticlePublicRoutes
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jsoup.Jsoup
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ARTICLE_BASE = "https://lapis.example"
private const val ARTICLE_CSP_PLAIN =
    "default-src 'none'; img-src 'self' data:; style-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
private const val ARTICLE_CSP_SCRIPT =
    "default-src 'none'; img-src 'self' data:; style-src 'self'; font-src 'self'; script-src 'self'; " +
        "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

class ArticlePublicSharingTest :
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
            status: ArticleStatus = ArticleStatus.PUBLISHED,
            title: String = "Teilbarer Artikel",
        ): String {
            val author = Uuid.random()
            val id = Uuid.random()
            val slug = "article-share-$id"
            val now = DbClock.nowLocalDateTime()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = author
                    it[displayName] = "ArticlePublicSharingTest Autor"
                    it[email] = "article-share-$author@example.org"
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = slug
                    it[ArticleTable.title] = title
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt"
                    it[coverImageId] = null
                    it[authorId] = author
                    it[ArticleTable.status] = status
                    it[submittedAt] = now
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[rejectionReason] = null
                    it[publishedAt] = if (status == ArticleStatus.PUBLISHED) LocalDateTime(2030, 9, 28, 10, 0) else null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            memberIds += author
            articleIds += id
            return slug
        }

        fun limiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun withApp(
            share: ShareConfig,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                install(XForwardedHeaders) { useLastProxy() }
                routing {
                    registerArticlePublicRoutes(
                        baseUrl = ARTICLE_BASE,
                        branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                        navAvailability = eventTestNav(),
                        embedConfig = eventTestEmbedConfig(),
                        coverStorage = EventCoverStorage(createTempDirectory("article-share-test").toFile()),
                        pageRateLimiter = limiter(),
                        coverReadRateLimiter = limiter(),
                        shareConfig = share,
                    )
                }
            }
            block()
        }

        val enabled = ShareConfig.load(baseUrl = ARTICLE_BASE, env = { null })

        test("STANDARD: bar, og:url equals canonical, widened CSP") {
            val slug = createArticle()
            withApp(enabled) {
                val response = client.get("/aktuelles/$slug")
                response.headers["Content-Security-Policy"] shouldBe ARTICLE_CSP_SCRIPT
                val doc = Jsoup.parse(response.bodyAsText())
                doc.select("section.share").size shouldBe 1
                doc.select("script[src=/s/assets/share.js][defer]").size shouldBe 1
                val canonical = doc.select("link[rel=canonical]").single().attr("href")
                canonical shouldBe "$ARTICLE_BASE/aktuelles/$slug"
                doc.select("meta[property=og:url]").single().attr("content") shouldBe canonical
                doc.select("meta[name=twitter:card]").single().attr("content") shouldBe "summary"
                doc.select("div[lang=de] section.share").size shouldBe 0
            }
        }

        test("DISABLED: no bar, no script, old CSP") {
            val slug = createArticle()
            withApp(ShareConfig.DISABLED) {
                val response = client.get("/aktuelles/$slug")
                response.headers["Content-Security-Policy"] shouldBe ARTICLE_CSP_PLAIN
                val html = response.bodyAsText()
                html shouldNotContain "class=\"share\""
                html shouldNotContain "share.js"
            }
        }

        test("EMBED: no bar, old embed CSP, og:url is the language-less canonical") {
            val slug = createArticle()
            withApp(enabled) {
                val response = client.get("/aktuelles/$slug?embed=1")
                response.headers["Content-Security-Policy"] shouldBe ARTICLE_CSP_PLAIN.replace("img-src 'self' data:", "img-src 'self'")
                val doc = Jsoup.parse(response.bodyAsText())
                doc.select("section.share").size shouldBe 0
                doc.select("meta[property=og:url]").single().attr("content") shouldBe "$ARTICLE_BASE/aktuelles/$slug"
            }
        }

        test("draft, rejected and unknown slugs are a 404 without bar") {
            val draft = createArticle(status = ArticleStatus.DRAFT)
            withApp(enabled) {
                for (path in listOf("/aktuelles/$draft", "/aktuelles/gibt-es-nicht")) {
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.NotFound
                    response.headers["Content-Security-Policy"] shouldBe ARTICLE_CSP_PLAIN
                    response.bodyAsText() shouldNotContain "class=\"share\""
                }
            }
        }

        test("a spoofed Host never reaches a share URL") {
            val slug = createArticle()
            withApp(enabled) {
                val html =
                    client
                        .get("/aktuelles/$slug") {
                            header(HttpHeaders.Host, "evil.example")
                            header("X-Forwarded-Host", "evil.example")
                        }.bodyAsText()
                html shouldNotContain "evil.example"
            }
        }
    })
