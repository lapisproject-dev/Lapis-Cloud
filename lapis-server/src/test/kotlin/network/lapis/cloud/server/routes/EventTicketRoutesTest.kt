package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- `GET /veranstaltung/{slug}/ticket[.svg|.pdf]`.
 * Security-relevant assertions: no personally-identifying data on the page, a byte-identical
 * neutral 404 for every dead end (unknown code, correct-code-wrong-slug), the `img-src 'self'` CSP
 * delta, and the failures-only rate limiter not penalizing a genuine ticket holder.
 */
class EventTicketRoutesTest :
    FunSpec({
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
            }
        }

        val adminId = Uuid.parse("00000000-0000-0000-0000-000000000001")

        fun createEvent(
            title: String = "Ticket-Route-Test-Event",
            guestFriendlyLocation: String = "Testort",
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            val slug = "ticket-route-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[description] = "test"
                    it[locationText] = guestFriendlyLocation
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = adminId
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id to slug
        }

        /** Inserts a CONFIRMED, ticketed registration -- returns the RAW code (never persisted, only its hash is). */
        fun createTicketedRegistration(
            eventId: Uuid,
            guestName: String = "Ticket-Route-Gast",
            guestEmail: String = "ticket-route-guest-${Uuid.random()}@example.org",
        ): String {
            val rawCode = EventTicketPolicy.newRawCode()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventStore.insertRegistration(
                    id = Uuid.random(),
                    eventId = eventId,
                    memberId = null,
                    guestName = guestName,
                    guestEmail = guestEmail,
                    activeParticipantKey = "g:$guestEmail",
                    status = EventRegistrationStatus.CONFIRMED,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = "cancel-${Uuid.random()}",
                    registeredAt = now,
                    confirmedAt = now,
                    ticketCodeSha256 = sha256Hex(rawCode.toByteArray(Charsets.US_ASCII)),
                    ticketIssuedAt = now,
                )
            }
            return rawCode
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            ticketCodeFailureLimiter: LoginRateLimiter = LoginRateLimiter(maxFailures = 10_000),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEventPublicRoutes(
                            pspConfigState = PspConfigState.NotConfigured,
                            checkoutClient = null,
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
                            ticketCodeFailureLimiter = ticketCodeFailureLimiter,
                        )
                    }
                }
                block()
            }
        }

        test("valid code -> 200, shows the grouped code and a same-origin QR image, no name/email") {
            val (eventId, slug) = createEvent()
            val rawCode = createTicketedRegistration(eventId = eventId, guestName = "Geheimer Gastname")
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket?code=$rawCode")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain rawCode.chunked(4).joinToString("-")
                body shouldContain "ticket.svg?code=$rawCode"
                body shouldNotContain "Geheimer Gastname"
                body shouldNotContain "@example.org"
            }
        }

        test("an unknown code and a correct-code-wrong-slug both render a BYTE-IDENTICAL neutral 404") {
            val (eventA, slugA) = createEvent()
            val (_, slugB) = createEvent()
            val rawCode = createTicketedRegistration(eventId = eventA)
            testApp {
                val unknown = client.get("/veranstaltung/$slugA/ticket?code=${EventTicketPolicy.newRawCode()}")
                val wrongSlug = client.get("/veranstaltung/$slugB/ticket?code=$rawCode")
                unknown.status shouldBe HttpStatusCode.NotFound
                wrongSlug.status shouldBe HttpStatusCode.NotFound
                unknown.bodyAsText() shouldBe wrongSlug.bodyAsText()
            }
        }

        test("a malformed code never reaches the database and still 404s") {
            val (_, slug) = createEvent()
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket?code=too-short")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("response headers: no-store, img-src 'self' CSP") {
            val (eventId, slug) = createEvent()
            val rawCode = createTicketedRegistration(eventId = eventId)
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket?code=$rawCode")
                response.headers["Cache-Control"] shouldBe "no-store"
                (response.headers["Content-Security-Policy"] ?: "") shouldContain "img-src 'self'"
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
            }
        }

        test("ticket.svg is image/svg+xml with no <script> and no external reference") {
            val (eventId, slug) = createEvent()
            val rawCode = createTicketedRegistration(eventId = eventId)
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket.svg?code=$rawCode")
                response.status shouldBe HttpStatusCode.OK
                (response.headers["Content-Type"] ?: "").shouldContain("image/svg+xml")
                val body = response.bodyAsText()
                body shouldNotContain "<script"
                body shouldNotContain "<image"
                // The `xmlns="http://www.w3.org/2000/svg"` namespace declaration is expected and
                // harmless (it is not a network reference) -- what must be absent is any actual
                // external resource reference (an <image>/xlink:href, checked above/below).
                body shouldNotContain "xlink:href"
                body shouldNotContain "href=\"http"
            }
        }

        test("ticket.svg for an unknown code is a bare 404, not an HTML error page") {
            val (_, slug) = createEvent()
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket.svg?code=${EventTicketPolicy.newRawCode()}")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("ticket.pdf is application/pdf, attachment, and starts with the PDF magic bytes") {
            val (eventId, slug) = createEvent()
            val rawCode = createTicketedRegistration(eventId = eventId)
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket.pdf?code=$rawCode")
                response.status shouldBe HttpStatusCode.OK
                (response.headers["Content-Type"] ?: "").shouldContain("application/pdf")
                (response.headers["Content-Disposition"] ?: "").shouldContain("attachment")
                val bytes = response.bodyAsBytes()
                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) shouldBe "%PDF"
            }
        }

        test("a non-CONFIRMED (e.g. cancelled) registration's ticket page shows an honest, non-oracle status") {
            val (eventId, slug) = createEvent()
            val rawCode = EventTicketPolicy.newRawCode()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventStore.insertRegistration(
                    id = Uuid.random(),
                    eventId = eventId,
                    memberId = null,
                    guestName = "Storno-Gast",
                    guestEmail = "storno-${Uuid.random()}@example.org",
                    activeParticipantKey = null,
                    status = EventRegistrationStatus.CANCELLED,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = "cancel-${Uuid.random()}",
                    registeredAt = now,
                    confirmedAt = null,
                    ticketCodeSha256 = sha256Hex(rawCode.toByteArray(Charsets.US_ASCII)),
                    ticketIssuedAt = now,
                )
            }
            testApp {
                val response = client.get("/veranstaltung/$slug/ticket?code=$rawCode")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "storniert"
            }
        }

        test("failed code lookups are rate-limited, but a repeated SUCCESSFUL lookup never is") {
            val (eventId, slug) = createEvent()
            val rawCode = createTicketedRegistration(eventId = eventId)
            testApp(ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 2)) {
                // Two failures exhaust the budget...
                client.get("/veranstaltung/$slug/ticket?code=${EventTicketPolicy.newRawCode()}").status shouldBe HttpStatusCode.NotFound
                client.get("/veranstaltung/$slug/ticket?code=${EventTicketPolicy.newRawCode()}").status shouldBe HttpStatusCode.NotFound
                // ...the THIRD lookup is throttled regardless of whether the code would have been valid.
                client.get("/veranstaltung/$slug/ticket?code=$rawCode").status shouldBe HttpStatusCode.TooManyRequests
            }
            // A FRESH limiter: repeated successful lookups of the SAME valid code never trip it,
            // because only failures consume budget.
            testApp(ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 2)) {
                repeat(5) {
                    client.get("/veranstaltung/$slug/ticket?code=$rawCode").status shouldBe HttpStatusCode.OK
                }
            }
        }
    })
