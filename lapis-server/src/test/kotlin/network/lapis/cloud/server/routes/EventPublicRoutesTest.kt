package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
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
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- `POST /veranstaltung/{slug}/zahlung`, the resume-payment
 * route a waitlist-promotion's mail links to. Closes review finding "kein Testfile referenziert
 * diese Route": the abuse-protection layers this handler is built on (token comparison via
 * `MessageDigest.isEqual`, the content-length cap, the content-type check, the two rate limiters)
 * had zero HTTP-level coverage before this file -- `EventPaymentResumeTest` only ever calls
 * `EventRegistrationSubmission.resumeCheckout` directly, bypassing the route entirely.
 */
class EventPublicRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                }
            }
        }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    val registrationIds =
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.eventId inList createdEventIds }
                            .map { it[EventRegistrationTable.id] }
                    if (registrationIds.isNotEmpty()) {
                        PaymentCheckoutSessionTable.deleteWhere { eventRegistrationId inList registrationIds }
                    }
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventPublicRoutesTest Mitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun enableGateway() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = createMember("event-public-routes-ack-${Uuid.random()}@example.org")
                    it[acknowledgedAt] = LocalDateTime(2030, 1, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        fun testPspConfigState(): PspConfigState.Configured =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_event_public_routes"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_event_public_routes"
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        fun fakeSuccessfulCheckoutClient(pspConfigState: PspConfigState.Configured): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"id":"cs_test_public_routes_fake","url":"https://checkout.stripe.com/c/pay/cs_test_public_routes_fake"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        fun noOpMailDispatcher() = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createPaidEvent(): Pair<Uuid, String> {
            val organizer = createMember("event-public-routes-organizer-${Uuid.random()}@example.org")
            val id = Uuid.random()
            val slug = "public-routes-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[title] = "Public-Routes-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[capacity] = 10
                    it[feeAmount] = BigDecimal("25.00")
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
            return id to slug
        }

        /** Mirrors `EventRegistrationSubmission.randomCancelToken` (private there) -- any sufficiently random hex string works for this test's purposes. */
        fun randomToken(): String {
            val buffer = ByteArray(32)
            SecureRandom().nextBytes(buffer)
            return buffer.joinToString(separator = "") { "%02x".format(it) }
        }

        fun insertPendingPaymentRegistration(
            eventId: Uuid,
            memberId: Uuid,
            token: String,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventRegistrationTable.insert {
                    it[EventRegistrationTable.id] = id
                    it[EventRegistrationTable.eventId] = eventId
                    it[EventRegistrationTable.memberId] = memberId
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = "m:$memberId"
                    it[status] = EventRegistrationStatus.PENDING_PAYMENT
                    it[feeAmount] = BigDecimal("25.00")
                    it[holdExpiresAt] = LocalDateTime(2035, 1, 1, 0, 0)
                    it[waitlistPosition] = null
                    it[cancelTokenSha256] = sha256Hex(token.toByteArray(Charsets.US_ASCII))
                    it[registeredAt] = now
                    it[confirmedAt] = null
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
            return id
        }

        suspend fun testApp(
            checkoutClient: StripeCheckoutClient?,
            pspConfigState: PspConfigState,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEventPublicRoutes(
                            pspConfigState = pspConfigState,
                            checkoutClient = checkoutClient,
                            baseUrl = "https://example.org",
                            mailDispatcher = noOpMailDispatcher(),
                            brandTitle = "Testverein",
                            pageRateLimiter = generousLimiter(),
                            attemptRateLimiter = generousLimiter(),
                            registrationRateLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        fun formBody(vararg pairs: Pair<String, String>): String =
            Parameters.build { pairs.forEach { (k, v) -> append(k, v) } }.formUrlEncode()

        test("valid payment-resume token -> 303 redirect to the Stripe checkout URL") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val (eventId, slug) = createPaidEvent()
            val payer = createMember("event-public-routes-payer-${Uuid.random()}@example.org")
            val token = randomToken()
            insertPendingPaymentRegistration(eventId = eventId, memberId = payer, token = token)

            testApp(checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val response =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("token" to token))
                    }
                response.status shouldBe HttpStatusCode(303, "See Other")
                response.headers[HttpHeaders.Location] shouldBe "https://checkout.stripe.com/c/pay/cs_test_public_routes_fake"
            }
        }

        test("empty token -> 303 redirect to /abgebrochen, never reaches Stripe (no oracle)") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val (eventId, slug) = createPaidEvent()
            val payer = createMember("event-public-routes-empty-token-${Uuid.random()}@example.org")
            insertPendingPaymentRegistration(eventId = eventId, memberId = payer, token = randomToken())

            testApp(checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val response =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("token" to ""))
                    }
                response.status shouldBe HttpStatusCode(303, "See Other")
                response.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/abgebrochen"
            }
        }

        test("wrong (well-formed but non-matching) token -> the SAME 303 redirect to /abgebrochen as an empty one -- no oracle") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val (eventId, slug) = createPaidEvent()
            val payer = createMember("event-public-routes-wrong-token-${Uuid.random()}@example.org")
            val registrationId = insertPendingPaymentRegistration(eventId = eventId, memberId = payer, token = randomToken())

            testApp(checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val response =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("token" to randomToken()))
                    }
                response.status shouldBe HttpStatusCode(303, "See Other")
                response.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/abgebrochen"
            }

            // No checkout session must ever have been created for the real registration.
            val sessionCount =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                        .count()
                }
            sessionCount shouldBe 0L
        }

        test("wrong Content-Type -> 400, body never parsed as a form") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val (eventId, slug) = createPaidEvent()
            val payer = createMember("event-public-routes-bad-ct-${Uuid.random()}@example.org")
            val token = randomToken()
            insertPendingPaymentRegistration(eventId = eventId, memberId = payer, token = token)

            testApp(checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val response =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"$token"}""")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("oversized Content-Length -> 413, body never read") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            val (eventId, slug) = createPaidEvent()
            val payer = createMember("event-public-routes-oversized-${Uuid.random()}@example.org")
            insertPendingPaymentRegistration(eventId = eventId, memberId = payer, token = randomToken())

            testApp(checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val response =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("token" to "x".repeat(999_900)))
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }
    })
