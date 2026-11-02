package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import kotlin.uuid.Uuid

/**
 * V1.9.261 -- the paid path of the embed origin: a deferred paid embed registration stores the origin on the row (and creates no checkout
 * session); resuming from the mail link re-validates it against the CURRENT allowlist before it is copied to
 * `payment_checkout_session.embed_origin` -- a removed partner, a disabled embed, a legacy NULL row or a hostile stored value all give NULL
 * while the checkout itself still succeeds. A waitlisted embed registration promoted to a paid seat carries the origin through.
 */
class EventRegistrationEmbedOriginPaidTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val eventIds = mutableListOf<Uuid>()
        val partner = "https://partei.example"
        var sessionCounter = 0

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
                if (eventIds.isNotEmpty()) {
                    val registrationIds =
                        EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId inList eventIds }.map {
                            it[EventRegistrationTable.id]
                        }
                    if (registrationIds.isNotEmpty()) PaymentCheckoutSessionTable.deleteWhere { eventRegistrationId inList registrationIds }
                    EventRegistrationTable.deleteWhere { eventId inList eventIds }
                    EventTable.deleteWhere { id inList eventIds }
                }
                if (memberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere { acknowledgedByMemberId inList memberIds }
                    AccountTable.deleteWhere { memberId inList memberIds }
                    MemberTable.deleteWhere { id inList memberIds }
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EmbedOriginPaid Mitglied"
                    it[email] = "embed-origin-paid-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            memberIds += id
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
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_embed_origin_paid"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_embed_origin_paid"
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        /** A client whose Stripe session id is fresh on every call, so no unique constraint is hit across the tests of this spec. */
        fun freshClient(): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            val id = "cs_test_embed_origin_${sessionCounter++}_${Uuid.random()}"
                            respond(
                                """{"id":"$id","url":"https://checkout.stripe.com/c/pay/$id"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        fun configFor(raw: String?) =
            if (raw == null) {
                EmbedConfig.DISABLED
            } else {
                EmbedConfig(
                    enabled = true,
                    allowlist = EmbedOriginAllowlist.parse(raw = raw, allowInsecure = false).allowlist,
                    allowInsecureOrigins = false,
                )
            }

        fun submissionWith(embed: EmbedConfig) =
            EventRegistrationSubmission(
                checkoutGateways = mapOf(PaymentProvider.STRIPE to freshClient()),
                baseUrl = "https://example.org",
                mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                embedConfig = embed,
            )

        fun createPaidEvent(capacity: Int? = 10): Uuid {
            val organizer = createMember()
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "embed-origin-paid-$id"
                    it[title] = "Embed-Origin-Paid"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[EventTable.capacity] = capacity
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
            eventIds += id
            return id
        }

        fun guest(n: Int = 1) =
            EventParticipant.Guest(name = "Gast $n", normalizedEmail = "embed-origin-paid-${Uuid.random()}-$n@example.org")

        /** Registers a paid guest through the deferred path and returns the registration id. */
        fun register(
            eventId: Uuid,
            origin: String?,
            n: Int = 1,
        ): Pair<Uuid, EventRegistrationResult> {
            val result =
                runBlocking {
                    submissionWith(
                        configFor(partner),
                    ).submit(eventId = eventId, participant = guest(n), embedOrigin = origin, deferCheckout = true)
                }
            val id =
                when (result) {
                    is EventRegistrationResult.PaymentLinkSent -> result.registrationId
                    is EventRegistrationResult.Waitlisted -> result.registrationId
                    else -> error("unexpected $result")
                }
            return id to result
        }

        fun storedOrigin(id: Uuid): String? =
            transaction {
                EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq id }.single()[EventRegistrationTable.embedOrigin]
            }

        fun sessionOrigins(id: Uuid): List<String?> =
            transaction {
                PaymentCheckoutSessionTable
                    .selectAll()
                    .where { PaymentCheckoutSessionTable.eventRegistrationId eq id }
                    .map { it[PaymentCheckoutSessionTable.embedOrigin] }
            }

        test("a deferred paid embed registration stores the origin and creates no checkout session") {
            enableGateway()
            val eventId = createPaidEvent()
            val (id, _) = register(eventId, partner)
            storedOrigin(id) shouldBe partner
            sessionOrigins(id) shouldBe emptyList()
        }

        test("resume with the partner still on the allowlist copies the origin; the redirect is the provider's own") {
            enableGateway()
            val eventId = createPaidEvent()
            val (id, _) = register(eventId, partner)
            val result =
                runBlocking {
                    submissionWith(
                        configFor("$partner,https://www.partei.example"),
                    ).resumeCheckout(eventId = eventId, registrationId = id)
                }
            val redirect = result.shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>().redirectUrl
            redirect.startsWith("https://checkout.stripe.com/c/pay/cs_test_embed_origin_") shouldBe true
            sessionOrigins(id) shouldBe listOf(partner)
            storedOrigin(id) shouldBe partner
        }

        test("resume after the partner was removed from the allowlist writes NULL, the checkout still succeeds, the row keeps its origin") {
            enableGateway()
            val eventId = createPaidEvent()
            val (id, _) = register(eventId, partner)
            val result =
                runBlocking { submissionWith(configFor("https://andere.example")).resumeCheckout(eventId = eventId, registrationId = id) }
            result.shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>()
            sessionOrigins(id) shouldBe listOf(null)
            storedOrigin(id) shouldBe partner
        }

        test("resume with the embed feature disabled, or with the default submission, writes NULL") {
            enableGateway()
            val eventId = createPaidEvent()
            val (a, _) = register(eventId, partner, n = 1)
            runBlocking {
                submissionWith(EmbedConfig(enabled = false, allowlist = configFor(partner).allowlist, allowInsecureOrigins = false))
                    .resumeCheckout(eventId = eventId, registrationId = a)
            }.shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>()
            sessionOrigins(a) shouldBe listOf(null)
            val (b, _) = register(eventId, partner, n = 2)
            runBlocking { submissionWith(EmbedConfig.DISABLED).resumeCheckout(eventId = eventId, registrationId = b) }
                .shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>()
            sessionOrigins(b) shouldBe listOf(null)
        }

        test("a registration without an origin (also: created before V137) resumes with a NULL session origin") {
            enableGateway()
            val eventId = createPaidEvent()
            val (id, _) = register(eventId, origin = null)
            storedOrigin(id) shouldBe null
            runBlocking { submissionWith(configFor(partner)).resumeCheckout(eventId = eventId, registrationId = id) }
                .shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>()
            sessionOrigins(id) shouldBe listOf(null)
        }

        test("a hostile value written to the row is never copied to the session") {
            enableGateway()
            val eventId = createPaidEvent()
            val hostile =
                listOf(
                    "javascript:alert(1)",
                    "https://partei.example.evil.com",
                    "https://evilpartei.example",
                    "https://user@partei.example",
                    "HTTPS://PARTEI.EXAMPLE",
                    "https://partei.example/",
                    "*",
                    "https://partei.example\r\nSet-Cookie: x",
                )
            hostile.forEachIndexed { index, value ->
                val (id, _) = register(eventId, origin = null, n = index)
                transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq id }) { it[embedOrigin] = value } }
                runBlocking { submissionWith(configFor(partner)).resumeCheckout(eventId = eventId, registrationId = id) }
                    .shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>()
                sessionOrigins(id) shouldBe listOf(null)
            }
        }

        test("a waitlisted embed registration promoted to a paid seat carries the origin into the checkout session") {
            enableGateway()
            val eventId = createPaidEvent(capacity = 1)
            val (first, _) = register(eventId, partner, n = 1)
            val (second, result) = register(eventId, partner, n = 2)
            result.shouldBeInstanceOf<EventRegistrationResult.Waitlisted>()
            storedOrigin(second) shouldBe partner
            val now = DbClock.nowLocalDateTime()
            val (_, promotions) =
                EventCapacityGuard.withEventLock(eventId = eventId, now = now) { _ -> EventStore.cancelRegistration(id = first, now = now) }
            promotions.size shouldBe 1
            val status = transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq second }.single() }
            status[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
            runBlocking { submissionWith(configFor(partner)).resumeCheckout(eventId = eventId, registrationId = second) }
                .shouldBeInstanceOf<EventRegistrationResult.PaymentRequired>()
            sessionOrigins(second) shouldBe listOf(partner)
        }
    })
