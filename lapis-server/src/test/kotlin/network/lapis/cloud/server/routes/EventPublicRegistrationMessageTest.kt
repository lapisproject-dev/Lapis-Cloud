package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.204 -- the optional message of a guest on the public registration form (`nachricht`): stored, 500 multi-byte characters pass the body
 * cap, 501 are refused before the strict registration budget is spent and without echoing the text, the honeypot `kommentar` still wins, the
 * body ceiling holds, the form carries label / `maxlength` / the hint sentence, and the occupancy line stays binary (no number).
 */
class EventPublicRegistrationMessageTest :
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
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        val organizer: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventPublicRegistrationMessageTest Organisator"
                    it[email] = "event-public-message-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            id
        }

        fun createEvent(capacity: Int? = null): String {
            val id = Uuid.random()
            val slug = "event-public-message-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[title] = "Nachrichten-Test"
                    it[description] = "x"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[EventTable.capacity] = capacity
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

        fun limiter(max: Int = 10_000) = FederationInboxRateLimiter(maxRequests = max, window = 1.minutes)

        suspend fun withApp(
            registrationLimiter: FederationInboxRateLimiter = limiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
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
                        branding = eventTestBranding(),
                        navAvailability = eventTestNav(),
                        embedConfig = eventTestEmbedConfig(),
                        pageRateLimiter = limiter(),
                        attemptRateLimiter = limiter(),
                        registrationRateLimiter = registrationLimiter,
                        ticketPageRateLimiter = limiter(),
                        ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                        icsFeedRateLimiter = limiter(),
                    )
                }
            }
            block()
        }

        suspend fun ApplicationTestBuilder.postForm(
            url: String,
            vararg pairs: Pair<String, String>,
        ): HttpResponse =
            client.post(url) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(Parameters.build { pairs.forEach { (k, v) -> append(k, v) } }.formUrlEncode())
            }

        fun storedMessages(slug: String): List<String?> =
            transaction {
                val eventId = EventTable.selectAll().where { EventTable.slug eq slug }.single()[EventTable.id]
                EventRegistrationTable
                    .selectAll()
                    .where { EventRegistrationTable.eventId eq eventId }
                    .map { it[EventRegistrationTable.message] }
            }

        fun email() = "msg-${Uuid.random()}@example.org"

        test("the field is stored; blank and absent become null") {
            val slug = createEvent()
            withApp {
                postForm(
                    "/veranstaltung/$slug/anmeldung",
                    "guestName" to "Gerda",
                    "guestEmail" to email(),
                    "nachricht" to "  Ich brauche\r\neinen Parkplatz  ",
                ).status shouldBe HttpStatusCode.SeeOther
                postForm("/veranstaltung/$slug/anmeldung", "guestName" to "Hans", "guestEmail" to email(), "nachricht" to "   ")
                postForm("/veranstaltung/$slug/anmeldung", "guestName" to "Ida", "guestEmail" to email())
                storedMessages(slug).toSet() shouldBe setOf("Ich brauche\neinen Parkplatz", null)
                storedMessages(slug).size shouldBe 3
            }
        }

        test("500 multi-byte characters pass the body cap (a 5 KB body)") {
            val slug = createEvent()
            val text = "ä€".repeat(250)
            text.length shouldBe 500
            withApp {
                val response =
                    postForm("/veranstaltung/$slug/anmeldung", "guestName" to "Zoë", "guestEmail" to email(), "nachricht" to text)
                response.status shouldBe HttpStatusCode.SeeOther
                storedMessages(slug) shouldBe listOf(text)
            }
        }

        test("501 characters answer 400 without echo, store nothing and spend no registration budget") {
            val slug = createEvent()
            val tight = limiter(max = 1)
            withApp(registrationLimiter = tight) {
                val bad =
                    postForm(
                        "/veranstaltung/$slug/anmeldung",
                        "guestName" to "Gerda",
                        "guestEmail" to email(),
                        "nachricht" to "Z".repeat(501),
                    )
                bad.status shouldBe HttpStatusCode.BadRequest
                bad.bodyAsText() shouldNotContain "ZZZZZZZZ"
                storedMessages(slug) shouldBe emptyList()
                // the one strict attempt is still available
                postForm("/veranstaltung/$slug/anmeldung", "guestName" to "Gerda", "guestEmail" to email(), "nachricht" to "ok")
                    .status shouldBe HttpStatusCode.SeeOther
            }
        }

        test("a filled honeypot stays a silent no-op even with a message") {
            val slug = createEvent()
            withApp {
                postForm(
                    "/veranstaltung/$slug/anmeldung",
                    "guestName" to "Bot",
                    "guestEmail" to email(),
                    "kommentar" to "spam",
                    "nachricht" to "Hallo",
                ).status shouldBe HttpStatusCode.SeeOther
                storedMessages(slug) shouldBe emptyList()
            }
        }

        test("a body above 16 KB answers 413") {
            val slug = createEvent()
            withApp {
                postForm(
                    "/veranstaltung/$slug/anmeldung",
                    "guestName" to "Gerda",
                    "guestEmail" to email(),
                    "nachricht" to "x".repeat(17_000),
                ).status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test("the form has a labelled message field with maxlength, the hint sentence and aria-describedby") {
            val slug = createEvent()
            withApp {
                val html = client.get("/veranstaltung/$slug").bodyAsText()
                html shouldContain "<label for=\"guestMessage\">Nachricht an die Veranstalter (optional)</label>"
                html shouldContain "name=\"nachricht\""
                html shouldContain "maxlength=\"500\""
                html shouldContain "aria-describedby=\"guestMessageHint\""
                html shouldContain "id=\"guestMessageHint\""
                html shouldContain
                    "Ihre Nachricht sehen die Veranstalter. Gültig ist Ihre Anmeldung durch die Bestätigung, " +
                    "nicht durch eine Antwort auf Ihre Frage."
                // the honeypot keeps its own name
                html shouldContain "name=\"kommentar\""
            }
        }

        test("the occupancy line stays binary: no number, however many seats are taken") {
            val slug = createEvent(capacity = 10)
            withApp {
                postForm("/veranstaltung/$slug/anmeldung", "guestName" to "Eins", "guestEmail" to email())
                val html = client.get("/veranstaltung/$slug").bodyAsText()
                html shouldContain "Plätze frei"
                val row = html.substringAfter("<dt>Plätze</dt><dd>").substringBefore("</dd>")
                row.any { it.isDigit() } shouldBe false
            }
        }
    })
