package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.35 -- `resumeOwnEventPayment`: ownership, server-side amount, idempotency, single-flight, uniform refusals. */
abstract class EventPaymentResumeRpcScenarios(
    db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)

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
                    val regIds =
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.eventId inList createdEventIds }
                            .map { it[EventRegistrationTable.id] }
                    if (regIds.isNotEmpty()) PaymentCheckoutSessionTable.deleteWhere { eventRegistrationId inList regIds }
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
            db.deactivate()
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ResumeRpc Mitglied"
                    it[email] = "resume-rpc-${Uuid.random()}@example.org"
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
            val acker = createMember()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = acker
                    it[acknowledgedAt] = LocalDateTime(2030, 1, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        val pspConfig =
            requireNotNull(
                (
                    PspConfig.load {
                        when (it) {
                            PspConfig.ENV_SECRET_KEY -> "sk_test_resume_rpc"
                            PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_resume_rpc"
                            else -> null
                        }
                    } as? PspConfigState.Configured
                )?.config,
            )

        /** Counts real Stripe calls; every call yields a UNIQUE session id so a double call fails on an assertion, not on a unique index. */
        class FakeStripe(
            private val delayMillis: Long = 0,
            private val failWith: HttpStatusCode? = null,
        ) {
            val calls = AtomicInteger(0)
            val client =
                StripeCheckoutClient(
                    pspConfig = pspConfig,
                    httpClient =
                        HttpClient(
                            MockEngine { _ ->
                                val n = calls.incrementAndGet()
                                if (delayMillis > 0) delay(delayMillis)
                                if (failWith != null) {
                                    respond(
                                        """{"error":{"message":"boom"}}""",
                                        failWith,
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                } else {
                                    val sid = "cs_resume_rpc_${Uuid.random()}_$n"
                                    respond(
                                        """{"id":"$sid","url":"https://checkout.stripe.com/c/pay/$sid"}""",
                                        HttpStatusCode.OK,
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                            },
                        ),
                )
        }

        fun createEvent(
            createdBy: Uuid,
            startsAt: LocalDateTime = LocalDateTime(2030, 1, 1, 18, 0),
            status: EventStatus = EventStatus.PUBLISHED,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "resume-rpc-$id"
                    it[title] = "Resume-RPC-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[endsAt] = startsAt.let { s -> LocalDateTime(s.year, s.month, s.day, s.hour + 3, 0) }
                    it[capacity] = null
                    it[feeAmount] = BigDecimal("25.00")
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun insertRegistration(
            eventId: Uuid,
            memberId: Uuid,
            status: EventRegistrationStatus,
            holdExpiresAt: LocalDateTime? =
                if (status ==
                    EventRegistrationStatus.PENDING_PAYMENT
                ) {
                    LocalDateTime(2035, 1, 1, 0, 0)
                } else {
                    null
                },
        ): Uuid {
            val id = Uuid.random()
            val inactive = status == EventRegistrationStatus.CANCELLED || status == EventRegistrationStatus.EXPIRED
            transaction {
                EventRegistrationTable.insert {
                    it[EventRegistrationTable.id] = id
                    it[EventRegistrationTable.eventId] = eventId
                    it[EventRegistrationTable.memberId] = memberId
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = if (inactive) null else "m:$memberId"
                    it[EventRegistrationTable.status] = status
                    it[feeAmount] = BigDecimal("25.00")
                    it[EventRegistrationTable.holdExpiresAt] = holdExpiresAt
                    it[waitlistPosition] = if (status == EventRegistrationStatus.WAITLISTED) 1 else null
                    it[cancelTokenSha256] = null
                    it[registeredAt] = DbClock.nowLocalDateTime()
                    it[confirmedAt] = if (status == EventRegistrationStatus.CONFIRMED) DbClock.nowLocalDateTime() else null
                    it[cancelledAt] = if (inactive) DbClock.nowLocalDateTime() else null
                    it[waitlistOfferedAt] = null
                }
            }
            return id
        }

        fun sessionRows(registrationId: Uuid) =
            transaction {
                PaymentCheckoutSessionTable.selectAll().where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }.toList()
            }

        fun Route.resumeRoutes(
            stripe: FakeStripe?,
            limiter: FederationInboxRateLimiter,
        ) {
            fun service(call: ApplicationCall) =
                EventService(
                    call = call,
                    checkoutGateways = stripe?.let { mapOf(PaymentProvider.STRIPE to it.client) } ?: emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher =
                        MailDispatcher(
                            transport = NoOpMailTransport(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        ),
                    writeRateLimiter = limiter,
                    checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                    seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                )
            post("/test/resume/{eventId}") {
                val dto = service(call).resumeOwnEventPayment(call.parameters["eventId"]!!)
                call.respondText(dto.checkoutRedirectUrl ?: "none")
            }
        }

        fun io.ktor.server.application.Application.setup(
            stripe: FakeStripe?,
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
        ) {
            install(StatusPages) {
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing { resumeRoutes(stripe, limiter) }
        }

        val refused = "Die Zahlung kann nicht fortgesetzt werden."

        test(
            "own PENDING_PAYMENT registration gets a redirect; the session amount is the registration's server-side fee; a second call reuses it",
        ) {
            enableGateway()
            val stripe = FakeStripe()
            testApplication {
                application { setup(stripe) }
                val me = createMember()
                val event = createEvent(createMember())
                val reg = insertRegistration(event, me, EventRegistrationStatus.PENDING_PAYMENT)
                val first = client.post("/test/resume/$event") { header("X-Member-Id", me.toString()) }
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText() shouldStartWith "https://checkout.stripe.com/c/pay/"
                sessionRows(reg).single()[PaymentCheckoutSessionTable.amount].compareTo(BigDecimal("25.00")) shouldBe 0
                val second = client.post("/test/resume/$event") { header("X-Member-Id", me.toString()) }
                second.bodyAsText() shouldBe first.bodyAsText()
                sessionRows(reg).size shouldBe 1
                stripe.calls.get() shouldBe 1
            }
        }

        test("two concurrent resumes create exactly one session and make exactly one Stripe call (single flight)") {
            enableGateway()
            val stripe = FakeStripe(delayMillis = 300)
            testApplication {
                application { setup(stripe) }
                val me = createMember()
                val event = createEvent(createMember())
                val reg = insertRegistration(event, me, EventRegistrationStatus.PENDING_PAYMENT)
                val bodies =
                    coroutineScope {
                        (1..2)
                            .map {
                                async {
                                    client
                                        .post(
                                            "/test/resume/$event",
                                        ) { header("X-Member-Id", me.toString()) }
                                        .bodyAsText()
                                }
                            }.awaitAll()
                    }
                bodies[0] shouldBe bodies[1]
                sessionRows(reg).size shouldBe 1
                stripe.calls.get() shouldBe 1
            }
        }

        test("the e-mail path and the RPC resuming at the same time share one session") {
            enableGateway()
            val stripe = FakeStripe(delayMillis = 300)
            testApplication {
                application { setup(stripe) }
                val me = createMember()
                val event = createEvent(createMember())
                val reg = insertRegistration(event, me, EventRegistrationStatus.PENDING_PAYMENT)
                val mailPath =
                    EventRegistrationSubmission(
                        checkoutGateways = mapOf(PaymentProvider.STRIPE to stripe.client),
                        baseUrl = "https://example.org",
                        mailDispatcher =
                            MailDispatcher(
                                transport = NoOpMailTransport(),
                                scope =
                                    CoroutineScope(
                                        SupervisorJob() + Dispatchers.IO,
                                    ),
                            ),
                    )
                coroutineScope {
                    val a = async { mailPath.resumeCheckout(eventId = event, registrationId = reg) }
                    val b = async { client.post("/test/resume/$event") { header("X-Member-Id", me.toString()) }.bodyAsText() }
                    a.await()
                    b.await()
                }
                sessionRows(reg).size shouldBe 1
                stripe.calls.get() shouldBe 1
            }
        }

        test("every non-success is the same Conflict: wrong status, no registration, foreign registration, bad id") {
            enableGateway()
            val stripe = FakeStripe()
            testApplication {
                application { setup(stripe) }
                val me = createMember()
                val other = createMember()
                val organizer = createMember()
                val confirmedEvent = createEvent(organizer)
                insertRegistration(confirmedEvent, me, EventRegistrationStatus.CONFIRMED)
                val waitlistEvent = createEvent(organizer)
                insertRegistration(waitlistEvent, me, EventRegistrationStatus.WAITLISTED)
                val cancelledEvent = createEvent(organizer)
                insertRegistration(cancelledEvent, me, EventRegistrationStatus.CANCELLED)
                val expiredEvent = createEvent(organizer)
                insertRegistration(expiredEvent, me, EventRegistrationStatus.EXPIRED)
                val emptyEvent = createEvent(organizer)
                val foreignEvent = createEvent(organizer)
                val foreignReg = insertRegistration(foreignEvent, other, EventRegistrationStatus.PENDING_PAYMENT)
                listOf(
                    confirmedEvent.toString(),
                    waitlistEvent.toString(),
                    cancelledEvent.toString(),
                    expiredEvent.toString(),
                    emptyEvent.toString(),
                    foreignEvent.toString(),
                    "nope",
                    Uuid.random().toString(),
                ).forEach { id ->
                    val r = client.post("/test/resume/$id") { header("X-Member-Id", me.toString()) }
                    r.status shouldBe HttpStatusCode.Conflict
                    r.bodyAsText() shouldBe refused
                }
                sessionRows(foreignReg).size shouldBe 0
                stripe.calls.get() shouldBe 0
            }
        }

        test(
            "expired hold, cancelled or started event, missing gateway and Stripe errors are refused; a Stripe error keeps PENDING_PAYMENT",
        ) {
            val organizer = createMember()
            val me = createMember()
            // hold elapsed
            enableGateway()
            testApplication {
                application { setup(FakeStripe()) }
                val e1 = createEvent(organizer)
                val r1 =
                    insertRegistration(e1, me, EventRegistrationStatus.PENDING_PAYMENT, holdExpiresAt = LocalDateTime(2020, 1, 1, 0, 0))
                client.post("/test/resume/$e1") { header("X-Member-Id", me.toString()) }.bodyAsText() shouldBe refused
                sessionRows(r1).size shouldBe 0
                // cancelled event
                val me2 = createMember()
                val e2 = createEvent(organizer, status = EventStatus.CANCELLED)
                insertRegistration(e2, me2, EventRegistrationStatus.PENDING_PAYMENT)
                client.post("/test/resume/$e2") { header("X-Member-Id", me2.toString()) }.bodyAsText() shouldBe refused
                // already started event
                val me3 = createMember()
                val e3 = createEvent(organizer, startsAt = LocalDateTime(2020, 1, 1, 10, 0))
                insertRegistration(e3, me3, EventRegistrationStatus.PENDING_PAYMENT)
                client.post("/test/resume/$e3") { header("X-Member-Id", me3.toString()) }.bodyAsText() shouldBe refused
            }
            // Stripe error
            testApplication {
                application { setup(FakeStripe(failWith = HttpStatusCode.PaymentRequired)) }
                val me4 = createMember()
                val e4 = createEvent(organizer)
                val r4 = insertRegistration(e4, me4, EventRegistrationStatus.PENDING_PAYMENT)
                client.post("/test/resume/$e4") { header("X-Member-Id", me4.toString()) }.bodyAsText() shouldBe refused
                transaction {
                    EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq r4 }.single()[EventRegistrationTable.status]
                } shouldBe
                    EventRegistrationStatus.PENDING_PAYMENT
            }
            // gateway missing
            testApplication {
                application { setup(null) }
                val me5 = createMember()
                val e5 = createEvent(organizer)
                insertRegistration(e5, me5, EventRegistrationStatus.PENDING_PAYMENT)
                client.post("/test/resume/$e5") { header("X-Member-Id", me5.toString()) }.bodyAsText() shouldBe refused
            }
        }

        test("the write rate limit applies") {
            testApplication {
                application { setup(null, FederationInboxRateLimiter(maxRequests = 2, window = 60.minutes)) }
                val me = createMember()
                val bodies =
                    (1..3).map {
                        client
                            .post(
                                "/test/resume/${Uuid.random()}",
                            ) { header("X-Member-Id", me.toString()) }
                            .bodyAsText()
                    }
                bodies[0] shouldBe refused
                bodies[2] shouldBe "Zu viele Anfragen -- bitte spaeter erneut versuchen."
            }
        }
    })

/** The unchanged H2 run (normal `test` task). */
class EventPaymentResumeRpcTest : EventPaymentResumeRpcScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventPaymentResumeRpcPostgresTest : EventPaymentResumeRpcScenarios(TestDatabase.Postgres())
