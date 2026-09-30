package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.chapters.ChapterCrestFormat
import network.lapis.cloud.server.chapters.ChapterCrestStorage
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import kotlin.time.Duration.Companion.minutes

private const val ORIGIN = "https://partei.example"
private const val BASE = "https://lapis.example"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- `GET /api/embed/v1/board`, `/politicians`, `/chapters` and
 * their `OPTIONS` preflights. The load-bearing tests are the EXACT key-set comparisons on the raw
 * JSON (nested objects included) and the negative string scans: the feeds are an allowlist mapping,
 * so a field that is not in the expected set can never leak, whatever the domain model grows later.
 */
class EmbedProfilesFeedRoutesTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()
        val photoStorage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("embed-profiles-feed"))
        val crestStorage = ChapterCrestStorage(MemberPhotoFixtures.freshRoot("embed-profiles-crest"))

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup(photoStorage = photoStorage, crestStorage = crestStorage) }
        beforeTest { fixtures.neutralizeForeignProfiles() }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = ORIGIN, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun testApp(
            feedLimiter: FederationInboxRateLimiter = generousLimiter(),
            preflightLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedProfilesFeedRoutes(
                            config = enabledConfig,
                            baseUrl = BASE,
                            feedRateLimiter = feedLimiter,
                            preflightRateLimiter = preflightLimiter,
                        )
                    }
                }
                block()
            }
        }

        suspend fun ApplicationTestBuilder.feed(path: String): HttpResponse = client.get(path) { header(HttpHeaders.Origin, ORIGIN) }

        fun itemsOf(
            body: String,
            key: String,
        ): JsonArray =
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue(key)
                .jsonArray

        fun JsonObject.keys(): Set<String> = keys

        // ── board ─────────────────────────────────────────────────────────────────

        test("board: exact key set per item -- name, role, roleLabel, photoUrl, bio, nothing else (raw JSON, nothing nested)") {
            val member = fixtures.newMember(displayName = "Vera Vorsitz")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            val token = fixtures.seedPhoto(storage = photoStorage, memberId = member, publish = true)!!
            fixtures.seedBio(memberId = member, text = "Kurztext Vorstand", publish = true)
            testApp {
                val response = feed("/api/embed/v1/board")
                response.status shouldBe HttpStatusCode.OK
                val raw = response.bodyAsText()
                Json.parseToJsonElement(raw).jsonObject.keys shouldBe setOf("board")
                val item = itemsOf(raw, "board").map { it.jsonObject }.single()
                item.keys() shouldBe setOf("name", "role", "roleLabel", "photoUrl", "bio")
                item.getValue("name").jsonPrimitive.content shouldBe "Vera Vorsitz"
                item.getValue("role").jsonPrimitive.content shouldBe "CHAIR"
                item.getValue("roleLabel").jsonPrimitive.content shouldBe "Vorsitz"
                item.getValue("photoUrl").jsonPrimitive.content shouldBe "$BASE/public/member-photos/$token"
                item.getValue("bio").jsonPrimitive.content shouldBe "Kurztext Vorstand"
                // The raw string never carries an id, an e-mail, a date or a number about the person.
                for (forbidden in listOf(
                    "\"id\"",
                    "memberId",
                    "email",
                    "since",
                    "joinedAt",
                    "trust",
                    "like",
                    "count",
                    "weight",
                    member.toString(),
                )) {
                    raw shouldNotContain forbidden
                }
            }
        }

        test("board: without a photo and without a bio those two keys are simply absent (explicitNulls = false)") {
            val member = fixtures.newMember(displayName = "Schlicht Sven")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.MEMBER)
            testApp {
                val item = itemsOf(feed("/api/embed/v1/board").bodyAsText(), "board").map { it.jsonObject }.single()
                item.keys() shouldBe setOf("name", "role", "roleLabel")
            }
        }

        test("board: a private photo, a private bio and a bio under an old consent wording do not appear; a withdrawn member is gone") {
            val hiddenBio = fixtures.newMember(displayName = "Nur Name")
            fixtures.addBoardSeat(memberId = hiddenBio, role = CommitteeRole.CHAIR)
            fixtures.seedBio(memberId = hiddenBio, text = "GEHEIMER-TEXT", publish = false)
            fixtures.seedPhoto(storage = photoStorage, memberId = hiddenBio, publish = false)
            val oldConsent = fixtures.newMember(displayName = "Alte Zustimmung")
            fixtures.addBoardSeat(memberId = oldConsent, role = CommitteeRole.DEPUTY_CHAIR)
            fixtures.seedBio(memberId = oldConsent, text = "ALTER-TEXT", publish = true, consentVersion = "member-bio-public-v0")
            val gone = fixtures.newMember(displayName = "Ausgetreten Anton")
            fixtures.addBoardSeat(memberId = gone, role = CommitteeRole.ASSESSOR)
            fixtures.setStatus(memberId = gone, status = network.lapis.cloud.shared.domain.MemberStatus.WITHDRAWN)
            testApp {
                val raw = feed("/api/embed/v1/board").bodyAsText()
                val names =
                    itemsOf(raw, "board").map {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content
                    }
                names shouldBe listOf("Nur Name", "Alte Zustimmung")
                raw shouldNotContain "GEHEIMER-TEXT"
                raw shouldNotContain "ALTER-TEXT"
                raw shouldNotContain "photoUrl"
            }
        }

        test("board: the order is the page's order (role rank) and an empty board is an empty array, not an error") {
            testApp {
                val empty = feed("/api/embed/v1/board")
                empty.status shouldBe HttpStatusCode.OK
                itemsOf(empty.bodyAsText(), "board").size shouldBe 0
            }
            val second = fixtures.newMember(displayName = "Zweite")
            val first = fixtures.newMember(displayName = "Erste")
            fixtures.addBoardSeat(memberId = second, role = CommitteeRole.ASSESSOR)
            fixtures.addBoardSeat(memberId = first, role = CommitteeRole.CHAIR)
            testApp {
                itemsOf(feed("/api/embed/v1/board").bodyAsText(), "board")
                    .map {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content
                    } shouldBe listOf("Erste", "Zweite")
            }
        }

        // ── politicians ───────────────────────────────────────────────────────────

        test("politicians: exact key set -- name, office, photoUrl, bio -- and no ranking, trust or like figure anywhere in the raw JSON") {
            val member = fixtures.newMember(displayName = "Paula Politikerin")
            fixtures.makePolitician(memberId = member, mandateText = "Abgeordnete des Landtags")
            fixtures.grantConsent(memberId = member)
            val token = fixtures.seedPhoto(storage = photoStorage, memberId = member, publish = true)!!
            fixtures.seedBio(memberId = member, text = "Politiker-Text", publish = true)
            testApp {
                val raw = feed("/api/embed/v1/politicians").bodyAsText()
                Json.parseToJsonElement(raw).jsonObject.keys shouldBe setOf("politicians")
                val item = itemsOf(raw, "politicians").map { it.jsonObject }.single()
                item.keys() shouldBe setOf("name", "office", "photoUrl", "bio")
                item.getValue("office").jsonPrimitive.content shouldBe "Abgeordnete des Landtags"
                item.getValue("photoUrl").jsonPrimitive.content shouldBe "$BASE/public/member-photos/$token"
                for (forbidden in listOf(
                    "trust",
                    "Trust",
                    "like",
                    "Like",
                    "weight",
                    "rank",
                    "score",
                    "\"id\"",
                    "memberId",
                    "email",
                    member.toString(),
                )) {
                    raw shouldNotContain forbidden
                }
            }
        }

        test("politicians: only with an effective listing consent, an ACTIVE profile and an ACTIVE member") {
            val ok = fixtures.newMember(displayName = "Sichtbar Susi")
            fixtures.makePolitician(memberId = ok)
            fixtures.grantConsent(memberId = ok)
            val noConsent = fixtures.newMember(displayName = "Ohne Zustimmung")
            fixtures.makePolitician(memberId = noConsent)
            val stale = fixtures.newMember(displayName = "Alte Fassung")
            fixtures.makePolitician(memberId = stale)
            fixtures.grantConsent(memberId = stale, stale = true)
            val former = fixtures.newMember(displayName = "Ehemalig")
            fixtures.makePolitician(memberId = former, status = PoliticianProfileStatus.FORMER)
            fixtures.grantConsent(memberId = former)
            testApp {
                val names =
                    itemsOf(feed("/api/embed/v1/politicians").bodyAsText(), "politicians")
                        .map {
                            it.jsonObject
                                .getValue("name")
                                .jsonPrimitive.content
                        }
                names shouldBe listOf("Sichtbar Susi")
            }
        }

        // ── chapters ──────────────────────────────────────────────────────────────

        test("chapters: an SVG crest is exposed as the same extension-less token URL, never inline or as a data URI") {
            val svg = fixtures.newChapter(name = "Landesverband Vektor")
            fixtures.seedCrest(storage = crestStorage, chapterId = svg, format = ChapterCrestFormat.SVG)
            testApp {
                val raw = feed("/api/embed/v1/chapters").bodyAsText()
                val vektor =
                    itemsOf(raw, "chapters").map { it.jsonObject }.single {
                        it.getValue("name").jsonPrimitive.content ==
                            "Landesverband Vektor"
                    }
                vektor.getValue("crestUrl").jsonPrimitive.content shouldMatch
                    Regex("^" + Regex.escape(BASE) + "/public/chapter-crests/[A-Za-z0-9_-]{43}$")
                raw shouldNotContain "data:"
                raw shouldNotContain "<svg"
            }
        }

        test("chapters: exact key set -- name, crestUrl, description -- and a chapter without crest/description has only its name") {
            val full = fixtures.newChapter(name = "Landesverband Nord", description = "Beschreibung Nord")
            val token = fixtures.seedCrest(storage = crestStorage, chapterId = full)
            fixtures.newChapter(name = "Landesverband Sued")
            testApp {
                val raw = feed("/api/embed/v1/chapters").bodyAsText()
                Json.parseToJsonElement(raw).jsonObject.keys shouldBe setOf("chapters")
                val items = itemsOf(raw, "chapters").map { it.jsonObject }
                val nord = items.single { it.getValue("name").jsonPrimitive.content == "Landesverband Nord" }
                nord.keys() shouldBe setOf("name", "crestUrl", "description")
                nord.getValue("crestUrl").jsonPrimitive.content shouldBe "$BASE/public/chapter-crests/$token"
                val sued = items.single { it.getValue("name").jsonPrimitive.content == "Landesverband Sued" }
                sued.keys() shouldBe setOf("name")
                for (forbidden in listOf(
                    "\"id\"",
                    "crestImageId",
                    "crestPublicToken",
                    "crestContentType",
                    "nameKey",
                    "createdAt",
                    "memberCount",
                )) {
                    raw shouldNotContain forbidden
                }
            }
        }

        // ── shared behaviour of all three feeds ───────────────────────────────────

        val paths = listOf("/api/embed/v1/board", "/api/embed/v1/politicians", "/api/embed/v1/chapters")

        test(
            "CORS: an allowed Origin is echoed canonically, a missing Origin is a normal 200, a foreign Origin is 403 without any CORS header",
        ) {
            testApp {
                for (path in paths) {
                    val allowed = feed(path)
                    allowed.status shouldBe HttpStatusCode.OK
                    allowed.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
                    allowed.headers[HttpHeaders.Vary] shouldBe "Origin"
                    allowed.headers["X-Content-Type-Options"] shouldBe "nosniff"

                    client.get(path).status shouldBe HttpStatusCode.OK

                    val rejected = client.get(path) { header(HttpHeaders.Origin, "https://evil.example") }
                    rejected.status shouldBe HttpStatusCode.Forbidden
                    rejected.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                }
            }
        }

        test("Cache-Control: no-store occurs EXACTLY once per response -- applyEmbedCors sets it, nothing appends a second one") {
            testApp {
                for (path in paths) {
                    feed(path).headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                    client.get(path).headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }

        test("a withdrawn consent vanishes from the feed on the very next request") {
            val member = fixtures.newMember(displayName = "Widerruf Willi")
            fixtures.makePolitician(memberId = member)
            fixtures.grantConsent(memberId = member)
            testApp {
                itemsOf(feed("/api/embed/v1/politicians").bodyAsText(), "politicians").size shouldBe 1
                org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                    PublicRankingConsentStore.revoke(
                        memberId = member,
                        kind = network.lapis.cloud.shared.domain.PublicRankingKind.POLITICIAN_LISTING,
                        now =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime(),
                    )
                }
                itemsOf(feed("/api/embed/v1/politicians").bodyAsText(), "politicians").size shouldBe 0
            }
        }

        test("rate limit exhausted -> 429 with Retry-After; the preflight has its own limiter") {
            testApp(feedLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)) {
                feed("/api/embed/v1/board")
                val second = feed("/api/embed/v1/board")
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
            }
            testApp(preflightLimiter = FederationInboxRateLimiter(maxRequests = 0, window = 1.minutes, maxTrackedKeys = 50_000)) {
                client.options("/api/embed/v1/chapters") { header(HttpHeaders.Origin, ORIGIN) }.status shouldBe
                    HttpStatusCode.TooManyRequests
            }
        }

        test("OPTIONS preflight: 204 with GET, OPTIONS for an allowed origin, 403 for a foreign one") {
            testApp {
                for (path in paths) {
                    val preflight = client.options(path) { header(HttpHeaders.Origin, ORIGIN) }
                    preflight.status shouldBe HttpStatusCode.NoContent
                    preflight.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "GET, OPTIONS"
                    client.options(path) { header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("the feeds live inside the LAPIS_EMBED_ENABLED gate: enabled -> registered via registerEmbedRoutes, disabled -> 404") {
            fun noOpMailDispatcher() =
                MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            for ((config, expected) in listOf(enabledConfig to HttpStatusCode.OK, EmbedConfig.DISABLED to HttpStatusCode.NotFound)) {
                testApplication {
                    application {
                        routing {
                            registerEmbedRoutes(
                                config = config,
                                assetRateLimiter = generousLimiter(),
                                loginPageRateLimiter = generousLimiter(),
                                sessionRateLimiter = generousLimiter(),
                                adminStatusRateLimiter = generousLimiter(),
                                pspConfigState = PspConfigState.NotConfigured,
                                checkoutGateways = emptyMap(),
                                donationCheckoutRateLimiter = generousLimiter(),
                                donationCheckoutAttemptRateLimiter = generousLimiter(),
                                donationPageRateLimiter = generousLimiter(),
                                mailDispatcher = noOpMailDispatcher(),
                                eventRegistrationAttemptRateLimiter = generousLimiter(),
                                eventRegistrationRateLimiter = generousLimiter(),
                                eventPageRateLimiter = generousLimiter(),
                                eventsFeedRateLimiter = generousLimiter(),
                                articlesFeedRateLimiter = generousLimiter(),
                            )
                        }
                    }
                    for (path in paths) {
                        val response = client.get(path) { header(HttpHeaders.Origin, ORIGIN) }
                        response.status shouldBe expected
                        if (expected == HttpStatusCode.OK) response.bodyAsText() shouldContain "["
                    }
                }
            }
        }
    })
