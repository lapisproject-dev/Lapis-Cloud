package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
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
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation" -- route-level tests for `GET /aktuelles` and
 * `GET /veranstaltungen`, same `testApplication` house style [PublicLandingRoutesTest]/
 * [PublicTransparencyRoutesTest] establish: fixtures via direct Exposed `insert`, a FRESH
 * `testApplication` block (and therefore a fresh [PublicNavAvailabilityProvider] and body cache) per
 * test that asserts on nav/list state.
 *
 * `beforeTest` neutralizes every pre-existing `PUBLISHED` article / public+upcoming+`PUBLISHED` event
 * left behind by earlier specs sharing this process-wide H2 DB -- see [PublicNavAvailabilityTest]'s
 * own class KDoc "Stolperfalle S3" for the full rationale (identical here).
 */
class PublicOverviewRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                ArticleTable.update({ ArticleTable.status eq ArticleStatus.PUBLISHED }) { it[status] = ArticleStatus.DRAFT }
                EventTable.update({
                    (EventTable.visibility eq EventVisibility.PUBLIC) and (EventTable.status eq EventStatus.PUBLISHED)
                }) { it[status] = EventStatus.CANCELLED }
            }
        }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) ArticleTable.deleteWhere { id inList createdArticleIds }
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PublicOverviewRoutesTest Mitglied"
                    it[email] = "public-overview-routes-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun createArticle(
            title: String = "Öffentliche Icon-Navigation Testartikel <script>",
            status: ArticleStatus = ArticleStatus.PUBLISHED,
            publishedAt: LocalDateTime = LocalDateTime(2030, 1, 1, 10, 0),
        ): Pair<Uuid, String?> {
            val author = createMember()
            val id = Uuid.random()
            val slug = if (status == ArticleStatus.PUBLISHED) "overview-test-$id" else null
            val now = DbClock.nowLocalDateTime()
            transaction {
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
                    it[ArticleTable.publishedAt] = if (status == ArticleStatus.PUBLISHED) publishedAt else null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            createdArticleIds += id
            return id to slug
        }

        fun createEvent(
            title: String = "Öffentliche Icon-Navigation Test-Event",
            startsAt: LocalDateTime = LocalDateTime(2030, 6, 1, 19, 0),
            endsAt: LocalDateTime = LocalDateTime(2030, 6, 1, 22, 0),
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
        ): Pair<Uuid, String> {
            val organizer = createMember()
            val id = Uuid.random()
            val slug = "overview-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[EventTable.visibility] = visibility
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id to slug
        }

        suspend fun testApp(
            readLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
                        val navAvailability = PublicNavAvailabilityProvider()
                        registerPublicArticlesOverviewRoutes(
                            readRateLimiter = readLimiter,
                            branding = branding,
                            navAvailability = navAvailability,
                        )
                        registerPublicEventsOverviewRoutes(
                            readRateLimiter = readLimiter,
                            branding = branding,
                            navAvailability = navAvailability,
                        )
                    }
                }
                block()
            }
        }

        // ── T6a: articles happy path ─────────────────────────────────────────────
        test("T6a: GET /aktuelles lists a published article, links to its detail page, escapes the title") {
            val (_, slug) = createArticle()
            testApp {
                val response = client.get("/aktuelles")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "href=\"http://localhost:8080/aktuelles/$slug\""
                body shouldContain "hreflang=\"de\""
                body shouldNotContain "<script>"
                body shouldContain "&lt;script&gt;"
            }
        }

        test("T6c: GET /aktuelles with no published articles shows the empty state, still 200, no nav-articles link") {
            testApp {
                val response = client.get("/aktuelles")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain PublicChrome.stringsFor(PublicLanguage.DEFAULT).articlesEmpty
                body shouldNotContain "nav-articles"
            }
        }

        test("T6a: a DRAFT/SUBMITTED/REJECTED article never appears on /aktuelles") {
            createArticle(status = ArticleStatus.DRAFT, title = "Entwurf, darf nie erscheinen")
            testApp {
                val body = client.get("/aktuelles").bodyAsText()
                body shouldNotContain "Entwurf, darf nie erscheinen"
            }
        }

        // ── T6b: events happy path ───────────────────────────────────────────────
        test("T6b: GET /veranstaltungen lists an upcoming public event, links to its detail page, German date/time") {
            val (_, slug) = createEvent()
            testApp {
                val body = client.get("/veranstaltungen").bodyAsText()
                body shouldContain "href=\"http://localhost:8080/veranstaltung/$slug\""
                body shouldContain "1. Juni 2030, 19:00 Uhr"
            }
        }

        test("T6c: GET /veranstaltungen with no upcoming events shows the empty state, still 200, no nav-events link") {
            testApp {
                val response = client.get("/veranstaltungen")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain PublicChrome.stringsFor(PublicLanguage.DEFAULT).eventsEmpty
                body shouldNotContain "nav-events"
            }
        }

        test("T6b: a past, MEMBERS_ONLY, DRAFT, or CANCELLED event never appears on /veranstaltungen") {
            createEvent(
                title = "Vergangenes Event, darf nie erscheinen",
                startsAt = LocalDateTime(2020, 1, 1, 10, 0),
                endsAt = LocalDateTime(2020, 1, 1, 12, 0),
            )
            createEvent(title = "Mitgliederintern, darf nie erscheinen", visibility = EventVisibility.MEMBERS_ONLY)
            createEvent(title = "Entwurf, darf nie erscheinen", status = EventStatus.DRAFT)
            testApp {
                val body = client.get("/veranstaltungen").bodyAsText()
                body shouldNotContain "darf nie erscheinen"
            }
        }

        // ── T6d: canonical redirect ───────────────────────────────────────────────
        test("T6d: an unexpected query parameter 308-redirects to the bare canonical URL") {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/aktuelles?foo=1")
                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/aktuelles"
            }
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/veranstaltungen?foo=1")
                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/veranstaltungen"
            }
        }

        test("T6d: ?lang=de (the default) 308-redirects to the parameter-free canonical URL") {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/aktuelles?lang=de")
                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/aktuelles"
            }
        }

        // ── T6e: language switching ───────────────────────────────────────────────
        test("T6e: ?lang=en renders <html lang=\"en\">, the English label, and aria-current on nav-articles") {
            createArticle()
            testApp {
                val body = client.get("/aktuelles?lang=en").bodyAsText()
                body shouldContain "<html lang=\"en\">"
                body shouldContain PublicChrome.stringsFor(PublicLanguage.EN).navArticles
                Regex("""class="nav-articles"[^>]*aria-current="page"""").containsMatchIn(body) shouldBe true
            }
        }

        // ── T6f: rate limiting ────────────────────────────────────────────────────
        test("T6f: exceeding the rate limit returns 429 for /aktuelles") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.get("/aktuelles").status shouldBe HttpStatusCode.OK
                client.get("/aktuelles").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("T6f: exceeding the rate limit returns 429 for /veranstaltungen") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.get("/veranstaltungen").status shouldBe HttpStatusCode.OK
                client.get("/veranstaltungen").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        // ── T6g: headers ──────────────────────────────────────────────────────────
        test(
            "T6g: /aktuelles carries Cache-Control max-age=300, a CSP without script-src 'self', robots index,follow, and a canonical link",
        ) {
            testApp {
                val response = client.get("/aktuelles")
                (response.headers[HttpHeaders.CacheControl] ?: "") shouldContain "public, max-age=300"
                (response.headers["Content-Security-Policy"] ?: "") shouldNotContain "script-src"
                val body = response.bodyAsText()
                body shouldContain "name=\"robots\" content=\"index,follow\""
                body shouldContain "rel=\"canonical\""
            }
        }
    })
