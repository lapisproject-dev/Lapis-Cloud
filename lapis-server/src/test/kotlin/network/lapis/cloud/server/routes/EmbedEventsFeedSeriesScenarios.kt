package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ALLOWED = "https://partei.example"
private val ITEM_KEYS =
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
        "online",
        "seriesText",
    )

/**
 * V1.9.278 -- `GET /api/embed/v1/events?serien=eine` on H2 and on a real PostgreSQL (the representative query uses an id-as-text tiebreak
 * and a `NOT EXISTS` anti-join, both engine-sensitive). Helpers for series and events are adapted from [PublicSeriesScenarios].
 */
abstract class EmbedEventsFeedSeriesScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val json = Json
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdSeriesIds = mutableListOf<Uuid>()
        var idCounter = 0L

        beforeSpec { db.activate() }
        installLaneGuards(db = db)

        beforeTest {
            // Neutralize public events left behind by other specs sharing a process-wide H2 database.
            transaction {
                EventTable.update({
                    (EventTable.visibility eq EventVisibility.PUBLIC) and (EventTable.status eq EventStatus.PUBLISHED)
                }) { it[status] = EventStatus.CANCELLED }
            }
        }

        afterSpec {
            try {
                transaction {
                    if (createdEventIds.isNotEmpty()) {
                        EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                        EventTable.deleteWhere { id inList createdEventIds }
                    }
                    if (createdSeriesIds.isNotEmpty()) EventSeriesTable.deleteWhere { id inList createdSeriesIds }
                    if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
                }
            } finally {
                db.deactivate()
            }
        }

        val organizer: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EmbedEventsFeedSeriesScenarios Mitglied"
                    it[email] = "embed-feed-series-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            id
        }

        val utc = TimeZone.UTC

        fun base(day: Int): LocalDateTime = LocalDateTime(2031, 3, day, 19, 0)

        /** `index` hours after 2031-05-01 00:00 (index may exceed 23). */
        fun later(
            index: Int,
            month: Int = 5,
        ): LocalDateTime = (LocalDateTime(2031, month, 1, 0, 0).toInstant(utc) + index.hours).toLocalDateTime(utc)

        fun createSeries(rrule: String = "FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;COUNT=20"): Uuid {
            val id = Uuid.random()
            transaction {
                EventSeriesTable.insert {
                    it[EventSeriesTable.id] = id
                    it[EventSeriesTable.rrule] = rrule
                    it[dtstart] = base(1)
                    it[timezone] = "Europe/Berlin"
                    it[durationMinutes] = 120
                    it[createdBy] = organizer
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdSeriesIds += id
            return id
        }

        /** Ids share all but the last byte, so only the tiebreak can distinguish them. Returns id to slug. */
        fun createEvent(
            startsAt: LocalDateTime = base(1),
            seriesId: Uuid? = null,
            originalStart: LocalDateTime? = if (seriesId != null) startsAt else null,
            detached: Boolean = false,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
        ): Pair<Uuid, String> {
            val id = Uuid.fromLongs(0x0123456789abcdefL, 0x2000_0000_0000_0000L + (++idCounter))
            val slug = "embed-series-$id"
            val ends = (startsAt.toInstant(utc) + 2.hours).toLocalDateTime(utc)
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[title] = "Serien-Embed $idCounter"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[endsAt] = ends
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[EventTable.visibility] = visibility
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                    it[EventTable.seriesId] = seriesId
                    it[seriesOriginalStart] = originalStart
                    it[seriesDetached] = detached
                }
            }
            createdEventIds += id
            return id to slug
        }

        fun generous() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val config =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = ALLOWED, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        fun Route.feedRoutes(limiter: FederationInboxRateLimiter) =
            registerEmbedEventsFeedRoutes(
                config = config,
                baseUrl = "https://lapis.example",
                feedRateLimiter = limiter,
                preflightRateLimiter = generous(),
                pastFeedRateLimiter = generous(),
            )

        suspend fun rawFeed(
            query: String,
            origin: String = ALLOWED,
            limiter: FederationInboxRateLimiter = generous(),
        ): HttpResponse {
            var response: HttpResponse? = null
            testApplication {
                application { routing { feedRoutes(limiter) } }
                response = client.get("/api/embed/v1/events$query") { header(HttpHeaders.Origin, origin) }
                // Read the body inside the application scope so it is buffered.
                response.bodyAsText()
            }
            return response!!
        }

        suspend fun feedBody(query: String): String {
            var text = ""
            testApplication {
                application { routing { feedRoutes(generous()) } }
                val r = client.get("/api/embed/v1/events$query") { header(HttpHeaders.Origin, ALLOWED) }
                r.status shouldBe HttpStatusCode.OK
                text = r.bodyAsText()
            }
            return text
        }

        suspend fun feedEvents(query: String): List<JsonObject> =
            json
                .parseToJsonElement(feedBody(query))
                .jsonObject
                .getValue("events")
                .jsonArray
                .map { it.jsonObject }

        fun JsonObject.slug() = getValue("slug").jsonPrimitive.content

        fun JsonObject.seriesText() = this["seriesText"]?.jsonPrimitive?.content

        class StatementCounter : SqlLogger {
            var count = 0

            override fun log(
                context: StatementContext,
                transaction: Transaction,
            ) {
                count++
            }
        }

        // ── Unit: parameter parsing ──────────────────────────────────────────────
        test("wantsOneEntryPerSeries: exactly one value, exactly eine") {
            fun p(vararg pairs: Pair<String, List<String>>) = Parameters.build { pairs.forEach { (k, v) -> appendAll(k, v) } }
            wantsOneEntryPerSeries(p("serien" to listOf("eine"))) shouldBe true
            wantsOneEntryPerSeries(p()) shouldBe false
            wantsOneEntryPerSeries(p("serien" to listOf("EINE"))) shouldBe false
            wantsOneEntryPerSeries(p("serien" to listOf(""))) shouldBe false
            wantsOneEntryPerSeries(p("serien" to listOf("alle"))) shouldBe false
            wantsOneEntryPerSeries(p("serien" to listOf("eine", "eine"))) shouldBe false
            wantsOneEntryPerSeries(p("serien[]" to listOf("eine"))) shouldBe false
        }

        // ── Reduction ────────────────────────────────────────────────────────────
        test("a single event stays and has no seriesText key") {
            val (_, slug) = createEvent(startsAt = base(2))
            val body = feedBody("?serien=eine")
            body shouldNotContain "seriesText"
            feedEvents("?serien=eine").map { it.slug() } shouldContainExactly listOf(slug)
        }

        test("a series with four dates is one entry (the next) with its repeat sentence, ordered between single events") {
            val series = createSeries()
            val (_, single1) = createEvent(startsAt = base(1))
            val (_, first) = createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            createEvent(startsAt = base(16), seriesId = series)
            createEvent(startsAt = base(23), seriesId = series)
            val (_, single2) = createEvent(startsAt = base(5))
            val events = feedEvents("?serien=eine")
            events.map { it.slug() } shouldContainExactly listOf(single1, first, single2)
            events.map { it.seriesText() } shouldContainExactly listOf(null, "jeden Dienstag", null)
        }

        test("cancelled, hidden, draft and ended dates are skipped; with none left the series disappears") {
            val series = createSeries()
            createEvent(startsAt = LocalDateTime(2020, 3, 3, 19, 0), seriesId = series)
            createEvent(startsAt = base(2), seriesId = series, status = EventStatus.CANCELLED)
            createEvent(startsAt = base(3), seriesId = series, visibility = EventVisibility.MEMBERS_ONLY)
            createEvent(startsAt = base(4), seriesId = series, status = EventStatus.DRAFT)
            val (_, valid) = createEvent(startsAt = base(9), seriesId = series)
            feedEvents("?serien=eine").map { it.slug() } shouldContainExactly listOf(valid)

            val gone = createSeries()
            createEvent(startsAt = base(10), seriesId = gone, status = EventStatus.CANCELLED)
            createEvent(startsAt = LocalDateTime(2020, 3, 10, 19, 0), seriesId = gone)
            feedEvents("?serien=eine").map { it.slug() } shouldContainExactly listOf(valid)
        }

        test("a detached date stays a member: one entry per series, never both") {
            val series = createSeries()
            val (_, moved) = createEvent(startsAt = base(3), originalStart = base(9), seriesId = series, detached = true)
            createEvent(startsAt = base(10), seriesId = series)
            val events = feedEvents("?serien=eine")
            events shouldHaveSize 1
            events.single().slug() shouldBe moved
            events.single().seriesText() shouldBe "jeden Dienstag"
        }

        test("two dates in the same minute: exactly one, the smaller id") {
            val series = createSeries()
            val (_, smaller) = createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(2), originalStart = base(9), seriesId = series, detached = true)
            feedEvents("?serien=eine").map { it.slug() } shouldContainExactly listOf(smaller)
        }

        test("a series with one remaining date is an entry with seriesText") {
            val series = createSeries()
            createEvent(startsAt = base(2), seriesId = series, status = EventStatus.CANCELLED)
            val (_, only) = createEvent(startsAt = base(9), seriesId = series)
            val events = feedEvents("?serien=eine")
            events.map { it.slug() } shouldContainExactly listOf(only)
            events.single().seriesText() shouldBe "jeden Dienstag"
        }

        test("the cap counts entries, not dates: 51 series with two dates each give the earliest 50 entries") {
            val firsts =
                (0 until 51).map { i ->
                    val s = createSeries()
                    val (_, slug) = createEvent(startsAt = later(i), seriesId = s)
                    createEvent(startsAt = later(i, month = 6), seriesId = s)
                    slug
                }
            val events = feedEvents("?serien=eine")
            events shouldHaveSize 50
            events.map { it.slug() } shouldContainExactly firsts.take(50)
        }

        test("serien=eine together with quelle=alle reduces only the own events, partnerEvents stays an array") {
            val series = createSeries()
            val (_, first) = createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            val root = json.parseToJsonElement(feedBody("?serien=eine&quelle=alle")).jsonObject
            root.getValue("events").jsonArray.map { it.jsonObject.slug() } shouldContainExactly listOf(first)
            root.getValue("partnerEvents").jsonArray.size shouldBe 0
            root.keys shouldBe setOf("events", "partnerEvents")
        }

        test("tripwire: no series id, no original start, no raw rule on the wire; only the known keys") {
            val series = createSeries()
            createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            val body = feedBody("?serien=eine")
            body shouldNotContain "seriesId"
            body shouldNotContain "seriesOriginalStart"
            body shouldNotContain series.toString()
            body shouldNotContain "FREQ="
            body shouldContain "\"seriesText\":\"jeden Dienstag\""
            feedEvents("?serien=eine").forEach { (it.keys - ITEM_KEYS) shouldBe emptySet() }
        }

        test("statements: the two extra queries are constant, independent of the number of series") {
            fun statements(series: Int): Int {
                transaction {
                    EventTable.update({ EventTable.status eq EventStatus.PUBLISHED }) { it[status] = EventStatus.CANCELLED }
                }
                repeat(series) { i ->
                    val s = createSeries()
                    createEvent(startsAt = later(i), seriesId = s)
                    createEvent(startsAt = later(i, month = 6), seriesId = s)
                }
                val counter = StatementCounter()
                transaction {
                    addLogger(counter)
                    val wallNow = DbClock.nowLocalDateTime()
                    val reps = PublicSeriesQueries.loadOverviewRepresentatives(wallNow = wallNow, limit = EmbedEventsFeedLimits.MAX_EVENTS)
                    PublicSeriesQueries.loadSeriesStats(seriesIds = reps.mapNotNull { it[EventTable.seriesId] }, wallNow = wallNow)
                }
                return counter.count
            }
            val few = statements(3)
            val many = statements(15)
            many shouldBe few
        }

        // ── Strictness and the default feed ──────────────────────────────────────
        test("every other spelling is the default feed, byte-identical, without seriesText") {
            val series = createSeries()
            createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            val plain = feedBody("")
            plain shouldNotContain "seriesText"
            feedEvents("") shouldHaveSize 2
            listOf("?serien=EINE", "?serien=", "?serien=eine&serien=eine", "?serien=alle", "?serien[]=eine").forEach { q ->
                feedBody(q) shouldBe plain
            }
            feedBody("?serien=eine") shouldNotBe plain
        }

        // ── Headers, CORS, rate limit ────────────────────────────────────────────
        test("CORS, rate limit and nosniff apply with the parameter") {
            val ok = rawFeed("?serien=eine")
            ok.status shouldBe HttpStatusCode.OK
            ok.headers["X-Content-Type-Options"] shouldBe "nosniff"
            ok.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ALLOWED

            rawFeed("?serien=eine", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden

            val strict = FederationInboxRateLimiter(maxRequests = 0, window = 1.minutes, maxTrackedKeys = 50_000)
            rawFeed("?serien=eine", limiter = strict).status shouldBe HttpStatusCode.TooManyRequests
        }
    })
