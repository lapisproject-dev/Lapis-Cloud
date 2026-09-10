package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
import io.ktor.server.routing.Route
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
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.3 "Veranstaltungs-Anmeldung als einbettbares Website-Widget" --
 * `POST /api/embed/v1/event/{slug}/registration` and its `OPTIONS` preflight. Mirrors
 * `EmbedDonationRoutesTest`'s registration-at-the-`registerEmbedEventRoutes`-level pattern (not
 * `registerEmbedRoutes`) and reuses `EventPublicRoutesTest`'s fixture idioms.
 */
class EmbedEventRoutesTest :
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
                    it[displayName] = "EmbedEventRoutesTest Mitglied"
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
                    it[acknowledgedByMemberId] = createMember("embed-event-routes-ack-${Uuid.random()}@example.org")
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
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_embed_event_routes"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_embed_event_routes"
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
                                """{"id":"cs_test_embed_event_fake","url":"https://checkout.stripe.com/c/pay/cs_test_embed_event_fake"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        fun failingCheckoutClient(pspConfigState: PspConfigState.Configured): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"error":{"message":"a secret Stripe-side rejection reason"}}""",
                                HttpStatusCode.BadRequest,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        fun noOpMailDispatcher() = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createEvent(
            feeAmount: BigDecimal,
            capacity: Int? = 10,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
        ): Pair<Uuid, String> {
            val organizer = createMember("embed-event-routes-organizer-${Uuid.random()}@example.org")
            val id = Uuid.random()
            val slug = "embed-event-routes-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[title] = "Embed-Event-Routes-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
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

        fun createFreeEvent(capacity: Int? = 10) = createEvent(feeAmount = BigDecimal("0.00"), capacity = capacity)

        fun createFreeEventWithCapacity(n: Int) = createFreeEvent(capacity = n)

        fun createPaidEvent() = createEvent(feeAmount = BigDecimal("25.00"))

        suspend fun testApp(
            checkoutClient: StripeCheckoutClient? = null,
            pspConfigState: PspConfigState = PspConfigState.NotConfigured,
            attemptRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            registrationRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            pageRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedEventRoutes(
                            config = enabledConfig,
                            pspConfigState = pspConfigState,
                            checkoutClient = checkoutClient,
                            mailDispatcher = noOpMailDispatcher(),
                            baseUrl = "https://lapis.example",
                            attemptRateLimiter = attemptRateLimiter,
                            registrationRateLimiter = registrationRateLimiter,
                            pageRateLimiter = pageRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        suspend fun ApplicationTestBuilder.postRegistration(
            slug: String,
            body: String,
        ) = client.post("/api/embed/v1/event/$slug/registration") {
            header(HttpHeaders.Origin, "https://partei.example")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        fun registrationCountFor(eventId: Uuid): Long =
            transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq eventId }.count() }

        test("Confirmed/AlreadyRegistered/Honeypot all return byte-identical {\"outcome\":\"CONFIRMED\"} at 200") {
            testApp {
                val (eventId, slug) = createFreeEvent()
                val confirmed = postRegistration(slug, """{"guestName":"Anna","guestEmail":"anna@example.org"}""")
                confirmed.status shouldBe HttpStatusCode.OK
                confirmed.bodyAsText() shouldBe """{"outcome":"CONFIRMED"}"""

                val alreadyRegistered = postRegistration(slug, """{"guestName":"Anna","guestEmail":"anna@example.org"}""")
                alreadyRegistered.status shouldBe HttpStatusCode.OK
                alreadyRegistered.bodyAsText() shouldBe """{"outcome":"CONFIRMED"}"""

                val honeypot = postRegistration(slug, """{"guestName":"Bot","guestEmail":"bot@example.org","kommentar":"x"}""")
                honeypot.status shouldBe HttpStatusCode.OK
                honeypot.bodyAsText() shouldBe """{"outcome":"CONFIRMED"}"""

                registrationCountFor(eventId) shouldBe 1L
            }
        }

        test("no oracle: response body never contains registrationId or position") {
            testApp {
                val (_, slug) = createFreeEvent()
                val confirmed = postRegistration(slug, """{"guestName":"Carla","guestEmail":"carla@example.org"}""")
                confirmed.bodyAsText() shouldNotContain "registrationId"
                confirmed.bodyAsText() shouldNotContain "position"

                val (_, capOneSlug) = createFreeEventWithCapacity(1)
                postRegistration(capOneSlug, """{"guestName":"First","guestEmail":"first@example.org"}""")
                val waitlisted = postRegistration(capOneSlug, """{"guestName":"Second","guestEmail":"second@example.org"}""")
                waitlisted.bodyAsText() shouldNotContain "registrationId"
                waitlisted.bodyAsText() shouldNotContain "position"
            }
        }

        test("404 identity: unknown slug, DRAFT, CANCELLED, MEMBERS_ONLY all render the same {\"outcome\":\"NOT_AVAILABLE\"}") {
            testApp {
                val unknown = postRegistration("no-such-slug", """{"guestName":"A","guestEmail":"a@example.org"}""")
                unknown.status shouldBe HttpStatusCode.NotFound
                unknown.bodyAsText() shouldBe """{"outcome":"NOT_AVAILABLE"}"""

                val (draftId, draftSlug) = createFreeEvent()
                transaction { EventTable.update({ EventTable.id eq draftId }) { it[status] = EventStatus.DRAFT } }
                val draft = postRegistration(draftSlug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                draft.status shouldBe HttpStatusCode.NotFound
                draft.bodyAsText() shouldBe """{"outcome":"NOT_AVAILABLE"}"""

                val (cancelledId, cancelledSlug) = createFreeEvent()
                transaction { EventTable.update({ EventTable.id eq cancelledId }) { it[status] = EventStatus.CANCELLED } }
                val cancelled = postRegistration(cancelledSlug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                cancelled.status shouldBe HttpStatusCode.NotFound
                cancelled.bodyAsText() shouldBe """{"outcome":"NOT_AVAILABLE"}"""

                val (membersOnlyId, membersOnlySlug) = createFreeEvent()
                transaction {
                    EventTable.update({ EventTable.id eq membersOnlyId }) { it[visibility] = EventVisibility.MEMBERS_ONLY }
                }
                val membersOnly = postRegistration(membersOnlySlug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                membersOnly.status shouldBe HttpStatusCode.NotFound
                membersOnly.bodyAsText() shouldBe """{"outcome":"NOT_AVAILABLE"}"""
            }
        }

        test("honeypot does not consume the strict registration budget") {
            val strict = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(registrationRateLimiter = strict) {
                val (_, slug) = createFreeEvent()
                repeat(3) {
                    val honeypot =
                        postRegistration(slug, """{"guestName":"Bot","guestEmail":"bot-$it@example.org","kommentar":"x"}""")
                    honeypot.status shouldBe HttpStatusCode.OK
                }
                val real = postRegistration(slug, """{"guestName":"Real","guestEmail":"real@example.org"}""")
                real.status shouldBe HttpStatusCode.OK
                real.bodyAsText() shouldBe """{"outcome":"CONFIRMED"}"""
            }
        }

        test("honeypot never writes a registration row") {
            testApp {
                val (eventId, slug) = createFreeEvent()
                postRegistration(slug, """{"guestName":"Bot","guestEmail":"bot@example.org","kommentar":"x"}""")
                registrationCountFor(eventId) shouldBe 0L
            }
        }

        test("missing Origin header -> 403, no body") {
            testApp {
                val (_, slug) = createFreeEvent()
                val response =
                    client.post("/api/embed/v1/event/$slug/registration") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"guestName":"A","guestEmail":"a@example.org"}""")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("disallowed Origin -> 403, no echo of the seen origin") {
            testApp {
                val (_, slug) = createFreeEvent()
                val response =
                    client.post("/api/embed/v1/event/$slug/registration") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"guestName":"A","guestEmail":"a@example.org"}""")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                response.bodyAsText().contains("evil.example") shouldBe false
            }
        }

        test("wrong Content-Type -> 415, empty body") {
            testApp {
                val (_, slug) = createFreeEvent()
                val response =
                    client.post("/api/embed/v1/event/$slug/registration") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Text.Plain)
                        setBody("""{"guestName":"A","guestEmail":"a@example.org"}""")
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
                response.bodyAsText() shouldBe ""
            }
        }

        test("4097-byte body -> 413") {
            testApp {
                val (_, slug) = createFreeEvent()
                val response = postRegistration(slug, """{"guestName":"${"x".repeat(4090)}"}""")
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test("OPTIONS preflight never consumes either registration budget") {
            val attempt = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            val strict = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(attemptRateLimiter = attempt, registrationRateLimiter = strict) {
                val (_, slug) = createFreeEvent()
                repeat(5) {
                    val preflight =
                        client.options("/api/embed/v1/event/$slug/registration") { header(HttpHeaders.Origin, "https://partei.example") }
                    preflight.status shouldBe HttpStatusCode.NoContent
                    preflight.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "POST, OPTIONS"
                }
                val post = postRegistration(slug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                post.status shouldBe HttpStatusCode.OK
            }
        }

        test("generous rate limit exhausted -> 429 with Retry-After header and RATE_LIMITED body") {
            val strictAttempt = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(attemptRateLimiter = strictAttempt) {
                val (_, slug) = createFreeEvent()
                postRegistration(slug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                val second = postRegistration(slug, """{"guestName":"B","guestEmail":"b@example.org"}""")
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
                second.bodyAsText().contains(""""outcome":"RATE_LIMITED"""") shouldBe true
                second.bodyAsText().contains(""""retryAfterSeconds"""") shouldBe true
            }
        }

        test("PAYMENT_REQUIRED: paid event with a usable gateway returns a redirectUrl") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            testApp(checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val (eventId, slug) = createPaidEvent()
                val response = postRegistration(slug, """{"guestName":"Payer","guestEmail":"payer@example.org"}""")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe
                    """{"outcome":"PAYMENT_REQUIRED","redirectUrl":"https://checkout.stripe.com/c/pay/cs_test_embed_event_fake"}"""

                // Security-Review MINOR fix: the checkout session's embed_origin must record the
                // canonical allowlist origin that came in via applyEmbedCors, not null -- otherwise
                // an operator cannot tell this session apart from one created by the server-rendered
                // form route (which has no embed origin at all).
                val registrationId =
                    transaction {
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.eventId eq eventId }
                            .single()[EventRegistrationTable.id]
                    }
                val storedEmbedOrigin =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.eventRegistrationId eq registrationId }
                            .single()[PaymentCheckoutSessionTable.embedOrigin]
                    }
                storedEmbedOrigin shouldBe "https://partei.example"
            }
        }

        test("GatewayUnavailable: paid event, gateway not enabled -> 503 UNAVAILABLE") {
            testApp {
                val (_, slug) = createPaidEvent()
                val response = postRegistration(slug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldBe """{"outcome":"UNAVAILABLE"}"""
            }
        }

        test("StripeFailed: Stripe rejects the checkout -> 502 GATEWAY_ERROR, Stripe's own text never appears") {
            enableGateway()
            val pspConfigState = testPspConfigState()
            testApp(checkoutClient = failingCheckoutClient(pspConfigState), pspConfigState = pspConfigState) {
                val (_, slug) = createPaidEvent()
                val response = postRegistration(slug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                response.status shouldBe HttpStatusCode.BadGateway
                response.bodyAsText() shouldBe """{"outcome":"GATEWAY_ERROR"}"""
                response.bodyAsText().contains("secret Stripe-side") shouldBe false
            }
        }

        test("Waitlisted: capacity=1 event, one seat already occupied -> exact {\"outcome\":\"WAITLISTED\"}, no position") {
            testApp {
                val (_, slug) = createFreeEventWithCapacity(1)
                postRegistration(slug, """{"guestName":"First","guestEmail":"first@example.org"}""")
                val second = postRegistration(slug, """{"guestName":"Second","guestEmail":"second@example.org"}""")
                second.status shouldBe HttpStatusCode.OK
                second.bodyAsText() shouldBe """{"outcome":"WAITLISTED"}"""
            }
        }

        test("BAD_REQUEST: malformed JSON, blank guestName, missing guestEmail, oversized name/email") {
            testApp {
                val (_, slug) = createFreeEvent()

                val malformed = postRegistration(slug, "not json at all")
                malformed.status shouldBe HttpStatusCode.BadRequest
                malformed.bodyAsText() shouldBe """{"outcome":"BAD_REQUEST"}"""

                val blankName = postRegistration(slug, """{"guestName":"  ","guestEmail":"a@example.org"}""")
                blankName.status shouldBe HttpStatusCode.BadRequest
                blankName.bodyAsText() shouldBe """{"outcome":"BAD_REQUEST"}"""

                val noEmail = postRegistration(slug, """{"guestName":"A"}""")
                noEmail.status shouldBe HttpStatusCode.BadRequest
                noEmail.bodyAsText() shouldBe """{"outcome":"BAD_REQUEST"}"""

                val longName = postRegistration(slug, """{"guestName":"${"x".repeat(301)}","guestEmail":"a@example.org"}""")
                longName.status shouldBe HttpStatusCode.BadRequest
                longName.bodyAsText() shouldBe """{"outcome":"BAD_REQUEST"}"""

                val longEmailLocal = "x".repeat(310)
                val longEmail = postRegistration(slug, """{"guestName":"A","guestEmail":"$longEmailLocal@example.org"}""")
                longEmail.status shouldBe HttpStatusCode.BadRequest
                longEmail.bodyAsText() shouldBe """{"outcome":"BAD_REQUEST"}"""
            }
        }

        test("nosniff is present on every response, exactly once (200/400/429/404)") {
            // maxRequests = 2 -- the attemptRateLimiter (step 6) runs BEFORE the JSON decode (step
            // 7), so it must let BOTH the "ok" and the "badRequest" call through and only reject the
            // THIRD one. maxRequests = 1 (the previous value) silently turned "badRequest" into a
            // second 429 instead of exercising the actual 400 branch -- the assertion on
            // badRequest.status below is what would have caught that.
            val strictAttempt = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(attemptRateLimiter = strictAttempt) {
                val (_, slug) = createFreeEvent()

                val ok = postRegistration(slug, """{"guestName":"A","guestEmail":"a@example.org"}""")
                ok.status shouldBe HttpStatusCode.OK
                ok.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")

                val badRequest = postRegistration(slug, "not json")
                badRequest.status shouldBe HttpStatusCode.BadRequest
                badRequest.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")

                val rateLimited = postRegistration(slug, """{"guestName":"B","guestEmail":"b@example.org"}""")
                rateLimited.status shouldBe HttpStatusCode.TooManyRequests
                rateLimited.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")
            }
            testApp {
                val notFound = postRegistration("no-such-slug", """{"guestName":"A","guestEmail":"a@example.org"}""")
                notFound.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")
            }
        }

        // Review finding (Welle V1.4.3.3, MINOR): the whole point of reusing the SAME
        // FederationInboxRateLimiter *instances* across registerEventPublicRoutes' own form route
        // AND registerEmbedEventRoutes (see Application.kt's eventRegistrationRateLimiter KDoc,
        // registerEmbedEventRoutes' own KDoc step 11, and docs/api/embed-widgets.adoc) is that the
        // embed widget must not grant a caller a second, independent 5/60min budget the form route
        // doesn't already cap them to. No test verified that claim before this one -- every OTHER
        // test in this file builds its OWN, private limiter instances, and EventPublicRoutesTest
        // never registers the embed route at all. A future refactor that quietly gave the embed
        // path its own limiter (e.g. "so widget traffic doesn't slow down the website form") would
        // silently double the real per-IP cap without failing any test.
        fun formBody(
            guestName: String,
            guestEmail: String,
        ): String =
            Parameters
                .build {
                    append("guestName", guestName)
                    append("guestEmail", guestEmail)
                }.formUrlEncode()

        fun Route.registerBothRouteFamilies(sharedRegistrationLimiter: FederationInboxRateLimiter) {
            registerEventPublicRoutes(
                pspConfigState = PspConfigState.NotConfigured,
                checkoutClient = null,
                baseUrl = "https://lapis.example",
                mailDispatcher = noOpMailDispatcher(),
                brandTitle = "Testverein",
                pageRateLimiter = generousLimiter(),
                attemptRateLimiter = generousLimiter(),
                registrationRateLimiter = sharedRegistrationLimiter,
                ticketPageRateLimiter = generousLimiter(),
                ticketCodeFailureLimiter =
                    network.lapis.cloud.server.security
                        .LoginRateLimiter(maxFailures = 10_000),
            )
            registerEmbedEventRoutes(
                config = enabledConfig,
                pspConfigState = PspConfigState.NotConfigured,
                checkoutClient = null,
                mailDispatcher = noOpMailDispatcher(),
                baseUrl = "https://lapis.example",
                attemptRateLimiter = generousLimiter(),
                registrationRateLimiter = sharedRegistrationLimiter,
                pageRateLimiter = generousLimiter(),
            )
        }

        test("shared registrationRateLimiter budget: the embed widget cannot double the form route's cap") {
            val sharedLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            val (_, slug) = createFreeEvent()

            testApplication {
                application { routing { registerBothRouteFamilies(sharedLimiter) } }

                // The FORM route consumes the shared budget's one allowed slot.
                val formResponse =
                    client.post("/veranstaltung/$slug/anmeldung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("Anna", "anna-shared@example.org"))
                    }
                formResponse.status shouldBe HttpStatusCode(303, "See Other")

                // The EMBED WIDGET, from the SAME remote host, must already be rate-limited -- it
                // must NOT get its own separate budget.
                val embedResponse = postRegistration(slug, """{"guestName":"Bob","guestEmail":"bob-shared@example.org"}""")
                embedResponse.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("shared registrationRateLimiter budget: the form route cannot double the embed widget's cap") {
            val sharedLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            val (_, slug) = createFreeEvent()

            testApplication {
                application { routing { registerBothRouteFamilies(sharedLimiter) } }

                // The EMBED WIDGET consumes the shared budget's one allowed slot first.
                val embedResponse = postRegistration(slug, """{"guestName":"Carla","guestEmail":"carla-shared@example.org"}""")
                embedResponse.status shouldBe HttpStatusCode.OK

                // The FORM route, from the SAME remote host, must already be rate-limited.
                val formResponse =
                    client.post("/veranstaltung/$slug/anmeldung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(formBody("Dirk", "dirk-shared@example.org"))
                    }
                formResponse.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
