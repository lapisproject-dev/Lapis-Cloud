package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.EventRegistrationStatus
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.243 -- "one series, one entry" on `/veranstaltungen` and "Weitere Termine" on `/veranstaltung/{slug}`, on H2 and on a real
 * PostgreSQL (grouping with `rrule` in the `GROUP BY`, the anti-join and its tiebreak are engine-sensitive). Events are created
 * directly; ids of one series differ only in their LAST byte so the tiebreak is exercised.
 */
abstract class PublicSeriesScenarios(
    private val db: TestDatabase,
) : FunSpec({
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
                    it[displayName] = "PublicSeriesScenarios Mitglied"
                    it[email] = "public-series-scenarios-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            id
        }

        fun base(day: Int): LocalDateTime = LocalDateTime(2031, 3, day, 19, 0)

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

        /** Ids share all but the last byte, so only the tiebreak (not the high bytes) can distinguish them. */
        fun createEvent(
            title: String = "Serien-Test",
            startsAt: LocalDateTime = base(1),
            durationHours: Long = 2,
            seriesId: Uuid? = null,
            originalStart: LocalDateTime? = if (seriesId != null) startsAt else null,
            detached: Boolean = false,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
            capacity: Int? = null,
        ): Pair<Uuid, String> {
            val id = Uuid.fromLongs(0x0123456789abcdefL, 0x1000_0000_0000_0000L + (++idCounter))
            val slug = "series-test-$id"
            val ends = LocalDateTime(startsAt.year, startsAt.monthNumber, startsAt.dayOfMonth, startsAt.hour + durationHours.toInt(), 0)
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[endsAt] = ends
                    it[EventTable.capacity] = capacity
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

        fun confirmRegistrations(
            eventId: Uuid,
            count: Int,
        ) {
            transaction {
                repeat(count) { index ->
                    EventRegistrationTable.insert {
                        it[id] = Uuid.random()
                        it[EventRegistrationTable.eventId] = eventId
                        it[guestName] = "Gast $index"
                        it[guestEmail] = "gast-$index-$eventId@example.org"
                        it[activeParticipantKey] = "gast-$index-$eventId@example.org"
                        it[status] = EventRegistrationStatus.CONFIRMED
                        it[feeAmount] = BigDecimal.ZERO
                        it[registeredAt] = DbClock.nowLocalDateTime()
                        it[confirmedAt] = DbClock.nowLocalDateTime()
                    }
                }
            }
        }

        fun wallNow(): LocalDateTime = DbClock.nowLocalDateTime()

        fun overviewSlugs(): List<String> =
            transaction { PublicSeriesQueries.loadOverviewRepresentatives(wallNow = wallNow()).map { it[EventTable.slug] } }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun fetch(url: String): Pair<HttpStatusCode, String> {
            var result: Pair<HttpStatusCode, String>? = null
            testApplication {
                application {
                    routing {
                        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
                        registerPublicEventsOverviewRoutes(
                            readRateLimiter = generousLimiter(),
                            branding = branding,
                            navAvailability = eventTestNav(),
                        )
                        registerEventPublicRoutes(
                            checkoutGateways = emptyMap(),
                            baseUrl = "https://example.org",
                            mailDispatcher =
                                MailDispatcher(
                                    transport = NoOpMailTransport(),
                                    scope =
                                        CoroutineScope(
                                            SupervisorJob() + Dispatchers.IO,
                                        ),
                                ),
                            branding = eventTestBranding(),
                            navAvailability = eventTestNav(),
                            embedConfig = eventTestEmbedConfig(),
                            pageRateLimiter = generousLimiter(),
                            attemptRateLimiter = generousLimiter(),
                            registrationRateLimiter = generousLimiter(),
                            ticketPageRateLimiter = generousLimiter(),
                            ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                            icsFeedRateLimiter = generousLimiter(),
                        )
                    }
                }
                val response = client.get(url)
                result = response.status to response.bodyAsText()
            }
            return result!!
        }

        class StatementCounter : SqlLogger {
            var count = 0

            override fun log(
                context: StatementContext,
                transaction: Transaction,
            ) {
                count++
            }
        }

        // ── Overview: queries ────────────────────────────────────────────────────
        test("a series with several upcoming dates is ONE entry: its earliest") {
            val series = createSeries()
            val (_, first) = createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            createEvent(startsAt = base(16), seriesId = series)
            overviewSlugs() shouldContainExactly listOf(first)
        }

        test("a cancelled next date: the following one represents the series") {
            val series = createSeries()
            createEvent(startsAt = base(2), seriesId = series, status = EventStatus.CANCELLED)
            val (_, second) = createEvent(startsAt = base(9), seriesId = series)
            overviewSlugs() shouldContainExactly listOf(second)
        }

        test("members-only, draft and past siblings neither represent nor count") {
            val series = createSeries()
            createEvent(startsAt = base(2), seriesId = series, visibility = EventVisibility.MEMBERS_ONLY)
            createEvent(startsAt = base(3), seriesId = series, status = EventStatus.DRAFT)
            createEvent(startsAt = LocalDateTime(2020, 1, 7, 19, 0), seriesId = series)
            val (_, only) = createEvent(startsAt = base(9), seriesId = series)
            overviewSlugs() shouldContainExactly listOf(only)
            val stats = transaction { PublicSeriesQueries.loadSeriesStats(seriesIds = listOf(series), wallNow = wallNow()) }
            stats.getValue(series).upcomingCount shouldBe 1
        }

        test("a moved date keeps its own time and is the representative when it is the earliest") {
            val series = createSeries()
            val (_, moved) = createEvent(startsAt = base(1), originalStart = base(8), seriesId = series, detached = true)
            createEvent(startsAt = base(9), seriesId = series)
            overviewSlugs() shouldContainExactly listOf(moved)
        }

        test("two dates of one series at the same minute: exactly one representative, the smaller id") {
            val series = createSeries()
            val (idA, slugA) = createEvent(startsAt = base(2), seriesId = series, originalStart = base(2))
            val (idB, _) = createEvent(startsAt = base(2), seriesId = series, originalStart = base(9), detached = true)
            val expected = if (idA.toString() < idB.toString()) slugA else "series-test-$idB"
            overviewSlugs() shouldContainExactly listOf(expected)
        }

        test("interleaved series and single events are ordered by start time") {
            val a = createSeries()
            val b = createSeries()
            val (_, a1) = createEvent(startsAt = base(2), seriesId = a)
            val (_, b1) = createEvent(startsAt = base(3), seriesId = b)
            val (_, single) = createEvent(startsAt = base(4))
            createEvent(startsAt = base(5), seriesId = a)
            createEvent(startsAt = base(6), seriesId = b)
            overviewSlugs() shouldContainExactly listOf(a1, b1, single)
        }

        test("the limit counts ENTRIES: 60 dates of one series plus 49 single events fill exactly 50 entries") {
            val series = createSeries()
            for (i in 0 until 60) {
                createEvent(startsAt = LocalDateTime(2032, 1 + i / 28, 1 + i % 28, 19, 0), seriesId = series)
            }
            for (i in 0 until 49) createEvent(startsAt = LocalDateTime(2031, 4, 1 + i % 28, 8 + i / 28, 0))
            overviewSlugs().size shouldBe 50
        }

        test("the statement count does not depend on the number of series") {
            fun statements(seriesCount: Int): Int {
                repeat(seriesCount) {
                    val s = createSeries()
                    createEvent(startsAt = base(2 + it), seriesId = s)
                    createEvent(startsAt = base(12 + it), seriesId = s)
                }
                val counter = StatementCounter()
                transaction {
                    addLogger(counter)
                    val rows = PublicSeriesQueries.loadOverviewRepresentatives(wallNow = wallNow())
                    PublicSeriesQueries.loadSeriesStats(seriesIds = rows.mapNotNull { it[EventTable.seriesId] }, wallNow = wallNow())
                }
                return counter.count
            }
            val few = statements(1)
            transaction {
                EventTable.update({ EventTable.status eq EventStatus.PUBLISHED }) { it[status] = EventStatus.CANCELLED }
            }
            val many = statements(8)
            many shouldBe few
        }

        // ── Overview: HTML ───────────────────────────────────────────────────────
        test("overview HTML: repeat mark, tooltip text, link with anchor, no title attribute, escaped title") {
            val series = createSeries("FREQ=MONTHLY;INTERVAL=1;BYDAY=1MO;COUNT=12")
            val (_, slug) = createEvent(title = "Stammtisch <b>&\"", startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            val (status, html) = fetch("/veranstaltungen")
            status shouldBe HttpStatusCode.OK
            html shouldContain "class=\"series-mark\""
            html shouldContain "aria-hidden=\"true\""
            html shouldContain "data-tip=\"Jeden ersten Montag im Monat\""
            html shouldContain "<svg"
            html shouldContain "href=\"http://localhost:8080/veranstaltung/$slug#weitere-termine\""
            html shouldContain "Alle Termine (2)"
            html shouldNotContain "title=\"Jeden"
            html shouldNotContain "Stammtisch <b>"
            html shouldContain "Stammtisch &lt;b&gt;"
        }

        test("overview HTML: the last date of a series says so and carries no link") {
            val series = createSeries()
            createEvent(startsAt = base(2), seriesId = series)
            val (_, html) = fetch("/veranstaltungen")
            html shouldContain "Letzter Termin dieser Serie"
            html shouldNotContain "#weitere-termine"
        }

        test("overview HTML: a single event renders without any series markup") {
            createEvent(startsAt = base(2))
            val (_, html) = fetch("/veranstaltungen")
            html shouldNotContain "series-mark\""
            html shouldNotContain "series-note\""
        }

        test("overview HTML: a full next date shows no occupancy") {
            val series = createSeries()
            val (id, _) = createEvent(startsAt = base(2), seriesId = series, capacity = 1)
            createEvent(startsAt = base(9), seriesId = series)
            confirmRegistrations(id, 1)
            val (_, html) = fetch("/veranstaltungen")
            html shouldNotContain "Warteliste"
            html shouldNotContain "Plätze"
        }

        // ── Detail ───────────────────────────────────────────────────────────────
        test("detail: sentence, remaining count, last date and the sibling list; the page itself is not listed") {
            val series = createSeries()
            val (_, own) = createEvent(startsAt = base(2), seriesId = series)
            val (_, s2) = createEvent(startsAt = base(9), seriesId = series)
            createEvent(startsAt = base(16), seriesId = series)
            val (status, html) = fetch("/veranstaltung/$own")
            status shouldBe HttpStatusCode.OK
            html shouldContain "id=\"weitere-termine\""
            html shouldContain "Weitere Termine dieser Serie"
            html shouldContain "Jeden Dienstag. Noch 2 Termine, bis So, 16.03.2031"
            html shouldContain "/veranstaltung/$s2"
            html shouldNotContain "href=\"/veranstaltung/$own\""
        }

        test("detail: a single remaining date reads 'Noch 1 Termin'") {
            val series = createSeries()
            val (_, own) = createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            fetch("/veranstaltung/$own").second shouldContain "Noch 1 Termin, bis"
        }

        test("detail: the block also shows on the page of a past date, listing the upcoming ones") {
            val series = createSeries()
            val (_, past) = createEvent(startsAt = LocalDateTime(2020, 1, 7, 19, 0), seriesId = series)
            val (_, next) = createEvent(startsAt = base(9), seriesId = series)
            val html = fetch("/veranstaltung/$past").second
            html shouldContain "Diese Veranstaltung hat stattgefunden."
            html shouldContain "id=\"weitere-termine\""
            html shouldContain "/veranstaltung/$next"
        }

        test("detail: no block for a single event and none for the only upcoming date of a series") {
            val (_, single) = createEvent(startsAt = base(2))
            fetch("/veranstaltung/$single").second shouldNotContain "weitere-termine"
            val series = createSeries()
            val (_, lone) = createEvent(startsAt = base(2), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series, status = EventStatus.CANCELLED)
            fetch("/veranstaltung/$lone").second shouldNotContain "weitere-termine"
        }

        test("detail: five dates visible, the rest inside a closed details element; cancelled dates are absent") {
            val series = createSeries()
            val (_, own) = createEvent(startsAt = base(1), seriesId = series)
            val others = (2..8).map { createEvent(startsAt = base(it), seriesId = series).second }
            val (_, cancelled) = createEvent(startsAt = base(9), seriesId = series, status = EventStatus.CANCELLED)
            val html = fetch("/veranstaltung/$own").second
            html shouldContain "<details><summary>Alle anzeigen (2 weitere)</summary>"
            html shouldNotContain "<details open"
            others.forEach { html shouldContain "/veranstaltung/$it" }
            html shouldNotContain cancelled
        }

        test("detail: the 'geändert' tag for a detached date and for a moved date, the 'Warteliste' tag for a full date") {
            val series = createSeries()
            val (_, own) = createEvent(startsAt = base(1), seriesId = series)
            val (_, detached) = createEvent(startsAt = base(2), seriesId = series, detached = true)
            val (_, moved) = createEvent(startsAt = base(3), originalStart = base(10), seriesId = series)
            val (_, plain) = createEvent(startsAt = base(4), seriesId = series)
            val (fullId, full) = createEvent(startsAt = base(5), seriesId = series, capacity = 1)
            confirmRegistrations(fullId, 1)
            val html = fetch("/veranstaltung/$own").second

            fun rowOf(slug: String): String = html.substringAfter("/veranstaltung/$slug").substringBefore("</li>")
            rowOf(detached) shouldContain "geändert"
            rowOf(moved) shouldContain "geändert"
            rowOf(plain) shouldNotContain "geändert"
            rowOf(full) shouldContain "Warteliste"
        }

        test("detail: more than 50 further dates are cut with a closing line") {
            val series = createSeries()
            val (_, own) = createEvent(startsAt = base(1), seriesId = series)
            for (i in 0 until 54) {
                createEvent(startsAt = LocalDateTime(2032, 1 + i / 28, 1 + i % 28, 19, 0), seriesId = series)
            }
            val html = fetch("/veranstaltung/$own").second
            html shouldContain "Noch 54 Termine"
            html shouldContain "… und 4 weitere Termine"
        }

        test("detail: markup in the title never reaches the series block; the embed variant has no block") {
            val series = createSeries()
            val (_, own) = createEvent(title = "<script>x</script>", startsAt = base(1), seriesId = series)
            createEvent(startsAt = base(9), seriesId = series)
            fetch("/veranstaltung/$own").second shouldNotContain "<script>x"
            fetch("/veranstaltung/$own?embed=1").second shouldNotContain "weitere-termine"
        }

        test("detail: the number of statements for the series block is constant") {
            fun statements(dates: Int): Int {
                val series = createSeries()
                val (ownId, _) = createEvent(startsAt = base(1), seriesId = series)
                repeat(dates) { createEvent(startsAt = LocalDateTime(2032, 1 + it / 28, 1 + it % 28, 19, 0), seriesId = series) }
                val counter = StatementCounter()
                transaction {
                    addLogger(counter)
                    val siblings = PublicSeriesQueries.loadSiblings(seriesId = series, excludeEventId = ownId, wallNow = wallNow())
                    PublicSeriesQueries.loadSeriesStats(seriesIds = listOf(series), wallNow = wallNow())
                    PublicSeriesQueries.loadFullEventIds(eventIds = siblings.map { it[EventTable.id] }, now = wallNow())
                }
                return counter.count
            }
            statements(2) shouldBe statements(20)
        }
    })
