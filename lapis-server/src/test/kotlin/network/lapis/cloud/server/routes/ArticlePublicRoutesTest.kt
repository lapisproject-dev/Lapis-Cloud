package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- `GET /aktuelles/{slug}` and
 * `GET /aktuelles/{slug}/bild`. Mirrors `EventPublicRoutesTest`'s registration-at-the-
 * `register*PublicRoutes`-level pattern.
 */
class ArticlePublicRoutesTest :
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
                    it[displayName] = "ArticlePublicRoutesTest Mitglied"
                    it[email] = "article-public-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun createArticle(
            title: String = "Ein oeffentlicher Artikel",
            excerpt: String = "Ein Auszug",
            body: String = "# Ueberschrift\n\nInhalt.",
            status: ArticleStatus = ArticleStatus.PUBLISHED,
            slug: String? = null,
            coverImageId: Uuid? = null,
        ): Pair<Uuid, String?> {
            val author = createMember()
            val id = Uuid.random()
            val actualSlug = slug ?: if (status == ArticleStatus.PUBLISHED) "article-public-test-$id" else null
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
                    it[ArticleTable.publishedAt] = if (status == ArticleStatus.PUBLISHED) LocalDateTime(2030, 9, 28, 10, 0) else null
                    it[ArticleTable.createdAt] = now
                    it[ArticleTable.updatedAt] = now
                }
            }
            createdArticleIds += id
            return id to actualSlug
        }

        /** A REJECTED article that WAS published before (depubliziert) -- publishedAt stays set. */
        fun createUnpublishedArticle(slug: String): Uuid {
            val author = createMember()
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = slug
                    it[ArticleTable.title] = "Depublizierter Artikel"
                    it[ArticleTable.excerpt] = "Auszug"
                    it[ArticleTable.body] = "Inhalt"
                    it[ArticleTable.coverImageId] = null
                    it[ArticleTable.authorId] = author
                    it[ArticleTable.status] = ArticleStatus.REJECTED
                    it[ArticleTable.submittedAt] = now
                    it[ArticleTable.reviewedBy] = null
                    it[ArticleTable.reviewedAt] = now
                    it[ArticleTable.rejectionReason] = "depubliziert"
                    it[ArticleTable.publishedAt] = LocalDateTime(2030, 9, 28, 10, 0)
                    it[ArticleTable.createdAt] = now
                    it[ArticleTable.updatedAt] = now
                }
            }
            createdArticleIds += id
            return id
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val defaultBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null, websiteUrl = null)

        suspend fun testApp(
            branding: ResolvedBranding = defaultBranding,
            pageRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            coverReadRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerArticlePublicRoutes(
                            baseUrl = "https://lapis.example",
                            branding = branding,
                            coverStorage = EventCoverStorage(createTempDirectory("article-public-test").toFile()),
                            pageRateLimiter = pageRateLimiter,
                            coverReadRateLimiter = coverReadRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        test("happy path: title/lead/body/meta-tags present, no author name or id in the HTML") {
            val (id, slug) = createArticle(title = "Mein Artikel", excerpt = "Mein Auszug", body = "# Titel\n\nHallo Welt.")
            testApp {
                val response = client.get("/aktuelles/$slug")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html.contains("Mein Artikel") shouldBe true
                html.contains("Mein Auszug") shouldBe true
                html.contains("Hallo Welt.") shouldBe true
                html.contains("og:title") shouldBe true
                html.contains("og:description") shouldBe true
                html.contains("og:type") shouldBe true
                html.contains("article:published_time") shouldBe true
                html.contains(id.toString()) shouldBe false
            }
        }

        test("og:image only appears when a cover image is set") {
            val (_, noImageSlug) = createArticle()
            val (_, withImageSlug) = createArticle(coverImageId = Uuid.random())
            testApp {
                val without = client.get("/aktuelles/$noImageSlug").bodyAsText()
                without.contains("og:image") shouldBe false

                val with = client.get("/aktuelles/$withImageSlug").bodyAsText()
                with.contains("og:image") shouldBe true
            }
        }

        test("\"Alle Neuigkeiten\" link only appears when branding.websiteUrl is set") {
            val (_, slug) = createArticle()
            testApp {
                val withoutUrl = client.get("/aktuelles/$slug").bodyAsText()
                withoutUrl.contains("Alle Neuigkeiten") shouldBe false
            }
            testApp(branding = defaultBranding.copy(websiteUrl = "https://example.org")) {
                val withUrl = client.get("/aktuelles/$slug").bodyAsText()
                withUrl.contains("Alle Neuigkeiten") shouldBe true
                withUrl.contains("https://example.org") shouldBe true
            }
        }

        test(
            "WICHTIG: 404 for unknown slug, DRAFT, SUBMITTED, REJECTED, and depubliziert-with-slug are byte-identical (status+body+headers)",
        ) {
            val (_, draftSlug) = createArticle(status = ArticleStatus.DRAFT, slug = "draft-test-slug-1")
            val (_, submittedSlug) = createArticle(status = ArticleStatus.SUBMITTED, slug = "submitted-test-slug-1")
            val (_, rejectedSlug) = createArticle(status = ArticleStatus.REJECTED, slug = "rejected-test-slug-1")
            val unpublishedSlug = "unpublished-test-slug-1"
            createUnpublishedArticle(unpublishedSlug)
            testApp {
                val unknown = client.get("/aktuelles/does-not-exist-at-all")
                val draft = client.get("/aktuelles/$draftSlug")
                val submitted = client.get("/aktuelles/$submittedSlug")
                val rejected = client.get("/aktuelles/$rejectedSlug")
                val unpublished = client.get("/aktuelles/$unpublishedSlug")

                val all = listOf(unknown, draft, submitted, rejected, unpublished)
                all.forEach { it.status shouldBe HttpStatusCode.NotFound }
                val bodies = all.map { it.bodyAsText() }
                bodies.distinct().size shouldBe 1
                val cacheControls = all.map { it.headers[HttpHeaders.CacheControl] }
                cacheControls.distinct().size shouldBe 1
            }
        }

        test("WICHTIG: the public cover-image route gives the same 404 for all non-published statuses") {
            val (_, draftSlug) = createArticle(status = ArticleStatus.DRAFT, slug = "draft-test-slug-2", coverImageId = Uuid.random())
            val (_, submittedSlug) =
                createArticle(status = ArticleStatus.SUBMITTED, slug = "submitted-test-slug-2", coverImageId = Uuid.random())
            val (_, rejectedSlug) =
                createArticle(
                    status = ArticleStatus.REJECTED,
                    slug = "rejected-test-slug-2",
                    coverImageId = Uuid.random(),
                )
            testApp {
                client.get("/aktuelles/$draftSlug/bild").status shouldBe HttpStatusCode.NotFound
                client.get("/aktuelles/$submittedSlug/bild").status shouldBe HttpStatusCode.NotFound
                client.get("/aktuelles/$rejectedSlug/bild").status shouldBe HttpStatusCode.NotFound
                client.get("/aktuelles/unknown-slug-xyz/bild").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("XSS: a <script> tag and a javascript: link in the body never appear executable in the HTML") {
            val maliciousBody = "Text <script>alert(1)</script> mehr Text [Link](javascript:alert(2))"
            val (_, slug) = createArticle(body = maliciousBody)
            testApp {
                val html = client.get("/aktuelles/$slug").bodyAsText()
                html.contains("<script>alert(1)</script>") shouldBe false
                html.contains("javascript:alert(2)") shouldBe false
            }
        }

        test("headers: CSP present, Cache-Control no-store, immutable when v matches") {
            val coverId = Uuid.random()
            val (_, slug) = createArticle(coverImageId = coverId)
            testApp {
                val page = client.get("/aktuelles/$slug")
                page.headers["Content-Security-Policy"] shouldBe
                    "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"
                page.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                val matchingV = client.get("/aktuelles/$slug/bild?v=${coverId.toString().take(8)}")
                matchingV.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"

                val noV = client.get("/aktuelles/$slug/bild")
                noV.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=300"
            }
        }
    })
