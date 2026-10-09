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
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.EventRegistrationStatus
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
 * Welle V1.4.33 "Veranstaltungsliste als Embed-Widget" -- `GET /api/embed/v1/events` and its
 * `OPTIONS` preflight. Mirrors `EmbedEventRoutesTest`'s registration-at-the-
 * `registerEmbedEventsFeedRoutes`-level pattern (not `registerEmbedRoutes`) and reuses
 * `EventIcsFeedTest`/`EmbedEventRoutesTest`'s fixture idioms.
 */
class EmbedEventsFeedRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EmbedEventsFeedRoutesTest Mitglied"
                    it[email] = "embed-events-feed-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createEvent(
            title: String = "Embed-Events-Feed-Test-Event",
            startsAt: LocalDateTime = farFutureStartsAt,
            endsAt: LocalDateTime = farFutureEndsAt,
            locationText: String? = "Testort",
            capacity: Int? = 10,
            feeAmount: BigDecimal = BigDecimal("0.00"),
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
            description: String = "test",
            summary: String? = null,
            coverImageAlt: String? = null,
            coverImageId: Uuid? = null,
            onlineUrl: String? = null,
            onlineUrlPublic: Boolean = false,
        ): Pair<Uuid, String> {
            val organizer = createMember()
            val id = Uuid.random()
            val slug = "embed-events-feed-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[EventTable.description] = description
                    it[EventTable.locationText] = locationText
                    it[EventTable.onlineUrl] = onlineUrl
                    it[EventTable.summary] = summary
                    it[EventTable.coverImageAlt] = coverImageAlt
                    it[EventTable.coverImageId] = coverImageId
                    it[EventTable.onlineUrlPublic] = onlineUrlPublic
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[EventTable.capacity] = capacity
                    it[EventTable.feeAmount] = feeAmount
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

        /** Inserts a CONFIRMED registration directly (no HTTP round trip needed for the `full` test). */
        fun confirmRegistration(eventId: Uuid) {
            val guest = createMember()
            transaction {
                EventRegistrationTable.insert {
                    it[id] = Uuid.random()
                    it[EventRegistrationTable.eventId] = eventId
                    it[memberId] = guest
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = "m:$guest"
                    it[status] = EventRegistrationStatus.CONFIRMED
                    it[feeAmount] = BigDecimal.ZERO
                    it[holdExpiresAt] = null
                    it[waitlistPosition] = null
                    it[cancelTokenSha256] = null
                    it[registeredAt] = DbClock.nowLocalDateTime()
                    it[confirmedAt] = DbClock.nowLocalDateTime()
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
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
            pastFeedRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedEventsFeedRoutes(
                            config = enabledConfig,
                            baseUrl = "https://lapis.example",
                            feedRateLimiter = feedRateLimiter,
                            preflightRateLimiter = preflightRateLimiter,
                            pastFeedRateLimiter = pastFeedRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        /** The event times are wall-clocks of the ORGANIZATION zone (default Europe/Berlin, V1.9.38), converted to UTC -- never the process zone. */
        fun expectedUtcIso(dt: LocalDateTime): String {
            val utc = dt.toInstant(TimeZone.of("Europe/Berlin")).toLocalDateTime(TimeZone.UTC)
            return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(utc.year, utc.monthNumber, utc.dayOfMonth, utc.hour, utc.minute, utc.second)
        }

        fun eventsOf(body: String) = Json.parseToJsonElement(body).jsonObject["events"]!!.jsonArray

        fun eventsOf(
            body: String,
            slug: String,
        ) = eventsOf(body).map { it.jsonObject }.single { it.jsonObject["slug"]!!.jsonPrimitive.content == slug }

        test("200, envelope shape, field values match a seeded event (fee as string, full=false under capacity)") {
            val (_, slug) = createEvent(feeAmount = BigDecimal("12.50"), capacity = 10)
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.OK
                val item = eventsOf(response.bodyAsText(), slug)
                item["title"]!!.jsonPrimitive.content shouldBe "Embed-Events-Feed-Test-Event"
                item["locationText"]!!.jsonPrimitive.content shouldBe "Testort"
                item["registrationUrl"]!!.jsonPrimitive.content shouldBe "https://lapis.example/veranstaltung/$slug"
                item["full"]!!.jsonPrimitive.content shouldBe "false"
                item["feeAmount"]!!.jsonPrimitive.content shouldBe "12.50"
                item["feeCurrency"]!!.jsonPrimitive.content shouldBe "EUR"
                item["coverImageUrl"] shouldBe null
                // Independently computed expectation -- via Instant/TimeZone, never a hardcoded
                // "Z"-suffixed wall-clock value, so this assertion is correct regardless of which
                // zone the test JVM runs in (same reasoning EventIcsFeedTest's own timezone test
                // documents for the sibling iCal feed).
                item["startsAt"]!!.jsonPrimitive.content shouldBe expectedUtcIso(farFutureStartsAt)
                item["endsAt"]!!.jsonPrimitive.content shouldBe expectedUtcIso(farFutureEndsAt)
                // ... and pinned to literals: 18:00 / 22:00 Berlin wall-clock in January is 17:00Z / 21:00Z.
                item["startsAt"]!!.jsonPrimitive.content shouldBe "2030-01-01T17:00:00Z"
                item["endsAt"]!!.jsonPrimitive.content shouldBe "2030-01-01T21:00:00Z"
            }
        }

        test("full=true once countOccupied reaches capacity") {
            val (eventId, slug) = createEvent(capacity = 1)
            confirmRegistration(eventId)
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                val item = eventsOf(response.bodyAsText(), slug)
                item["full"]!!.jsonPrimitive.content shouldBe "true"
            }
        }

        test("visibility: only PUBLIC+PUBLISHED appears -- DRAFT, CANCELLED, MEMBERS_ONLY are excluded") {
            val (_, publicSlug) = createEvent()
            val (_, draftSlug) = createEvent(status = EventStatus.DRAFT)
            val (_, cancelledSlug) = createEvent(status = EventStatus.CANCELLED)
            val (_, membersOnlySlug) = createEvent(visibility = EventVisibility.MEMBERS_ONLY)
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                val slugs = eventsOf(response.bodyAsText()).map { it.jsonObject["slug"]!!.jsonPrimitive.content }
                (publicSlug in slugs) shouldBe true
                (draftSlug in slugs) shouldBe false
                (cancelledSlug in slugs) shouldBe false
                (membersOnlySlug in slugs) shouldBe false
            }
        }

        test("NoOriginHeader -> 200, not 403 -- the one deliberate CORS-posture deviation from the write-path embed routes") {
            val (_, slug) = createEvent()
            testApp {
                val response = client.get("/api/embed/v1/events")
                response.status shouldBe HttpStatusCode.OK
                eventsOf(response.bodyAsText(), slug) // does not throw -- the event is present
            }
        }

        test("disallowed Origin -> 403, no Access-Control-Allow-Origin, no echo of the seen origin") {
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://evil.example") }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                response.bodyAsText().contains("evil.example") shouldBe false
            }
        }

        test("allowed Origin -> Access-Control-Allow-Origin echoes the canonical allowlist entry") {
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://partei.example"
            }
        }

        test("rate limit exhausted -> 429 with Retry-After header") {
            val strict = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(feedRateLimiter = strict) {
                client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                val second = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
            }
        }

        test("Cache-Control: no-store and X-Content-Type-Options: nosniff are present") {
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")
            }
        }

        test("ordering: results are ascending by startsAt") {
            val (_, laterSlug) = createEvent(startsAt = LocalDateTime(2031, 6, 1, 10, 0), endsAt = LocalDateTime(2031, 6, 1, 12, 0))
            val (_, earlierSlug) = createEvent(startsAt = LocalDateTime(2031, 1, 1, 10, 0), endsAt = LocalDateTime(2031, 1, 1, 12, 0))
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                val slugs = eventsOf(response.bodyAsText()).map { it.jsonObject["slug"]!!.jsonPrimitive.content }
                val earlierIndex = slugs.indexOf(earlierSlug)
                val laterIndex = slugs.indexOf(laterSlug)
                (earlierIndex >= 0 && laterIndex >= 0) shouldBe true
                (earlierIndex < laterIndex) shouldBe true
            }
        }

        test("OPTIONS preflight: 204, correct Access-Control-Allow-Methods, rejects a bad origin") {
            testApp {
                val preflight = client.options("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                preflight.status shouldBe HttpStatusCode.NoContent
                preflight.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "GET, OPTIONS"

                val badOrigin = client.options("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://evil.example") }
                badOrigin.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("OPTIONS preflight rate limit exhausted -> 429") {
            val strict = FederationInboxRateLimiter(maxRequests = 0, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(preflightRateLimiter = strict) {
                val response = client.options("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("always a valid envelope even with zero matching rows in this test app's own view (events key present)") {
            testApp {
                val response = client.get("/api/embed/v1/events") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("events") shouldBe true
            }
        }

        // ── V1.9.82: full text, summary, alt text, online link -- and what must NOT leave the server ──

        val allowedKeys =
            setOf(
                "slug",
                "title",
                "startsAt",
                "endsAt",
                "locationText",
                "registrationUrl",
                "full",
                "feeAmount",
                "feeCurrency",
                "coverImageUrl",
                "description",
                "summary",
                "coverImageAlt",
                "onlineUrl",
            )

        test("V1.9.82: description is always present and normalized (CRLF, control characters, blank-line runs)") {
            val (_, slug) = createEvent(description = "Erste Zeile\r\nZweite\u0000 Zeile\n\n\n\nAbsatz zwei  ")
            testApp {
                val item = eventsOf(client.get("/api/embed/v1/events").bodyAsText(), slug)
                item["description"]!!.jsonPrimitive.content shouldBe "Erste Zeile\nZweite Zeile\n\nAbsatz zwei"
            }
        }

        test("V1.9.82: summary is output single-line and omitted when absent") {
            val (_, withSummary) = createEvent(summary = "Kurz\nund knapp")
            val (_, without) = createEvent()
            testApp {
                val body = client.get("/api/embed/v1/events").bodyAsText()
                eventsOf(body, withSummary)["summary"]!!.jsonPrimitive.content shouldBe "Kurz und knapp"
                eventsOf(body, without).containsKey("summary") shouldBe false
            }
        }

        test("V1.9.82: coverImageAlt only appears together with a cover image") {
            val (_, withCover) = createEvent(coverImageId = Uuid.random(), coverImageAlt = "Ein Plakat")
            val (_, noCover) = createEvent(coverImageAlt = "Ein Plakat ohne Bild")
            testApp {
                val body = client.get("/api/embed/v1/events").bodyAsText()
                eventsOf(body, withCover)["coverImageAlt"]!!.jsonPrimitive.content shouldBe "Ein Plakat"
                eventsOf(body, withCover)["coverImageUrl"] shouldNotBe null
                eventsOf(body, noCover).containsKey("coverImageAlt") shouldBe false
            }
        }

        test("V1.9.82: onlineUrl only with explicit opt-in AND https") {
            val (_, optedIn) = createEvent(onlineUrl = "https://meet.example/abc", onlineUrlPublic = true)
            val (_, notOptedIn) = createEvent(onlineUrl = "https://meet.example/secret", onlineUrlPublic = false)
            val (_, httpOptedIn) = createEvent(onlineUrl = "http://meet.example/plain", onlineUrlPublic = true)
            val (_, optedInNoLink) = createEvent(onlineUrl = null, onlineUrlPublic = true)
            testApp {
                val body = client.get("/api/embed/v1/events").bodyAsText()
                eventsOf(body, optedIn)["onlineUrl"]!!.jsonPrimitive.content shouldBe "https://meet.example/abc"
                eventsOf(body, notOptedIn).containsKey("onlineUrl") shouldBe false
                eventsOf(body, httpOptedIn).containsKey("onlineUrl") shouldBe false
                eventsOf(body, optedInNoLink).containsKey("onlineUrl") shouldBe false
                body.contains("meet.example/secret") shouldBe false
            }
        }

        test("V1.9.82: backward compatible -- every pre-existing key keeps its name and type, full stays mandatory") {
            val (_, slug) = createEvent(feeAmount = BigDecimal("5.00"), coverImageId = Uuid.random())
            testApp {
                val item = eventsOf(client.get("/api/embed/v1/events").bodyAsText(), slug)
                for (key in listOf(
                    "slug",
                    "title",
                    "startsAt",
                    "endsAt",
                    "locationText",
                    "registrationUrl",
                    "feeAmount",
                    "feeCurrency",
                    "coverImageUrl",
                )) {
                    item[key]!!.jsonPrimitive.isString shouldBe true
                }
                item["full"]!!.jsonPrimitive.isString shouldBe false
                item["full"]!!.jsonPrimitive.content shouldBe "false"
            }
        }

        test("V1.9.82: privacy whitelist -- only known keys, no person data of any kind") {
            val (eventId, slug) =
                createEvent(
                    summary = "s",
                    coverImageId = Uuid.random(),
                    coverImageAlt = "a",
                    onlineUrl = "https://m.example/x",
                    onlineUrlPublic = true,
                )
            confirmRegistration(eventId)
            testApp {
                val response = client.get("/api/embed/v1/events").bodyAsText()
                val item = eventsOf(response, slug)
                (item.keys - allowedKeys) shouldBe emptySet()
                for (forbidden in listOf(
                    "createdBy",
                    "createdAt",
                    "imported",
                    "memberId",
                    "email",
                    "guestEmail",
                    "roomId",
                    "capacity",
                    "status",
                    "visibility",
                )) {
                    response.contains("\"$forbidden\"") shouldBe false
                }
            }
        }
    })
