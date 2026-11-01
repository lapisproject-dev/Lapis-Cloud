package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partner.FakeWorld
import network.lapis.cloud.server.partner.PartnerDisplayEpoch
import network.lapis.cloud.server.partner.PartnerEventDisplay
import network.lapis.cloud.server.partner.PartnerEventView
import network.lapis.cloud.server.partner.addDirectPartner
import network.lapis.cloud.server.partner.feedTime
import network.lapis.cloud.server.partner.partnerPublicApp
import network.lapis.cloud.server.partner.realNow
import network.lapis.cloud.server.partner.tickWorld
import network.lapis.cloud.server.partner.wipePartnerTables
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.time.OrganizationTimeZone
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ALLOWED = "https://partei.example"
private val PARTNER_KEYS =
    setOf(
        "title",
        "startsAt",
        "endsAt",
        "timezone",
        "locationText",
        "online",
        "summary",
        "seriesText",
        "url",
        "originName",
        "originHost",
        "via",
    )

/** V1.9.248 -- `GET /api/embed/v1/events?quelle=alle`: the separate `partnerEvents` array, read-time filtering, headers and limits. */
class EmbedEventsFeedPartnerTest :
    FunSpec({
        val json = Json

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            wipePartnerTables()
            PartnerDisplayEpoch.bump()
        }
        afterSpec { wipePartnerTables() }

        fun generous() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val config =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = ALLOWED, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        fun Route.feedRoutes(limiter: FederationInboxRateLimiter = generous()) =
            registerEmbedEventsFeedRoutes(
                config = config,
                baseUrl = "https://lapis.example",
                feedRateLimiter = limiter,
                preflightRateLimiter = generous(),
                pastFeedRateLimiter = generous(),
            )

        suspend fun feedApp(
            limiter: FederationInboxRateLimiter = generous(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application { routing { feedRoutes(limiter) } }
            block()
        }

        suspend fun ApplicationTestBuilder.feed(query: String = "?quelle=alle"): JsonObject =
            json
                .parseToJsonElement(
                    client.get("/api/embed/v1/events$query") { header(HttpHeaders.Origin, ALLOWED) }.bodyAsText(),
                ).jsonObject

        fun JsonObject.partners(): List<JsonObject> = getValue("partnerEvents").jsonArray.map { it.jsonObject }

        fun JsonObject.titles(): List<String> = partners().map { it.getValue("title").jsonPrimitive.content }

        /** a (direct, "Alpha") -> b (hop 2, "Bravo", one event). */
        fun seedWorld(
            events: List<String> = listOf("e1"),
            title: (String) -> String = { "Treffen $it" },
            days: Long = 4,
        ): FakeWorld {
            val world = FakeWorld()
            addDirectPartner(
                host = "a.beispiel.de",
                partners = listOf("b.beispiel.de"),
                displayName = "Alpha",
                now = realNow(),
                eventIds = emptyList(),
            )
            world.serve(
                host = "b.beispiel.de",
                name = "Bravo",
                eventIds = events,
                title = title,
                startsAt = feedTime(days = days),
                endsAt = feedTime(days = days, hour = 21),
            )
            return world
        }

        test("wantsPartnerEvents is true for exactly one quelle=alle and nothing else") {
            fun w(vararg v: String) = wantsPartnerEvents(Parameters.build { v.forEach { append("quelle", it) } })
            w("alle") shouldBe true
            w("Alle") shouldBe false
            w("eigene") shouldBe false
            w("") shouldBe false
            w("alle", "alle") shouldBe false
            w("xyz", "alle") shouldBe false
            wantsPartnerEvents(Parameters.Empty) shouldBe false
        }

        test("toEmbedPartnerEventItem: invalid zone becomes UTC, name falls back to the host and is cut, bad links drop the event") {
            val t = LocalDateTime(2099, 7, 1, 10, 0, 0)
            val base =
                PartnerEventView(
                    remoteServerId = Uuid.random(),
                    remoteEventId = "x",
                    title = "T",
                    startsAtWall = t,
                    startsAtUtc = t,
                    endsAtUtc = t,
                    online = false,
                    place = "  ",
                    summary = null,
                    recurrenceSentence = null,
                    url = "https://o.beispiel.de/e/x",
                    hops = 2,
                    originHost = "o.beispiel.de",
                    originName = null,
                    rootName = "Root",
                    timeZone = "Nowhere/Land",
                    via = listOf("r.beispiel.de"),
                )
            val item = base.toEmbedPartnerEventItem()!!
            item.timezone shouldBe "UTC"
            item.originName shouldBe "o.beispiel.de"
            item.locationText shouldBe null
            item.startsAt shouldBe "2099-07-01T10:00:00Z"
            base
                .copy(originName = "n".repeat(200))
                .toEmbedPartnerEventItem()!!
                .originName.length shouldBe 81
            base.copy(url = "http://o.beispiel.de/e/x").toEmbedPartnerEventItem() shouldBe null
            base.copy(url = "https://evil.example/e/x").toEmbedPartnerEventItem() shouldBe null
            base.copy(url = "https://u:p@o.beispiel.de/e/x").toEmbedPartnerEventItem() shouldBe null
        }

        test("quelle=alle: partnerEvents sits beside an unchanged events array, with exactly the specified keys") {
            tickWorld(seedWorld())
            feedApp {
                val plain = feed("")
                val all = feed()
                all.keys shouldBe setOf("events", "partnerEvents")
                plain.keys shouldBe setOf("events")
                all.getValue("events") shouldBe plain.getValue("events")
                val p = all.partners().single()
                p.keys shouldBe PARTNER_KEYS - setOf("seriesText")
                p.getValue("online").jsonPrimitive.boolean shouldBe false
                p.getValue("via").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("a.beispiel.de")
                p.getValue("title").jsonPrimitive.content shouldBe "Treffen e1"
                p.getValue("originHost").jsonPrimitive.content shouldBe "b.beispiel.de"
                p.getValue("url").jsonPrimitive.content shouldBe "https://b.beispiel.de/veranstaltung/e1"
                // already UTC: the instant is the one the partner sent, never converted a second time
                val instant =
                    java.time.OffsetDateTime
                        .parse(feedTime(days = 4))
                        .toInstant()
                java.time.Instant.parse(p.getValue("startsAt").jsonPrimitive.content) shouldBe instant
                p
                    .getValue("startsAt")
                    .jsonPrimitive.content
                    .endsWith("Z") shouldBe true
            }
        }

        test("a summer-time partner event keeps its UTC instant") {
            val world = seedWorld()
            val summer =
                java.time.OffsetDateTime
                    .now(
                        java.time.ZoneOffset.UTC,
                    ).plusDays(2)
                    .withHour(19)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)
            val start = summer.withOffsetSameInstant(java.time.ZoneOffset.ofHours(2)).toString()
            world.feeds.clear()
            world.serve(host = "b.beispiel.de", startsAt = start, endsAt = summer.plusHours(2).toString())
            tickWorld(world)
            feedApp {
                val p = feed().partners().single()
                java.time.Instant.parse(p.getValue("startsAt").jsonPrimitive.content) shouldBe summer.toInstant()
            }
        }

        test("without partners the array is empty") {
            feedApp { feed().getValue("partnerEvents") shouldBe JsonArray(emptyList()) }
        }

        test("a block acts on the very next request (read time), for the origin and for a host of the chain") {
            tickWorld(seedWorld())
            feedApp {
                feed().partners() shouldHaveSize 1
                transaction {
                    PartnerBlocklistTable.insert {
                        it[id] = Uuid.random()
                        it[pattern] = "a.beispiel.de"
                        it[createdAt] = realNow()
                    }
                }
                feed().partners() shouldHaveSize 0
                transaction {
                    PartnerBlocklistTable.update({ PartnerBlocklistTable.pattern eq "a.beispiel.de" }) {
                        it[pattern] =
                            "b.beispiel.de"
                    }
                }
                feed().partners() shouldHaveSize 0
                transaction {
                    PartnerBlocklistTable.update({ PartnerBlocklistTable.pattern eq "b.beispiel.de" }) {
                        it[pattern] =
                            "c.beispiel.de"
                    }
                }
                feed().partners() shouldHaveSize 1
            }
        }

        test("an inactive root, a switched-off recursion or a hop beyond the depth hides the event") {
            tickWorld(seedWorld())
            feedApp {
                feed().partners() shouldHaveSize 1
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[partnerRecursionEnabled] =
                            false
                    }
                }
                feed().partners() shouldHaveSize 0
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[partnerRecursionEnabled] =
                            true
                    }
                    PartnerServerTable.update { it[active] = false }
                }
                feed().partners() shouldHaveSize 0
            }
        }

        test("at most 10 events per origin") {
            tickWorld(seedWorld(events = (1..12).map { "e$it" }))
            feedApp { feed().partners() shouldHaveSize 10 }
        }

        test("a link stored with a foreign host, http or user info never leaves the server") {
            tickWorld(seedWorld(events = listOf("e1", "e2", "e3", "e4")))

            fun set(
                id: String,
                url: String,
            ) = transaction { PartnerEventTable.update({ PartnerEventTable.remoteEventId eq id }) { it[PartnerEventTable.url] = url } }
            set("e2", "https://evil.example/x")
            set("e3", "http://b.beispiel.de/x")
            set("e4", "https://u:p@b.beispiel.de/x")
            feedApp {
                val body = client.get("/api/embed/v1/events?quelle=alle").bodyAsText()
                feed().titles() shouldBe listOf("Treffen e1")
                body shouldNotContain "evil.example"
                body shouldNotContain "u:p@"
            }
        }

        test("hostile text is passed on as escaped JSON text, never as markup, and there is no online link") {
            tickWorld(seedWorld(title = { "<img src=x onerror=alert(1)>" }))
            feedApp {
                val raw = client.get("/api/embed/v1/events?quelle=alle") { header(HttpHeaders.Origin, ALLOWED) }.bodyAsText()
                val p =
                    json
                        .parseToJsonElement(raw)
                        .jsonObject
                        .partners()
                        .single()
                p
                    .getValue("title")
                    .jsonPrimitive.content
                    .contains("<img") shouldBe true
                p.keys.none { it.contains("onlineUrl", ignoreCase = true) } shouldBe true
            }
        }

        test("headers: foreign origin 403, allowed origin echoed, no-store and nosniff exactly once, one shared 429 budget") {
            tickWorld(seedWorld())
            feedApp(limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                client.get("/api/embed/v1/events?quelle=alle") { header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe
                    HttpStatusCode.Forbidden
                val plain = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, ALLOWED) }
                val all = client.get("/api/embed/v1/events?quelle=alle") { header(HttpHeaders.Origin, ALLOWED) }
                all.status shouldBe HttpStatusCode.OK
                all.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe plain.headers[HttpHeaders.AccessControlAllowOrigin]
                all.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                all.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")
                val limited = client.get("/api/embed/v1/events?quelle=alle") { header(HttpHeaders.Origin, ALLOWED) }
                limited.status shouldBe HttpStatusCode.TooManyRequests
                (limited.headers[HttpHeaders.RetryAfter] != null) shouldBe true
            }
        }

        test(
            "an event beyond the 366-day receive window reaches neither /veranstaltungen nor the embed feed; the order equals loadVisible",
        ) {
            tickWorld(seedWorld(days = 400))
            partnerPublicApp(extraRoutes = { feedRoutes() }) {
                feed().partners() shouldHaveSize 0
                client.get("/veranstaltungen").bodyAsText() shouldNotContain "Treffen e1"
            }
            wipePartnerTables()
            tickWorld(seedWorld(events = listOf("e1", "e2", "e3"), days = 5))
            partnerPublicApp(extraRoutes = { feedRoutes() }) {
                val expected =
                    transaction {
                        PartnerEventDisplay.loadVisible(nowUtc = realNow(), orgZone = OrganizationTimeZone.current()).map { it.title }
                    }
                feed().titles() shouldBe expected
                client.get("/veranstaltungen").bodyAsText() shouldContain "Treffen e1"
            }
        }
    })
