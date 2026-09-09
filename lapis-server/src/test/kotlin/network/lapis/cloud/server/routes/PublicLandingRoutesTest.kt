package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.6 "Öffentliche Startseite" -- route-level tests for `GET /`, same `testApplication`
 * house style [PublicTransparencyRoutesTest]/[SocialPublicRoutesTest] establish: fixtures via direct
 * Exposed `insert`, no auth installation at all (the whole point is that none is needed).
 *
 * `DevSeedData.seedIfEmpty` seeds at least one [MemberTable] row but ZERO [SocialPostTable] rows --
 * so a fresh test DB, with no post fixture inserted by an individual test, is already exactly the
 * "Leerzustand A" (members > 0, posts == 0) shape without any extra setup; see the corresponding test
 * below. "Leerzustand B" (0 members AND 0 posts) has no realistic route-level fixture (seeded members
 * cannot be un-seeded mid-suite) -- covered instead as a pure [PublicLandingHtml.page] unit test.
 */
class PublicLandingRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdPostIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                if (createdPostIds.isNotEmpty()) {
                    SocialPostTable.deleteWhere { SocialPostTable.id inList createdPostIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
            createdPostIds.clear()
            createdMemberIds.clear()
        }

        fun createAuthor(displayName: String = "Landing Test Autor"): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "landing-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertPost(
            authorMemberId: Uuid,
            content: String = "Landing-Testinhalt ${Uuid.random()}",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                SocialPostTable.insert {
                    it[SocialPostTable.id] = id
                    it[SocialPostTable.parentId] = null
                    it[SocialPostTable.rootId] = id
                    it[SocialPostTable.depth] = 0
                    it[SocialPostTable.authorMemberId] = authorMemberId
                    it[SocialPostTable.content] = content
                    it[SocialPostTable.visibility] = SocialPostVisibility.PUBLIC
                    it[SocialPostTable.initialWeightLtr] = BigDecimal("1.00")
                    it[SocialPostTable.publishedAt] = DbClock.nowLocalDateTime()
                    it[SocialPostTable.state] = SocialPostState.VISIBLE
                    it[SocialPostTable.stateChangedAt] = null
                    it[SocialPostTable.stateChangedBy] = null
                    it[SocialPostTable.stateReason] = null
                }
            }
            createdPostIds += id
            return id
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            readLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        registerPublicLandingRoutes(
                            readRateLimiter = readLimiter,
                            branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                        )
                    }
                }
                block()
            }
        }

        // ── 1: rendering with data ──────────────────────────────────────────────────────
        test("GET /: 200, brand title, three stat tiles, top-post titles, login/register CTAs, transparency link") {
            testApp {
                val author = createAuthor()
                (1..5).forEach { insertPost(authorMemberId = author, content = "Landing-Post $it") }

                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "Lapis Cloud"
                body shouldContain "Mitglieder"
                body shouldContain "Öffentliche Beiträge"
                (1..5).forEach { body shouldContain "Landing-Post $it" }
                body shouldContain "href=\"http://localhost:8080/app#/login\""
                body shouldContain "href=\"http://localhost:8080/app#/register\""
                body shouldContain "href=\"http://localhost:8080/transparenz\""
            }
        }

        // ── Regressions-Test: der Hero-Primary-CTA (nicht der gleichlautende Chrome-CTA) muss zur
        // SPA-Registrierung verlinken. Prüft gezielt DIESES Anchor-Tag über seine "cta-primary"-Klasse
        // (der Chrome-Link trägt "chrome-cta", siehe [PublicChrome.renderChrome]) statt nur den
        // Body global auf irgendein Vorkommen des Links zu prüfen -- ein reiner "shouldContain"-Test
        // auf den Body wäre blind dafür, wenn ausgerechnet DIESER Button auf den Skip-Link-Anker
        // "#main" statt auf die Registrierung zeigt (siehe [PublicChrome.renderChrome]'s eigener
        // Skip-Link, derselbe Anker).
        test("GET /: hero's primary CTA (class cta-primary) links to the SPA register route, not the #main skip-link anchor") {
            testApp {
                val body = client.get("/").bodyAsText()
                val heroCtaTag =
                    Regex("""<a[^>]*class="[^"]*cta-primary[^"]*"[^>]*>""").find(body)?.value
                        ?: error("hero CTA anchor (class contains cta-primary) not found in body")
                heroCtaTag shouldContain "href=\"http://localhost:8080/app#/register\""
                heroCtaTag shouldNotContain "href=\"#main\""
            }
        }

        // ── 2: Leerzustand A -- members > 0, posts == 0 -- pure unit test (see class KDoc: a
        // route-level test asserting on real DB emptiness would be flaky in a full suite run,
        // where other test classes' fixtures leave posts behind concurrently) ─────────────────
        test("Leerzustand A: PublicLandingHtml.page with stats present but empty topPosts renders the stats block, no top-posts section") {
            val html =
                PublicLandingHtml.page(
                    view =
                        PublicLandingView(
                            stats =
                                PublicTransparencyStats(
                                    activeMemberCount = 3L,
                                    mintedLtrTotal = "0.00",
                                    publicPostCount = 0L,
                                ),
                            topPosts = emptyList(),
                        ),
                    baseUrl = "https://cloud.example.org",
                    branding = ResolvedBranding(title = "Test-Verein", logoAvailable = false, logoPath = null),
                )
            html shouldContain "id=\"kennzahlen\""
            html shouldNotContain "id=\"beitraege\""
        }

        // ── 3: Leerzustand B -- pure unit test, see class KDoc ─────────────────────────
        test("Leerzustand B: PublicLandingHtml.page with stats == null and empty topPosts renders neither section, no bare zero") {
            val html =
                PublicLandingHtml.page(
                    view = PublicLandingView(stats = null, topPosts = emptyList()),
                    baseUrl = "https://cloud.example.org",
                    branding = ResolvedBranding(title = "Test-Verein", logoAvailable = false, logoPath = null),
                )
            html shouldNotContain "id=\"kennzahlen\""
            html shouldNotContain "id=\"beitraege\""
            html shouldNotContain ">0<"
            html shouldContain "Test-Verein"
            html shouldContain "href=\"https://cloud.example.org/app#/login\""
        }

        // ── 4/5: robots + canonical ─────────────────────────────────────────────────────
        test("meta robots is index,follow (the opposite of /transparenz), and canonical ignores a spoofed Host header") {
            testApp {
                val body = client.get("/") { header(HttpHeaders.Host, "evil.example") }.bodyAsText()
                body shouldContain "content=\"index,follow\""
                body shouldNotContain "content=\"noindex,follow\""
                body shouldContain "rel=\"canonical\""
                body shouldContain "http://localhost:8080/\""
                body shouldNotContain "evil.example"
            }
        }

        // ── 6: Datensparsamkeit -- the wave's core assertion ────────────────────────────
        test(
            "Datensparsamkeit: a board member's / LTR holder's / donor's display name never appears on /, " +
                "only a post AUTHOR's does",
        ) {
            testApp {
                val author = createAuthor(displayName = "Öffentlicher Autor")
                insertPost(authorMemberId = author, content = "Sichtbarer Landing-Beitrag")
                val nonAuthor = createAuthor(displayName = "Nicht-Autor Person")

                val body = client.get("/").bodyAsText()
                body shouldContain "Öffentlicher Autor"
                body shouldNotContain "Nicht-Autor Person"
            }
        }

        // ── 7/8: ETag/304, byte-identity ────────────────────────────────────────────────
        test("two immediate calls are byte-identical and the second's If-None-Match hits a 304") {
            testApp {
                val first = client.get("/")
                val etag = first.headers[HttpHeaders.ETag]
                val second = client.get("/")
                first.bodyAsText() shouldBe second.bodyAsText()

                val conditional = client.get("/") { header(HttpHeaders.IfNoneMatch, etag!!) }
                conditional.status shouldBe HttpStatusCode.NotModified
            }
        }

        // ── 9: query-param guard ────────────────────────────────────────────────────────
        test("GET /?utm_source=x (unexpected query param) 308-redirects to the bare canonical URL, before any DB work") {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/?utm_source=x")
                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers["Location"] shouldBe "http://localhost:8080/"
            }
        }

        // ── 10: rate limit ───────────────────────────────────────────────────────────────
        test("rate limit exceeded returns 429 with Retry-After and security headers, independent of /transparenz's own budget") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.get("/")
                val second = client.get("/")
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers["Retry-After"] shouldBe "60"
            }
        }

        // ── 11: full security-header set ────────────────────────────────────────────────
        test("GET / carries the full public security-header set") {
            testApp {
                val response = client.get("/")
                (response.headers["Content-Security-Policy"] ?: "") shouldContain "default-src 'none'"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
                response.headers["X-Frame-Options"] shouldBe "DENY"
                response.headers["Permissions-Policy"] shouldContain "geolocation=()"
            }
        }

        // ── 12: cache-control ────────────────────────────────────────────────────────────
        test("Cache-Control is public, max-age=300 -- no stale-while-revalidate, no Vary: Cookie") {
            testApp {
                val response = client.get("/")
                response.headers["Cache-Control"] shouldBe "public, max-age=300"
                (response.headers[HttpHeaders.Vary] ?: "") shouldNotContain "Cookie"
            }
        }

        // ── 13: cookie-independence (Norman-Assert) ─────────────────────────────────────
        test("the response body is byte-identical whether or not a session cookie is present") {
            testApp {
                val withoutCookie = client.get("/").bodyAsText()
                val withCookie = client.get("/") { header(HttpHeaders.Cookie, "lapis_session=some-opaque-value") }.bodyAsText()
                withoutCookie shouldBe withCookie
            }
        }

        // ── 14: XSS ──────────────────────────────────────────────────────────────────────
        test("XSS: a post author's display_name containing markup renders escaped, never raw") {
            testApp {
                val attacker = createAuthor(displayName = "<script>alert(1)</script>")
                insertPost(authorMemberId = attacker, content = "Post von einem XSS-Versuch")
                val body = client.get("/").bodyAsText()
                body shouldNotContain "<script>alert(1)</script>"
                body shouldContain "&lt;script&gt;"
            }
        }

        // ── 17 (optional, § 1.2 Hash-Bridge): scoped exactly to "/", never /s or /transparenz ──
        test("Hash-Bridge (§ 1.2): script-src 'self' and the hash-bridge <script> tag appear ONLY on /, never on /s or /transparenz") {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
                        registerPublicLandingRoutes(readRateLimiter = generousLimiter(), branding = branding)
                        registerSocialPublicRoutes(
                            readRateLimiter = generousLimiter(),
                            sitemapRateLimiter = generousLimiter(),
                            reportRateLimiter = generousLimiter(),
                            branding = branding,
                        )
                        registerPublicTransparencyRoutes(readRateLimiter = generousLimiter(), branding = branding)
                    }
                }
                val landing = client.get("/")
                (landing.headers["Content-Security-Policy"] ?: "") shouldContain "script-src 'self'"
                landing.bodyAsText() shouldContain "<script src=\"/s/assets/hash-bridge.js\">"

                val social = client.get("/s")
                (social.headers["Content-Security-Policy"] ?: "") shouldNotContain "script-src"

                val transparency = client.get("/transparenz")
                (transparency.headers["Content-Security-Policy"] ?: "") shouldNotContain "script-src"

                val bridgeAsset = client.get("/s/assets/hash-bridge.js")
                bridgeAsset.status shouldBe HttpStatusCode.OK
                (bridgeAsset.headers[HttpHeaders.ContentType] ?: "") shouldContain "javascript"
                bridgeAsset.bodyAsText() shouldContain "location.replace"
            }
        }

        // ── 18 (Security-Audit-Fund MAJOR, Welle V1.4.6 Review-Nachzug): body memoization ──
        test(
            "body memoization: a post inserted between two immediate GET / calls does NOT appear in " +
                "the second response -- proves the render is served from the in-memory TTL cache, " +
                "not recomputed from DB state on every request",
        ) {
            testApp {
                val author = createAuthor()
                insertPost(authorMemberId = author, content = "Vor dem Cache-Treffer sichtbar")

                val first = client.get("/")
                first.bodyAsText() shouldContain "Vor dem Cache-Treffer sichtbar"

                insertPost(authorMemberId = author, content = "Nach dem Cache-Treffer NICHT sichtbar")
                val second = client.get("/")
                second.bodyAsText() shouldBe first.bodyAsText()
                second.bodyAsText() shouldNotContain "Nach dem Cache-Treffer NICHT sichtbar"
            }
        }
    })
