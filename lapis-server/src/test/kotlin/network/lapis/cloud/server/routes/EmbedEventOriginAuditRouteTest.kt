package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
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
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.events.EventNoticeMailLimiter
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.server.security.LoginRateLimiter
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
 * V1.9.261 -- the partner origin of a JSON-embed registration: the route stores the canonical ALLOWLIST ENTRY (never the raw header), a foreign
 * origin is refused before anything is stored, the form route stores nothing, and the mail-link checkout (`POST /veranstaltung/{slug}/zahlung`)
 * copies the origin to the checkout session only while the partner is still on the allowlist -- with an identical response either way.
 */
class EmbedEventOriginAuditRouteTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val partner = "https://partei.example"

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
                        EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId inList createdEventIds }.map {
                            it[EventRegistrationTable.id]
                        }
                    if (registrationIds.isNotEmpty()) PaymentCheckoutSessionTable.deleteWhere { eventRegistrationId inList registrationIds }
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere { acknowledgedByMemberId inList createdMemberIds }
                    AccountTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EmbedOriginAuditRoute Mitglied"
                    it[email] = "embed-origin-route-$id@example.org"
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
            val acknowledger = createMember()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = acknowledger
                    it[acknowledgedAt] = LocalDateTime(2030, 1, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        val pspConfigState =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_embed_origin_route"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_embed_origin_route"
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        /** Every call answers with the SAME session id, so each test that reaches Stripe needs its own spec-unique id: pass [sessionId]. */
        fun checkoutClient(sessionId: String): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"id":"$sessionId","url":"https://checkout.stripe.com/c/pay/$sessionId"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        fun noOpMailDispatcher() = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        fun configFor(raw: String) =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = raw, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        fun createEvent(fee: BigDecimal): Pair<Uuid, String> {
            val organizer = createMember()
            val id = Uuid.random()
            val slug = "embed-origin-route-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[title] = "Embed-Origin-Route"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[capacity] = 10
                    it[feeAmount] = fee
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

        /** Both route families in one application: the embed route always sees [partner]; the public (mail-link) route sees [publicEmbed]. */
        suspend fun testApp(
            publicEmbed: EmbedConfig,
            sessionId: String = "cs_test_embed_origin_route_unused",
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            val gateways = mapOf(PaymentProvider.STRIPE to checkoutClient(sessionId))
            testApplication {
                application {
                    routing {
                        registerEmbedEventRoutes(
                            config = configFor(partner),
                            checkoutGateways = gateways,
                            mailDispatcher = noOpMailDispatcher(),
                            baseUrl = "https://lapis.example",
                            attemptRateLimiter = generousLimiter(),
                            registrationRateLimiter = generousLimiter(),
                            pageRateLimiter = generousLimiter(),
                            noticeMailLimiter = EventNoticeMailLimiter(),
                        )
                        registerEventPublicRoutes(
                            checkoutGateways = gateways,
                            baseUrl = "https://example.org",
                            mailDispatcher = noOpMailDispatcher(),
                            branding = eventTestBranding(),
                            navAvailability = eventTestNav(),
                            embedConfig = publicEmbed,
                            pageRateLimiter = generousLimiter(),
                            attemptRateLimiter = generousLimiter(),
                            registrationRateLimiter = generousLimiter(),
                            ticketPageRateLimiter = generousLimiter(),
                            ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                            icsFeedRateLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        suspend fun ApplicationTestBuilder.postEmbed(
            slug: String,
            email: String,
            origin: String?,
        ) = client.post("/api/embed/v1/event/$slug/registration") {
            if (origin != null) header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody("""{"guestName":"Anna","guestEmail":"$email"}""")
        }

        fun rowsOf(eventId: Uuid) =
            transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq eventId }.toList() }

        test("an allowed origin is stored as the canonical allowlist entry, whatever spelling the header used") {
            testApp(publicEmbed = configFor(partner)) {
                val (eventId, slug) = createEvent(BigDecimal("0.00"))
                postEmbed(slug, "a1@example.org", "https://PARTEI.example:443").status shouldBe HttpStatusCode.OK
                postEmbed(slug, "a2@example.org", partner).status shouldBe HttpStatusCode.OK
                rowsOf(eventId).map { it[EventRegistrationTable.embedOrigin] } shouldBe listOf(partner, partner)
            }
        }

        test("a foreign, prefixed, suffixed, userinfo, null or missing origin is refused with 403 and stores nothing") {
            testApp(publicEmbed = configFor(partner)) {
                val (eventId, slug) = createEvent(BigDecimal("0.00"))
                listOf(
                    "https://evil.example",
                    "https://partei.example.evil.com",
                    "https://evilpartei.example",
                    "https://user@partei.example",
                    "null",
                    null,
                ).forEachIndexed { index, origin ->
                    postEmbed(slug, "x$index@example.org", origin).status shouldBe HttpStatusCode.Forbidden
                }
                rowsOf(eventId).size shouldBe 0
            }
        }

        test("the server-rendered form stores no origin") {
            testApp(publicEmbed = configFor(partner)) {
                val (eventId, slug) = createEvent(BigDecimal("0.00"))
                val response =
                    client.post("/veranstaltung/$slug/anmeldung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("guestName", "Form Gast")
                                    append("guestEmail", "form-gast@example.org")
                                }.formUrlEncode(),
                        )
                    }
                (response.status.value in 200..399) shouldBe true
                val rows = rowsOf(eventId)
                rows.size shouldBe 1
                rows.single()[EventRegistrationTable.embedOrigin] shouldBe null
            }
        }

        test(
            "mail-link checkout copies the origin while the partner is allowlisted, writes NULL once it is removed, and answers identically",
        ) {
            enableGateway()
            val outcomes = mutableListOf<Pair<HttpStatusCode, String?>>()
            val origins = mutableListOf<List<String?>>()
            listOf(
                configFor(partner) to "cs_test_embed_origin_route_a",
                configFor("https://andere.example") to "cs_test_embed_origin_route_b",
            ).forEachIndexed { index, (publicEmbed, sessionId) ->
                testApp(publicEmbed = publicEmbed, sessionId = sessionId) {
                    val (eventId, slug) = createEvent(BigDecimal("25.00"))
                    postEmbed(slug, "payer$index@example.org", partner).status shouldBe HttpStatusCode.OK
                    val row = rowsOf(eventId).single()
                    row[EventRegistrationTable.embedOrigin] shouldBe partner
                    // the real token only exists in the mail; a fixture token stands in for it
                    val token = "token-" + Uuid.random()
                    transaction {
                        EventRegistrationTable.update({ EventRegistrationTable.id eq row[EventRegistrationTable.id] }) {
                            it[cancelTokenSha256] = sha256Hex(token.toByteArray(Charsets.US_ASCII))
                        }
                    }
                    val response =
                        client.post("/veranstaltung/$slug/zahlung") {
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody(Parameters.build { append("token", token) }.formUrlEncode())
                        }
                    outcomes += response.status to response.headers[HttpHeaders.Location]?.substringBeforeLast('_')
                    origins +=
                        transaction {
                            PaymentCheckoutSessionTable
                                .selectAll()
                                .where { PaymentCheckoutSessionTable.eventRegistrationId eq row[EventRegistrationTable.id] }
                                .map { it[PaymentCheckoutSessionTable.embedOrigin] }
                        }
                }
            }
            origins shouldBe listOf(listOf<String?>(partner), listOf<String?>(null))
            outcomes[0].first shouldBe HttpStatusCode(303, "See Other")
            outcomes[1] shouldBe outcomes[0]
        }
    })
