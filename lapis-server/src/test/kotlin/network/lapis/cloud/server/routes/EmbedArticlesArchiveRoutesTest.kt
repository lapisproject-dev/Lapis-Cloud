package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.105 -- `GET /api/embed/v1/articles/archive` and its `OPTIONS` preflight: strict parameters (invalid -> 400, never clamped),
 * `hasMore` from a `limit + 1` read, a stable order on equal `published_at`, an OWN rate limiter that never uses up the main feed's budget.
 *
 * The articles of this spec sit in the year 2093 so that they are always the newest rows of the shared test database, whatever other specs
 * left behind: page 1 of a small limit then consists of exactly these rows.
 */
class EmbedArticlesArchiveRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) ArticleTable.deleteWhere { id inList createdArticleIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EmbedArticlesArchiveRoutesTest Mitglied"
                    it[email] = "embed-articles-archive-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        /** Returns the slug. [slug] is chosen by the caller where the order of equal timestamps matters. */
        fun createArticle(
            publishedAt: LocalDateTime,
            slug: String = "embed-archive-test-${Uuid.random()}",
            status: ArticleStatus = ArticleStatus.PUBLISHED,
            id: Uuid = Uuid.random(),
            byline: String? = null,
        ): String {
            val author = createMember()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = slug
                    it[title] = "Archiv-Testartikel"
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt von $slug"
                    it[authorId] = author
                    it[ArticleTable.status] = status
                    it[submittedAt] = now
                    it[ArticleTable.publishedAt] = publishedAt
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ArticleTable.byline] = byline
                    it[bylineKind] = if (byline != null) network.lapis.cloud.shared.domain.ArticleBylineKind.IMPORTED else null
                }
            }
            createdArticleIds += id
            return slug
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun testApp(
            feedRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            archiveRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            preflightRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedArticlesFeedRoutes(
                            config = enabledConfig,
                            baseUrl = "https://lapis.example",
                            feedRateLimiter = feedRateLimiter,
                            preflightRateLimiter = preflightRateLimiter,
                            archiveRateLimiter = archiveRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        val origin = "https://partei.example"

        fun at(minute: Int) = LocalDateTime(2093, 1, 1, 10, minute)

        fun slugsOf(body: String) =
            Json
                .parseToJsonElement(body)
                .jsonObject["articles"]!!
                .jsonArray
                .map { it.jsonObject["slug"]!!.jsonPrimitive.content }

        test("invalid parameters -> 400 invalid_parameter, never silently clamped") {
            testApp {
                for (query in listOf(
                    "page=0",
                    "page=101",
                    "page=abc",
                    "page=-1",
                    "limit=0",
                    "limit=21",
                    "limit=abc",
                    "limit=",
                    "page=1.5",
                )) {
                    val response = client.get("/api/embed/v1/articles/archive?$query") { header(HttpHeaders.Origin, origin) }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldBe """{"error":"invalid_parameter"}"""
                }
            }
        }

        test("the bounds themselves are valid: page=100, limit=20, limit=1") {
            testApp {
                for (query in listOf("page=100", "limit=20", "limit=1", "page=1&limit=20")) {
                    client.get("/api/embed/v1/articles/archive?$query") { header(HttpHeaders.Origin, origin) }.status shouldBe
                        HttpStatusCode.OK
                }
            }
        }

        test("hasMore is exact at the page boundary, and page/limit are echoed") {
            val slugs = (1..5).map { createArticle(publishedAt = at(it)) } // newest = minute 5
            testApp {
                val first = client.get("/api/embed/v1/articles/archive?page=1&limit=2") { header(HttpHeaders.Origin, origin) }
                first.status shouldBe HttpStatusCode.OK
                val firstJson = Json.parseToJsonElement(first.bodyAsText()).jsonObject
                firstJson["page"]!!.jsonPrimitive.content shouldBe "1"
                firstJson["limit"]!!.jsonPrimitive.content shouldBe "2"
                firstJson["hasMore"]!!.jsonPrimitive.content shouldBe "true"
                slugsOf(first.bodyAsText()) shouldBe listOf(slugs[4], slugs[3])

                val second = client.get("/api/embed/v1/articles/archive?page=2&limit=2") { header(HttpHeaders.Origin, origin) }
                slugsOf(second.bodyAsText()) shouldBe listOf(slugs[2], slugs[1])
                Json
                    .parseToJsonElement(second.bodyAsText())
                    .jsonObject["hasMore"]!!
                    .jsonPrimitive.content shouldBe "true"
            }
        }

        test("hasMore is false on the last page") {
            createArticle(publishedAt = at(20))
            val total = transaction { ArticleTable.selectAll().where { ArticleTable.status eq ArticleStatus.PUBLISHED }.count() }
            val limit = 20
            val lastPage = ((total + limit - 1) / limit).toInt()
            if (lastPage in 1..100) {
                testApp {
                    val response =
                        client.get(
                            "/api/embed/v1/articles/archive?page=$lastPage&limit=$limit",
                        ) { header(HttpHeaders.Origin, origin) }
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["hasMore"]!!
                        .jsonPrimitive.content shouldBe "false"
                }
            }
        }

        test("equal published_at: the order is stable through the id (descending), also across a page boundary") {
            val ids = listOf(Uuid.parse("00000000-0000-0000-0000-0000000000a1"), Uuid.parse("00000000-0000-0000-0000-0000000000a2"))
            val low = createArticle(publishedAt = at(30), id = ids[0], slug = "embed-archive-tie-a")
            val high = createArticle(publishedAt = at(30), id = ids[1], slug = "embed-archive-tie-b")
            testApp {
                val one = client.get("/api/embed/v1/articles/archive?page=1&limit=20") { header(HttpHeaders.Origin, origin) }
                val order = slugsOf(one.bodyAsText())
                (order.indexOf(high) in 0 until order.indexOf(low)) shouldBe true
                // The same two rows, one per page, in the same order.
                val idx = order.indexOf(high)
                val p1 = client.get("/api/embed/v1/articles/archive?page=${idx + 1}&limit=1") { header(HttpHeaders.Origin, origin) }
                slugsOf(p1.bodyAsText()) shouldBe listOf(high)
                val p2 = client.get("/api/embed/v1/articles/archive?page=${idx + 2}&limit=1") { header(HttpHeaders.Origin, origin) }
                slugsOf(p2.bodyAsText()) shouldBe listOf(low)
            }
        }

        test("only PUBLISHED rows are served; byline and body appear like in the main feed, internals never") {
            val published = createArticle(publishedAt = at(40), byline = "Archiv-Redaktion")
            val draft = createArticle(publishedAt = at(41), status = ArticleStatus.DRAFT)
            testApp {
                val response = client.get("/api/embed/v1/articles/archive?limit=20") { header(HttpHeaders.Origin, origin) }
                val raw = response.bodyAsText()
                val slugs = slugsOf(raw)
                (published in slugs) shouldBe true
                (draft in slugs) shouldBe false
                val item =
                    Json.parseToJsonElement(raw).jsonObject["articles"]!!.jsonArray.map { it.jsonObject }.single {
                        it["slug"]!!.jsonPrimitive.content == published
                    }
                item["byline"]!!.jsonPrimitive.content shouldBe "Archiv-Redaktion"
                item["body"]!!.jsonPrimitive.content shouldBe "Inhalt von $published"
                for (forbidden in listOf(
                    "authorId",
                    "author",
                    "reviewedBy",
                    "id",
                    "rejectionReason",
                    "bylineKind",
                    "imported",
                    "mcpAgentLabel",
                )) {
                    raw.contains("\"$forbidden\"") shouldBe false
                }
            }
        }

        test("response headers: nosniff, CORS echo for an allowed origin, 403 and no CORS header for a foreign one") {
            testApp {
                val ok = client.get("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, origin) }
                ok.headers["X-Content-Type-Options"] shouldBe "nosniff"
                ok.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe origin
                val denied = client.get("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, "https://evil.example") }
                denied.status shouldBe HttpStatusCode.Forbidden
                denied.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("the archive has its OWN rate limiter: 429 with Retry-After, and the main feed's budget stays untouched") {
            val strictArchive = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            val mainFeed = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(feedRateLimiter = mainFeed, archiveRateLimiter = strictArchive) {
                client.get("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, origin) }.status shouldBe HttpStatusCode.OK
                val second = client.get("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, origin) }
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
                // The main feed still has its single request.
                client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, origin) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("OPTIONS preflight: 204 for an allowed origin, 403 for a foreign one, 429 when its budget is spent") {
            testApp {
                val preflight = client.options("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, origin) }
                preflight.status shouldBe HttpStatusCode.NoContent
                preflight.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "GET, OPTIONS"
                client.options("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe
                    HttpStatusCode.Forbidden
            }
            testApp(preflightRateLimiter = FederationInboxRateLimiter(maxRequests = 0, window = 1.minutes, maxTrackedKeys = 50_000)) {
                client.options("/api/embed/v1/articles/archive") { header(HttpHeaders.Origin, origin) }.status shouldBe
                    HttpStatusCode.TooManyRequests
            }
        }
    })
