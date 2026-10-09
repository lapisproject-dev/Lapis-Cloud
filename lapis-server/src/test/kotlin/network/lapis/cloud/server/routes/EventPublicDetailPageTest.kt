package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.security.LoginRateLimiter
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

/** Welle V1.9.82 -- the public event page: paragraphs, escaping, "has taken place", alt text and the opt-in online link. */
class EventPublicDetailPageTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

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
                    it[displayName] = "EventPublicDetailPageTest Mitglied"
                    it[email] = "event-detail-page-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            id
        }

        fun createEvent(
            description: String = "test",
            startsAt: LocalDateTime = LocalDateTime(2031, 1, 1, 18, 0),
            endsAt: LocalDateTime = LocalDateTime(2031, 1, 1, 22, 0),
            onlineUrl: String? = null,
            onlineUrlPublic: Boolean = false,
            coverImageId: Uuid? = null,
            coverImageAlt: String? = null,
            title: String = "Detailseiten-Test",
        ): String {
            val id = Uuid.random()
            val slug = "event-detail-page-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[EventTable.description] = description
                    it[locationText] = "Testort"
                    it[EventTable.onlineUrl] = onlineUrl
                    it[EventTable.onlineUrlPublic] = onlineUrlPublic
                    it[EventTable.coverImageId] = coverImageId
                    it[EventTable.coverImageAlt] = coverImageAlt
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
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

        suspend fun fetch(url: String): Pair<HttpStatusCode, String> {
            var result: Pair<HttpStatusCode, String>? = null
            testApplication {
                application {
                    routing {
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
                            brandTitle = "Testverein",
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

        suspend fun page(slug: String) = fetch("/veranstaltung/$slug")

        test("paragraphs: an empty line starts a new <p>, a single line break becomes <br>") {
            val slug = createEvent(description = "Zeile eins\r\nZeile zwei\n\n\n\nAbsatz zwei")
            val (status, html) = page(slug)
            status shouldBe HttpStatusCode.OK
            html shouldContain "<p>Zeile eins<br>Zeile zwei</p>"
            html shouldContain "<p>Absatz zwei</p>"
        }

        test("XSS: markup in description and title is escaped, never interpreted") {
            val slug = createEvent(description = "<script>alert(1)</script>\n\"><img src=x onerror=alert(2)>", title = "<b>Titel</b>")
            val (_, html) = page(slug)
            html shouldNotContain "<script>alert(1)"
            html shouldNotContain "<img src=x"
            html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
            html shouldNotContain "<b>Titel</b>"
        }

        test("an event in the past says so and offers no registration form") {
            val slug = createEvent(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 22, 0))
            val (_, html) = page(slug)
            html shouldContain "Diese Veranstaltung hat stattgefunden."
            html shouldNotContain "<form"
            html shouldNotContain "Plätze frei"
        }

        test("an upcoming event keeps the registration form and the seats line") {
            val slug = createEvent()
            val (_, html) = page(slug)
            html shouldContain "<form"
            html shouldContain "Plätze frei"
            html shouldNotContain "hat stattgefunden"
        }

        test("cover image alt text: the stored alt text, falling back to the title") {
            val withAlt = createEvent(coverImageId = Uuid.random(), coverImageAlt = "Ein Plakat mit Kerzen")
            val withoutAlt = createEvent(coverImageId = Uuid.random(), title = "Titel als Alt")
            page(withAlt).second shouldContain "alt=\"Ein Plakat mit Kerzen\""
            page(withoutAlt).second shouldContain "alt=\"Titel als Alt\""
        }

        test("F1: the online link is shown only with the opt-in and only as https") {
            val optedIn = createEvent(onlineUrl = "https://meet.example/abc", onlineUrlPublic = true)
            val notOptedIn = createEvent(onlineUrl = "https://meet.example/geheim", onlineUrlPublic = false)
            val httpOptedIn = createEvent(onlineUrl = "http://meet.example/plain", onlineUrlPublic = true)
            page(optedIn).second shouldContain "Online: https://meet.example/abc"
            page(notOptedIn).second shouldNotContain "meet.example"
            page(httpOptedIn).second shouldNotContain "meet.example"
        }

        test("F1: the opted-in online link of an ended event is not published on the page") {
            val ended =
                createEvent(
                    onlineUrl = "https://meet.example/vorbei",
                    onlineUrlPublic = true,
                    startsAt = LocalDateTime(2020, 1, 1, 18, 0),
                    endsAt = LocalDateTime(2020, 1, 1, 22, 0),
                )
            page(ended).second shouldNotContain "meet.example"
        }

        test("the iCal export of a single event is unchanged and still carries no online link") {
            createEvent(onlineUrl = "https://meet.example/abc", onlineUrlPublic = true)
            val (status, ics) = fetch("/veranstaltung.ics")
            status shouldBe HttpStatusCode.OK
            ics shouldContain "BEGIN:VCALENDAR"
            ics shouldNotContain "meet.example"
        }
    })
