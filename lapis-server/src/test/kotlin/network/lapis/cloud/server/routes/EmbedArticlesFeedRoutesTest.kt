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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- `GET /api/embed/v1/articles` and its
 * `OPTIONS` preflight. 1:1 structural mirror of [EmbedEventsFeedRoutesTest].
 */
class EmbedArticlesFeedRoutesTest :
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
                    it[displayName] = "EmbedArticlesFeedRoutesTest Mitglied"
                    it[email] = "embed-articles-feed-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        val farFuturePublishedAt = LocalDateTime(2030, 1, 1, 12, 0)

        fun createArticle(
            title: String = "Embed-Articles-Feed-Test-Artikel",
            excerpt: String = "Auszug",
            body: String = "Inhalt",
            status: ArticleStatus = ArticleStatus.PUBLISHED,
            publishedAt: LocalDateTime? = farFuturePublishedAt,
            slug: String? = null,
            coverImageId: Uuid? = null,
        ): Pair<Uuid, String?> {
            val author = createMember()
            val id = Uuid.random()
            val actualSlug = slug ?: if (status == ArticleStatus.PUBLISHED) "embed-articles-feed-test-$id" else null
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = actualSlug
                    it[ArticleTable.title] = title
                    it[ArticleTable.excerpt] = excerpt
                    it[ArticleTable.body] = body
                    it[ArticleTable.coverImageId] = coverImageId
                    it[ArticleTable.authorId] = author
                    it[ArticleTable.status] = status
                    it[ArticleTable.submittedAt] = now
                    it[ArticleTable.reviewedBy] = null
                    it[ArticleTable.reviewedAt] = null
                    it[ArticleTable.rejectionReason] = null
                    it[ArticleTable.publishedAt] = publishedAt
                    it[ArticleTable.createdAt] = now
                    it[ArticleTable.updatedAt] = now
                }
            }
            createdArticleIds += id
            return id to actualSlug
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
                        )
                    }
                }
                block()
            }
        }

        fun expectedUtcIso(dt: LocalDateTime): String {
            val utc = dt.toInstant(TimeZone.currentSystemDefault()).toLocalDateTime(TimeZone.UTC)
            return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(utc.year, utc.monthNumber, utc.dayOfMonth, utc.hour, utc.minute, utc.second)
        }

        fun articlesOf(body: String) = Json.parseToJsonElement(body).jsonObject["articles"]!!.jsonArray

        fun articlesOf(
            body: String,
            slug: String,
        ) = articlesOf(body).map { it.jsonObject }.single { it.jsonObject["slug"]!!.jsonPrimitive.content == slug }

        test("200, envelope shape, field values match a seeded PUBLISHED article") {
            val (_, slug) = createArticle(title = "Ein Test-Artikel", excerpt = "Kurzer Auszug")
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.OK
                val item = articlesOf(response.bodyAsText(), slug!!)
                item["title"]!!.jsonPrimitive.content shouldBe "Ein Test-Artikel"
                item["excerpt"]!!.jsonPrimitive.content shouldBe "Kurzer Auszug"
                item["url"]!!.jsonPrimitive.content shouldBe "https://lapis.example/aktuelles/$slug"
                item["coverImageUrl"] shouldBe null
                item["publishedAt"]!!.jsonPrimitive.content shouldBe expectedUtcIso(farFuturePublishedAt)
            }
        }

        test("coverImageUrl is the public /aktuelles/{slug}/bild URL when a cover is set") {
            val coverId = Uuid.random()
            val (_, slug) = createArticle(coverImageId = coverId)
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                val item = articlesOf(response.bodyAsText(), slug!!)
                item["coverImageUrl"]!!.jsonPrimitive.content shouldBe
                    "https://lapis.example/aktuelles/$slug/bild?v=${coverId.toString().take(8)}"
            }
        }

        test("WICHTIG: DRAFT/SUBMITTED/REJECTED/depubliziert never appear") {
            val (_, draftSlug) = createArticle(status = ArticleStatus.DRAFT, publishedAt = null)
            val (_, submittedSlug) = createArticle(status = ArticleStatus.SUBMITTED, publishedAt = null)
            val (_, rejectedSlug) = createArticle(status = ArticleStatus.REJECTED, publishedAt = null)
            val (_, unpublishedSlug) =
                createArticle(status = ArticleStatus.REJECTED, publishedAt = farFuturePublishedAt, slug = "unpublished-test-slug")
            val (_, publicSlug) = createArticle()
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                val slugs = articlesOf(response.bodyAsText()).map { it.jsonObject["slug"]!!.jsonPrimitive.content }
                (publicSlug in slugs) shouldBe true
                (draftSlug in slugs) shouldBe false
                (submittedSlug in slugs) shouldBe false
                (rejectedSlug in slugs) shouldBe false
                ("unpublished-test-slug" in slugs) shouldBe false
            }
        }

        test("WICHTIG: the JSON string never contains body/authorId/author/reviewedBy/id/rejectionReason") {
            createArticle(body = "Geheimer Fliesstext, der niemals im Feed erscheinen darf")
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                val raw = response.bodyAsText()
                raw.contains("\"body\"") shouldBe false
                raw.contains("\"authorId\"") shouldBe false
                raw.contains("\"author\"") shouldBe false
                raw.contains("\"reviewedBy\"") shouldBe false
                raw.contains("\"id\"") shouldBe false
                raw.contains("\"rejectionReason\"") shouldBe false
                raw.contains("Geheimer Fliesstext") shouldBe false
            }
        }

        test("WICHTIG: limit -- default 10, cap 20, 0/-1/abc all fall back to 10") {
            repeat(25) { createArticle() }
            testApp {
                val default = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                articlesOf(default.bodyAsText()).size shouldBe 10

                val capped = client.get("/api/embed/v1/articles?limit=50") { header(HttpHeaders.Origin, "https://partei.example") }
                articlesOf(capped.bodyAsText()).size shouldBe 20

                val zero = client.get("/api/embed/v1/articles?limit=0") { header(HttpHeaders.Origin, "https://partei.example") }
                articlesOf(zero.bodyAsText()).size shouldBe 10

                val negative = client.get("/api/embed/v1/articles?limit=-1") { header(HttpHeaders.Origin, "https://partei.example") }
                articlesOf(negative.bodyAsText()).size shouldBe 10

                val garbage = client.get("/api/embed/v1/articles?limit=abc") { header(HttpHeaders.Origin, "https://partei.example") }
                articlesOf(garbage.bodyAsText()).size shouldBe 10

                val exact = client.get("/api/embed/v1/articles?limit=3") { header(HttpHeaders.Origin, "https://partei.example") }
                articlesOf(exact.bodyAsText()).size shouldBe 3
            }
        }

        test("ordering: results are newest-published-first") {
            // Extreme, test-unique dates -- other tests in this Spec share the same DB and may have
            // already inserted many PUBLISHED articles at farFuturePublishedAt (2030-01-01); using
            // dates further out than anything else in this file guarantees these two rank first
            // regardless of execution order or how much other test data already exists.
            val (_, olderSlug) = createArticle(publishedAt = LocalDateTime(2098, 1, 1, 10, 0))
            val (_, newerSlug) = createArticle(publishedAt = LocalDateTime(2099, 1, 1, 10, 0))
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                val slugs = articlesOf(response.bodyAsText()).map { it.jsonObject["slug"]!!.jsonPrimitive.content }
                val newerIndex = slugs.indexOf(newerSlug)
                val olderIndex = slugs.indexOf(olderSlug)
                (newerIndex >= 0 && olderIndex >= 0) shouldBe true
                (newerIndex < olderIndex) shouldBe true
            }
        }

        test(
            "WICHTIG: a PUBLISHED row with a null publishedAt (data inconsistency, cannot happen by " +
                "construction) is skipped, not a 500 for the whole feed",
        ) {
            // Same "extreme date guarantees this test's own consistent article ranks first
            // regardless of other tests' data" reasoning as the ordering test above.
            val (_, consistentSlug) = createArticle(publishedAt = LocalDateTime(2096, 1, 1, 10, 0))
            createArticle(publishedAt = null) // PUBLISHED with a slug but no publishedAt -- the inconsistency.
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.OK
                // The consistent article is still served -- the one bad row did not fail the whole feed.
                articlesOf(response.bodyAsText(), consistentSlug!!)
            }
        }

        test("NoOriginHeader -> 200, not 403") {
            // Same "extreme date guarantees top-of-list regardless of other tests' data" reasoning
            // as the ordering test above.
            val (_, slug) = createArticle(publishedAt = LocalDateTime(2097, 1, 1, 10, 0))
            testApp {
                val response = client.get("/api/embed/v1/articles")
                response.status shouldBe HttpStatusCode.OK
                articlesOf(response.bodyAsText(), slug!!)
            }
        }

        test("disallowed Origin -> 403, no Access-Control-Allow-Origin") {
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://evil.example") }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("allowed Origin -> Access-Control-Allow-Origin echoes the canonical allowlist entry") {
            testApp {
                val response = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://partei.example"
            }
        }

        test("rate limit exhausted -> 429 with Retry-After header") {
            val strict = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(feedRateLimiter = strict) {
                client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                val second = client.get("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
            }
        }

        test("OPTIONS preflight: 204, correct Access-Control-Allow-Methods, rejects a bad origin") {
            testApp {
                val preflight = client.options("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                preflight.status shouldBe HttpStatusCode.NoContent
                preflight.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "GET, OPTIONS"

                val badOrigin = client.options("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://evil.example") }
                badOrigin.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("OPTIONS preflight rate limit exhausted -> 429") {
            val strict = FederationInboxRateLimiter(maxRequests = 0, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(preflightRateLimiter = strict) {
                val response = client.options("/api/embed/v1/articles") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
