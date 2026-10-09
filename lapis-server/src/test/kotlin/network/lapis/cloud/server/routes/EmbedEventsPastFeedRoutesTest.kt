package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.82 -- `GET /api/embed/v1/events/past` (archive feed). The server clock is pinned to the year 2040 so that "past" is
 * deterministic and no other spec's leftover rows can interleave (all archive fixtures end in 2039).
 */
class EmbedEventsPastFeedRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val pinned = "2040-06-01T10:00:00Z"

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        val organizer: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EmbedEventsPastFeedRoutesTest Mitglied"
                    it[email] = "embed-past-feed-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            id
        }

        fun createEvent(
            endsAt: LocalDateTime,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
            title: String = "Archiv-Test-Event",
            id: Uuid = Uuid.random(),
            onlineUrl: String? = "https://meet.example/archive",
            onlineUrlPublic: Boolean = true,
            capacity: Int? = 5,
        ): String {
            val slug = "embed-past-feed-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[description] = "Beschreibung\r\nmit Umbruch"
                    it[locationText] = "Archivort"
                    it[EventTable.onlineUrl] = onlineUrl
                    it[EventTable.onlineUrlPublic] = onlineUrlPublic
                    it[startsAt] = endsAt
                    it[EventTable.endsAt] = endsAt
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal("0.00")
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
            pastFeedRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            preflightRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedEventsFeedRoutes(
                            config = enabledConfig,
                            baseUrl = "https://lapis.example",
                            feedRateLimiter = generousLimiter(),
                            preflightRateLimiter = preflightRateLimiter,
                            pastFeedRateLimiter = pastFeedRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        suspend fun ApplicationTestBuilder.past(query: String = ""): HttpResponse =
            client.get("/api/embed/v1/events/past$query") { header(HttpHeaders.Origin, "https://partei.example") }

        fun JsonObject.slugs() = this["events"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }

        fun body(text: String) = Json.parseToJsonElement(text).jsonObject

        test("defaults: page 1, limit 20, newest ending first, envelope with page/limit/hasMore") {
            val older = createEvent(endsAt = LocalDateTime(2039, 1, 1, 10, 0))
            val newer = createEvent(endsAt = LocalDateTime(2039, 6, 1, 10, 0))
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    val response = past()
                    response.status shouldBe HttpStatusCode.OK
                    val json = body(response.bodyAsText())
                    json["page"]!!.jsonPrimitive.int shouldBe 1
                    json["limit"]!!.jsonPrimitive.int shouldBe 20
                    val slugs = json.slugs()
                    (slugs.indexOf(newer) in 0 until slugs.indexOf(older)) shouldBe true
                }
            }
        }

        test("items carry description and never full or onlineUrl, even with an opted-in link") {
            val slug = createEvent(endsAt = LocalDateTime(2039, 3, 3, 10, 0))
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    val text = past().bodyAsText()
                    val item = body(text)["events"]!!.jsonArray.map { it.jsonObject }.single { it["slug"]!!.jsonPrimitive.content == slug }
                    item["description"]!!.jsonPrimitive.content shouldBe "Beschreibung\nmit Umbruch"
                    item["registrationUrl"]!!.jsonPrimitive.content shouldBe "https://lapis.example/veranstaltung/$slug"
                    item.containsKey("full") shouldBe false
                    item.containsKey("onlineUrl") shouldBe false
                    text.contains("meet.example") shouldBe false
                }
            }
        }

        test("members-only, draft and cancelled events never appear in the archive") {
            val publicSlug = createEvent(endsAt = LocalDateTime(2039, 4, 1, 10, 0))
            val members = createEvent(endsAt = LocalDateTime(2039, 4, 1, 10, 0), visibility = EventVisibility.MEMBERS_ONLY)
            val draft = createEvent(endsAt = LocalDateTime(2039, 4, 1, 10, 0), status = EventStatus.DRAFT)
            val cancelled = createEvent(endsAt = LocalDateTime(2039, 4, 1, 10, 0), status = EventStatus.CANCELLED)
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    val slugs = body(past("?limit=50").bodyAsText()).slugs()
                    (publicSlug in slugs) shouldBe true
                    (members in slugs) shouldBe false
                    (draft in slugs) shouldBe false
                    (cancelled in slugs) shouldBe false
                }
            }
        }

        test("invalid parameters yield 400 with a fixed JSON error body") {
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    for (query in listOf(
                        "?page=0",
                        "?page=101",
                        "?limit=0",
                        "?limit=51",
                        "?limit=abc",
                        "?page=-1",
                        "?page=1.5",
                        "?page=",
                        "?limit=99999999999",
                    )) {
                        val response = past(query)
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.bodyAsText() shouldBe """{"error":"invalid_parameter"}"""
                    }
                    past("?page=100&limit=50").status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("paging: exact hasMore boundary and a stable order for entries with the same end, across page boundaries") {
            // Clock pinned to 2000-01-01: ONLY these five 1999 fixtures are "past", so every count below is exact.
            val ends = LocalDateTime(1999, 12, 31, 23, 0)
            val ids = List(5) { Uuid.random() }
            ids.forEach { createEvent(endsAt = ends, id = it) }
            val expectedOrder = ids.sortedByDescending { it.toString() }.map { "embed-past-feed-test-$it" }
            TimeTestSupport.withServerClock(instant = "2000-01-01T10:00:00Z") {
                testApp {
                    val exact = body(past("?limit=5").bodyAsText())
                    exact.slugs() shouldBe expectedOrder
                    exact["hasMore"]!!.jsonPrimitive.boolean shouldBe false

                    val partial = body(past("?limit=4").bodyAsText())
                    partial.slugs() shouldBe expectedOrder.take(4)
                    partial["hasMore"]!!.jsonPrimitive.boolean shouldBe true
                    val rest = body(past("?limit=4&page=2").bodyAsText())
                    rest.slugs() shouldBe expectedOrder.drop(4)
                    rest["hasMore"]!!.jsonPrimitive.boolean shouldBe false
                    rest["page"]!!.jsonPrimitive.int shouldBe 2

                    val walked = mutableListOf<String>()
                    for (page in 1..3) walked += body(past("?limit=2&page=$page").bodyAsText()).slugs()
                    walked shouldBe expectedOrder

                    val beyond = body(past("?limit=5&page=2").bodyAsText())
                    beyond.slugs() shouldBe emptyList()
                    beyond["hasMore"]!!.jsonPrimitive.boolean shouldBe false
                }
            }
        }

        test("boundary: an event ending exactly now is in the archive, not in the upcoming feed") {
            // 2040-06-01T10:00:00Z is 12:00 in Europe/Berlin (summer time) -- the organization wall clock.
            val slug = createEvent(endsAt = LocalDateTime(2040, 6, 1, 12, 0))
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    (slug in body(past("?limit=50").bodyAsText()).slugs()) shouldBe true
                    val upcoming = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                    (upcoming.bodyAsText().contains(slug)) shouldBe false
                }
            }
        }

        test("a disallowed origin gets 403, no origin gets 200") {
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    client.get("/api/embed/v1/events/past") { header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.get("/api/embed/v1/events/past").status shouldBe HttpStatusCode.OK
                    past().headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://partei.example"
                }
            }
        }

        test("rate limit: 429 with Retry-After, from its own limiter") {
            val strict = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp(pastFeedRateLimiter = strict) {
                    past().status shouldBe HttpStatusCode.OK
                    val second = past()
                    second.status shouldBe HttpStatusCode.TooManyRequests
                    second.headers[HttpHeaders.RetryAfter] shouldNotBe null
                }
            }
        }

        test("exactly one Cache-Control: no-store and one nosniff") {
            TimeTestSupport.withServerClock(instant = pinned) {
                testApp {
                    val response = past()
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                    response.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")
                }
            }
        }

        test("OPTIONS preflight answers 204 for an allowed origin and 403 for a bad one") {
            testApp {
                val ok = client.options("/api/embed/v1/events/past") { header(HttpHeaders.Origin, "https://partei.example") }
                ok.status shouldBe HttpStatusCode.NoContent
                ok.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "GET, OPTIONS"
                client.options("/api/embed/v1/events/past") { header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }
    })
