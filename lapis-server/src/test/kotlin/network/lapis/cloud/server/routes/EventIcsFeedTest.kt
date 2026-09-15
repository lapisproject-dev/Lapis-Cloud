package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1c "iCal-Feed für öffentliche Veranstaltungen" -- covers both [EventIcsFeed] directly
 * (query filtering, RFC-5545 text shape, escaping, line folding, timezone conversion) and the
 * `GET /veranstaltung.ics` route (content type, caching, rate limiting).
 */
class EventIcsFeedTest :
    FunSpec({
        val createdEventIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
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
                    it[displayName] = "EventIcsFeedTest Mitglied"
                    it[email] = "event-ics-feed-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun createEvent(
            title: String = "Ics-Feed-Test-Event",
            description: String = "test",
            locationText: String? = "Testort",
            startsAt: LocalDateTime,
            endsAt: LocalDateTime,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
        ): Pair<Uuid, String> {
            val organizer = createMember()
            val id = Uuid.random()
            val slug = "ics-feed-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[EventTable.description] = description
                    it[EventTable.locationText] = locationText
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

        val farFuture = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEnd = LocalDateTime(2030, 1, 1, 22, 0)
        val farPast = LocalDateTime(2020, 1, 1, 18, 0)
        val farPastEnd = LocalDateTime(2020, 1, 1, 22, 0)

        // ── EventIcsFeed.loadUpcomingPublicPublished -- scope/filtering ─────────────────────────

        test("PUBLIC+PUBLISHED future event is included") {
            val (id, _) = createEvent(startsAt = farFuture, endsAt = farFutureEnd)
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }
            (id in rows.map { it[EventTable.id] }) shouldBe true
        }

        test("MEMBERS_ONLY+PUBLISHED is excluded") {
            val (id, _) = createEvent(startsAt = farFuture, endsAt = farFutureEnd, visibility = EventVisibility.MEMBERS_ONLY)
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }
            (id in rows.map { it[EventTable.id] }) shouldBe false
        }

        test("PUBLIC+DRAFT is excluded") {
            val (id, _) = createEvent(startsAt = farFuture, endsAt = farFutureEnd, status = EventStatus.DRAFT)
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }
            (id in rows.map { it[EventTable.id] }) shouldBe false
        }

        test("PUBLIC+CANCELLED is excluded") {
            val (id, _) = createEvent(startsAt = farFuture, endsAt = farFutureEnd, status = EventStatus.CANCELLED)
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }
            (id in rows.map { it[EventTable.id] }) shouldBe false
        }

        test("endsAt in the past is excluded") {
            val (id, _) = createEvent(startsAt = farPast, endsAt = farPastEnd)
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }
            (id in rows.map { it[EventTable.id] }) shouldBe false
        }

        test("endsAt exactly equal to now is excluded (boundary is `greater`, not `greaterEq`)") {
            val now = LocalDateTime(2027, 6, 1, 12, 0)
            val (id, _) = createEvent(startsAt = now.minusHoursCompat(2), endsAt = now)
            val rows = transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }
            (id in rows.map { it[EventTable.id] }) shouldBe false
        }

        // ── EventIcsFeed.render -- RFC-5545 shape ────────────────────────────────────────────────

        test("render produces a valid VCALENDAR envelope, even for an empty event list") {
            val body = EventIcsFeed.render(rows = emptyList(), baseUrl = "https://example.org", brandTitle = "Testverein")
            body shouldContain "BEGIN:VCALENDAR\r\n"
            body shouldContain "VERSION:2.0\r\n"
            body shouldContain "END:VCALENDAR\r\n"
            body shouldNotContain "BEGIN:VEVENT"
        }

        // Every test below that renders a body scopes its assertions to ITS OWN event's row only
        // (via [rowsFor]) -- events created by earlier tests in this spec are only cleaned up in
        // `afterSpec`, so a body built from the FULL `loadUpcomingPublicPublished` result would
        // otherwise mix in other tests' events and make substring/first-match assertions flaky.
        fun rowsFor(id: Uuid): List<org.jetbrains.exposed.v1.core.ResultRow> {
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            return transaction { EventIcsFeed.loadUpcomingPublicPublished(now) }.filter { it[EventTable.id] == id }
        }

        /** Joins RFC-5545 folded continuation lines (`\r\n ` -> nothing) back into single logical lines, so a substring check doesn't need to know where a fold happened to land. */
        fun unfold(body: String): String = body.replace("\r\n ", "")

        test("render emits UID/DTSTAMP/DTSTART/DTEND/SUMMARY for each event, CRLF line endings throughout") {
            val (id, slug) = createEvent(startsAt = farFuture, endsAt = farFutureEnd)
            val body = EventIcsFeed.render(rows = rowsFor(id), baseUrl = "https://example.org", brandTitle = "Testverein")
            body shouldContain "BEGIN:VEVENT\r\n"
            body shouldContain "END:VEVENT\r\n"
            body shouldContain "UID:"
            body shouldContain "DTSTAMP:"
            body shouldContain "DTSTART:"
            body shouldContain "DTEND:"
            body shouldContain "SUMMARY:"
            unfold(body) shouldContain "URL:https://example.org/veranstaltung/$slug\r\n"
            // No bare LF without a preceding CR anywhere in the document.
            val bareLf = Regex("(?<!\r)\n").containsMatchIn(body)
            bareLf shouldBe false
        }

        // ── Escaping (RFC 5545 §3.3.11) ───────────────────────────────────────────────────────────

        test("comma, semicolon, backslash and embedded newlines are escaped, backslash first") {
            val (id, _) =
                createEvent(
                    description = "a,b;c\\d\ne\r\nf",
                    startsAt = farFuture,
                    endsAt = farFutureEnd,
                )
            val body = EventIcsFeed.render(rows = rowsFor(id), baseUrl = "https://example.org", brandTitle = "Testverein")
            // Backslash-first ordering: raw "c\d" -> "c\\d" (not "c\\\d" and not collapsed).
            unfold(body) shouldContain "a\\,b\\;c\\\\d\\ne\\nf"
        }

        // ── Line folding (RFC 5545 §3.1) ─────────────────────────────────────────────────────────

        test(
            "a description longer than 75 UTF-8 bytes is folded with CRLF + a single leading space, and un-folds back to the original text",
        ) {
            val longDescription = "Ä".repeat(60) // 60 codepoints x 2 bytes (UTF-8) = 120 bytes, well past 75
            val (id, _) = createEvent(description = longDescription, startsAt = farFuture, endsAt = farFutureEnd)
            val body = EventIcsFeed.render(rows = rowsFor(id), baseUrl = "https://example.org", brandTitle = "Testverein")
            // Find the DESCRIPTION content-line (folded across multiple physical lines) and un-fold it.
            val lines = body.split("\r\n")
            val descStart = lines.indexOfFirst { it.startsWith("DESCRIPTION:") }
            descStart shouldNotBe -1
            val physicalLines = mutableListOf(lines[descStart])
            var i = descStart + 1
            while (i < lines.size && lines[i].startsWith(" ")) {
                physicalLines += lines[i]
                i++
            }
            // Every physical line (first + continuations, leading space included) must be <= 75 octets.
            physicalLines.forEach { line -> (line.toByteArray(Charsets.UTF_8).size <= 75) shouldBe true }
            // Folding must actually have happened (more than one physical line) -- otherwise the
            // <= 75 octet assertion above would trivially pass without exercising the fold path.
            (physicalLines.size > 1) shouldBe true
            val unfolded = physicalLines.mapIndexed { idx, line -> if (idx == 0) line else line.removePrefix(" ") }.joinToString("")
            unfolded shouldBe "DESCRIPTION:$longDescription"
        }

        // ── Timezone handling -- the core stolperfalle of this wave ─────────────────────────────

        test("DTSTART/DTEND are computed via Instant conversion, not a hardcoded Z-suffix on the raw wall-clock value") {
            val wallClock = LocalDateTime(2026, 12, 24, 18, 0, 0)
            val wallClockEnd = LocalDateTime(2026, 12, 24, 20, 0, 0)
            val (id, _) = createEvent(startsAt = wallClock, endsAt = wallClockEnd)
            val body = EventIcsFeed.render(rows = rowsFor(id), baseUrl = "https://example.org", brandTitle = "Testverein")

            // Independently computed expectation -- via Instant/TimeZone, never a hardcoded offset,
            // so this assertion is correct regardless of which zone the test JVM runs in.
            val expectedInstant = wallClock.toInstant(TimeZone.currentSystemDefault())
            val expectedUtc = expectedInstant.toString() // kotlinx.datetime.Instant.toString() is always UTC with trailing Z
            val expectedDtstart =
                "DTSTART:" +
                    expectedUtc
                        .replace("-", "")
                        .replace(":", "")
                        .substringBefore(".")
                        .let { if (it.endsWith("Z")) it else "${it}Z" }
            body shouldContain expectedDtstart

            // Regression guard: a naive "wallClock.toString() + Z" would produce THIS wrong value
            // whenever the current system zone is not UTC -- assert it's NOT what we emitted, unless
            // the test happens to run with a UTC system zone (in which case both forms coincide and
            // this guard is a no-op, not a false failure).
            if (TimeZone.currentSystemDefault() != TimeZone.UTC) {
                val naiveWrongValue = "DTSTART:20261224T180000Z"
                if (expectedDtstart != naiveWrongValue) {
                    body shouldNotContain naiveWrongValue
                }
            }
        }

        test("DTSTAMP reflects the real current instant, not a double-converted UTC-as-local value") {
            val (id, _) = createEvent(startsAt = farFuture, endsAt = farFutureEnd)
            val before = Clock.System.now()
            val body = EventIcsFeed.render(rows = rowsFor(id), baseUrl = "https://example.org", brandTitle = "Testverein")
            val after = Clock.System.now()

            val dtstampLine = body.split("\r\n").first { it.startsWith("DTSTAMP:") }
            val raw = dtstampLine.removePrefix("DTSTAMP:")
            // "yyyyMMddTHHmmssZ" -> parse back into an Instant, interpreting it as UTC (exactly what
            // any RFC-5545-compliant client does with a Z-suffixed DTSTAMP).
            val parsed =
                LocalDateTime(
                    year = raw.substring(0, 4).toInt(),
                    monthNumber = raw.substring(4, 6).toInt(),
                    dayOfMonth = raw.substring(6, 8).toInt(),
                    hour = raw.substring(9, 11).toInt(),
                    minute = raw.substring(11, 13).toInt(),
                    second = raw.substring(13, 15).toInt(),
                ).toInstant(TimeZone.UTC)

            // A double-converted value (the regression this guards) would be off by roughly the test
            // JVM's UTC offset -- easily minutes to hours, never sub-minute. A generous ±120s window
            // absorbs normal test execution time and DB round-trip while still catching that bug on
            // any non-UTC system zone.
            (parsed >= before - 120.seconds) shouldBe true
            (parsed <= after + 120.seconds) shouldBe true
        }

        // ── HTTP route ────────────────────────────────────────────────────────────────────────────

        fun noOpMailDispatcher() = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            icsFeedRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEventPublicRoutes(
                            pspConfigState = PspConfigState.NotConfigured,
                            checkoutClient = null,
                            baseUrl = "https://example.org",
                            mailDispatcher = noOpMailDispatcher(),
                            brandTitle = "Testverein",
                            pageRateLimiter = generousLimiter(),
                            attemptRateLimiter = generousLimiter(),
                            registrationRateLimiter = generousLimiter(),
                            ticketPageRateLimiter = generousLimiter(),
                            ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                            icsFeedRateLimiter = icsFeedRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        test("GET /veranstaltung.ics -- 200, text/calendar, Cache-Control, includes a future public+published event") {
            val (_, slug) = createEvent(startsAt = farFuture, endsAt = farFutureEnd)
            testApp {
                val response = client.get("/veranstaltung.ics")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldBe "text/calendar; charset=utf-8"
                response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=900"
                val body = response.bodyAsText()
                body.replace("\r\n ", "") shouldContain "URL:https://example.org/veranstaltung/$slug\r\n"
            }
        }

        test("GET /veranstaltung.ics -- exhausted rate limiter -> 429, not text/calendar") {
            val exhausted = FederationInboxRateLimiter(maxRequests = 0, window = 1.minutes)
            testApp(icsFeedRateLimiter = exhausted) {
                val response = client.get("/veranstaltung.ics")
                response.status shouldBe HttpStatusCode.TooManyRequests
                response.headers[HttpHeaders.ContentType]?.let { it shouldNotContain "text/calendar" }
            }
        }

        test("GET /veranstaltung.ics -- always a valid VCALENDAR envelope, 200, even with zero matching rows") {
            // Not asserting an EMPTY feed here (unlike the pure EventIcsFeed.render unit test above):
            // this spec's earlier tests already inserted far-future PUBLIC+PUBLISHED events that live
            // until afterSpec, and DbClock.nowLocalDateTime() (the real "now" this route uses, not a
            // fixed test now) will see them too -- only the envelope shape is a reliable HTTP-level
            // assertion here.
            testApp {
                val response = client.get("/veranstaltung.ics")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "BEGIN:VCALENDAR"
                body shouldContain "END:VCALENDAR"
            }
        }
    })

/** Test-local helper -- `kotlinx.datetime.LocalDateTime` has no built-in arithmetic without a TimeZone/period API; a plain hour-of-day subtraction is all this file needs (never crosses a day boundary in its call sites). */
private fun LocalDateTime.minusHoursCompat(hours: Int): LocalDateTime =
    LocalDateTime(year, monthNumber, dayOfMonth, hour - hours, minute, second)
