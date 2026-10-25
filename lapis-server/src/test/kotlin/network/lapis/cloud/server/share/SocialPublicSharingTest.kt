package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.PublicNavAvailabilityProvider
import network.lapis.cloud.server.routes.registerSocialPublicRoutes
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jsoup.Jsoup
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The Social route family takes its base URL from FederationConfig (http://localhost:8080 in tests, a loopback dev URL). */
class SocialPublicSharingTest :
    FunSpec({
        val postIds = mutableListOf<Uuid>()
        val memberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterTest {
            transaction {
                postIds.forEach { id -> SocialPostTable.deleteWhere { SocialPostTable.id eq id } }
                memberIds.forEach { id -> MemberTable.deleteWhere { MemberTable.id eq id } }
            }
            postIds.clear()
            memberIds.clear()
        }

        fun createPost(): Uuid {
            val author = Uuid.random()
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = author
                    it[displayName] = "SocialPublicSharingTest Autor"
                    it[email] = "social-share-$author@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                SocialPostTable.insert {
                    it[SocialPostTable.id] = id
                    it[parentId] = null
                    it[rootId] = id
                    it[depth] = 0
                    it[authorMemberId] = author
                    it[content] = "Teilbarer Beitrag ${Uuid.random()}"
                    it[visibility] = SocialPostVisibility.PUBLIC
                    it[initialWeightLtr] = BigDecimal("1.00")
                    it[publishedAt] = DbClock.nowLocalDateTime()
                    it[state] = SocialPostState.VISIBLE
                    it[stateChangedAt] = null
                    it[stateChangedBy] = null
                    it[stateReason] = null
                }
            }
            memberIds += author
            postIds += id
            return id
        }

        fun limiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun withApp(
            share: ShareConfig,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    registerSocialPublicRoutes(
                        readRateLimiter = limiter(),
                        sitemapRateLimiter = limiter(),
                        reportRateLimiter = limiter(),
                        branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                        navAvailability = PublicNavAvailabilityProvider(),
                        shareConfig = share,
                    )
                }
            }
            block()
        }

        val enabled = ShareConfig.load(baseUrl = "http://localhost:8080", env = { null })
        val plainCsp =
            "default-src 'none'; style-src 'self'; font-src 'self'; img-src data:; base-uri 'none'; " +
                "form-action 'self'; frame-ancestors 'none'"
        val scriptCsp =
            "default-src 'none'; style-src 'self'; font-src 'self'; script-src 'self'; img-src data:; base-uri 'none'; " +
                "form-action 'self'; frame-ancestors 'none'"

        test("post page: bar directly after the post, widened CSP, also on a 304 revalidation") {
            val id = createPost()
            withApp(enabled) {
                val response = client.get("/s/$id")
                response.headers["Content-Security-Policy"] shouldBe scriptCsp
                val doc = Jsoup.parse(response.bodyAsText())
                doc.select("section.share").size shouldBe 1
                doc.select("script[src=/s/assets/share.js][defer]").size shouldBe 1
                doc.select("article + section.share").size shouldBe 1
                val etag = response.headers[HttpHeaders.ETag]!!
                val revalidated = client.get("/s/$id") { header(HttpHeaders.IfNoneMatch, etag) }
                revalidated.status shouldBe HttpStatusCode.NotModified
                revalidated.headers["Content-Security-Policy"] shouldBe scriptCsp
            }
        }

        test("DISABLED: no bar and the old CSP") {
            val id = createPost()
            withApp(ShareConfig.DISABLED) {
                val response = client.get("/s/$id")
                response.headers["Content-Security-Policy"] shouldBe plainCsp
                response.bodyAsText() shouldNotContain "class=\"share\""
            }
        }

        test("timeline, report form and 404 carry no bar and no script-src") {
            val id = createPost()
            withApp(enabled) {
                for (path in listOf("/s", "/s/$id/report", "/s/${Uuid.random()}")) {
                    val response = client.get(path)
                    (response.headers["Content-Security-Policy"] ?: "") shouldNotContain "script-src"
                    response.bodyAsText() shouldNotContain "class=\"share\""
                }
            }
        }

        test("share.js: served with nosniff, ETag and 304, body is the constant script") {
            withApp(ShareConfig.DISABLED) {
                val response = client.get("/s/assets/share.js")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/javascript"
                response.headers[HttpHeaders.ContentType] shouldContain "charset=utf-8"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.bodyAsText() shouldBe SHARE_SCRIPT
                val etag = response.headers[HttpHeaders.ETag]!!
                client.get("/s/assets/share.js") { header(HttpHeaders.IfNoneMatch, etag) }.status shouldBe HttpStatusCode.NotModified
            }
        }
    })
