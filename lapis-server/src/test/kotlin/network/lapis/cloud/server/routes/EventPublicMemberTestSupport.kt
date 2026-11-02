package network.lapis.cloud.server.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.events.EventNoticeMailLimiter
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.payment.psp.PspCheckoutGateway
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.rpc.FriendRegistrationConfig
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** One mail the recording transport saw. */
internal data class SentMail(
    val to: String,
    val subject: String,
    val plainText: String,
    val html: String,
)

/** A [MailTransport] that records everything; with a single-sender [MailDispatcher] the order is the enqueue order. */
internal class RecordingMailTransport : MailTransport {
    val sent = CopyOnWriteArrayList<SentMail>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        sent += SentMail(to = to, subject = subject, plainText = plainTextBody, html = htmlBody)
        return MailSendOutcome.Sent
    }
}

/**
 * A dispatcher + transport pair for tests. [settle] is deterministic, not a sleep: with exactly ONE sender the queue is FIFO, so once a
 * canary mail enqueued AFTER the request shows up in the transport, every mail the request enqueued has been sent already.
 */
internal class RecordingMail {
    val transport = RecordingMailTransport()
    val dispatcher =
        MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), maxConcurrentSends = 1)

    /** Waits for everything enqueued so far and returns the mails received since the last call (canary excluded). */
    suspend fun settle(): List<SentMail> {
        val canary = "canary-${Uuid.random()}@example.invalid"
        dispatcher.enqueue(to = canary, subject = "canary", plainTextBody = "c", htmlBody = "c", purpose = "event-registration")
        repeat(500) {
            if (transport.sent.any { it.to == canary }) {
                val all = transport.sent.filter { it.to != canary }
                transport.sent.clear()
                return all
            }
            kotlinx.coroutines.delay(20)
        }
        error("the mail dispatcher did not deliver the canary")
    }
}

internal fun generousEventLimiter(): FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

internal const val EVENT_TEST_FAR_FUTURE_YEAR = 2030

/**
 * Database fixtures for the V1.9.259 public-event tests: members (with account, so a session resolves), events, registrations, sessions
 * and an enabled Stripe test gateway. [cleanup] removes everything created, in FK order.
 */
internal class EventPublicMemberFixtures {
    private val memberIds = mutableListOf<Uuid>()
    private val eventIds = mutableListOf<Uuid>()
    private val sessionMemberIds = mutableListOf<Uuid>()

    fun createMember(
        email: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        displayName: String = "Fixture Mitglied",
        anonymized: Boolean = false,
        withAccount: Boolean = true,
        emailVerified: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[MemberTable.email] = email
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                if (anonymized) it[anonymizedAt] = LocalDateTime(2026, 6, 1, 12, 0)
                if (emailVerified) it[emailVerifiedAt] = LocalDateTime(2026, 6, 1, 12, 0)
            }
            if (withAccount) {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
        }
        memberIds += id
        return id
    }

    private val organizer: Uuid by lazy { createMember(email = "event-public-fixture-organizer-${Uuid.random()}@example.org") }

    fun createEvent(
        capacity: Int? = null,
        fee: BigDecimal = BigDecimal.ZERO,
        status: EventStatus = EventStatus.PUBLISHED,
        visibility: EventVisibility = EventVisibility.PUBLIC,
        startsAt: LocalDateTime = LocalDateTime(EVENT_TEST_FAR_FUTURE_YEAR, 1, 1, 18, 0),
        endsAt: LocalDateTime = LocalDateTime(EVENT_TEST_FAR_FUTURE_YEAR, 1, 1, 22, 0),
        title: String = "Mitglieder-Test-Veranstaltung",
    ): Pair<Uuid, String> {
        val id = Uuid.random()
        val slug = "member-aware-$id"
        transaction {
            EventTable.insert {
                it[EventTable.id] = id
                it[EventTable.slug] = slug
                it[EventTable.title] = title
                it[description] = "test"
                it[locationText] = "Testort"
                it[onlineUrl] = null
                it[EventTable.startsAt] = startsAt
                it[EventTable.endsAt] = endsAt
                it[EventTable.capacity] = capacity
                it[feeAmount] = fee
                it[feeCurrency] = "EUR"
                it[EventTable.status] = status
                it[EventTable.visibility] = visibility
                it[registrationClosesAt] = null
                it[createdAt] = DbClock.nowLocalDateTime()
                it[createdBy] = organizer
                it[cancelledAt] = null
            }
        }
        eventIds += id
        return id to slug
    }

    /** A real session; returns the RAW cookie value. */
    fun sessionFor(memberId: Uuid): String {
        sessionMemberIds += memberId
        return SessionStore.createSession(memberId).rawToken
    }

    /** Inserts a registration of a member or a guest directly. */
    fun insertRegistration(
        eventId: Uuid,
        memberId: Uuid? = null,
        guestEmail: String? = null,
        status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
        holdExpiresAt: LocalDateTime? = null,
        fee: BigDecimal = BigDecimal.ZERO,
        waitlistPosition: Int? = null,
    ): Uuid {
        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        val inactive = status == EventRegistrationStatus.CANCELLED || status == EventRegistrationStatus.EXPIRED
        val key =
            if (inactive) {
                null
            } else if (memberId != null) {
                "m:$memberId"
            } else {
                "g:$guestEmail"
            }
        transaction {
            EventRegistrationTable.insert {
                it[EventRegistrationTable.id] = id
                it[EventRegistrationTable.eventId] = eventId
                it[EventRegistrationTable.memberId] = memberId
                it[guestName] = if (memberId == null) "Fixture Gast" else null
                it[EventRegistrationTable.guestEmail] = guestEmail
                it[activeParticipantKey] = key
                it[EventRegistrationTable.status] = status
                it[feeAmount] = fee
                it[EventRegistrationTable.holdExpiresAt] = holdExpiresAt
                it[EventRegistrationTable.waitlistPosition] = waitlistPosition
                it[cancelTokenSha256] = sha256Hex("fixture-${Uuid.random()}".toByteArray(Charsets.US_ASCII))
                it[registeredAt] = now
                it[confirmedAt] = if (status == EventRegistrationStatus.CONFIRMED) now else null
                it[cancelledAt] = null
                it[waitlistOfferedAt] = null
            }
        }
        return id
    }

    fun registrations(eventId: Uuid): List<ResultRow> =
        transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq eventId }.toList() }

    fun registrationCount(eventId: Uuid): Int = registrations(eventId).size

    /** Switches the Stripe gateway on (with its compliance acknowledgement), like `EventPublicRoutesTest`. */
    fun enableGateway() {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentGatewayEnabled] = true
                it[paymentGatewayProvider] = PaymentProvider.STRIPE
            }
            PaymentGatewayComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = createMember(email = "event-public-fixture-ack-${Uuid.random()}@example.org")
                it[acknowledgedAt] = LocalDateTime(2030, 1, 1, 9, 0)
                it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                it[provider] = PaymentProvider.STRIPE
            }
        }
    }

    fun disableGateway() {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentGatewayEnabled] = false
                it[paymentGatewayProvider] = null
            }
        }
    }

    fun cleanup() {
        transaction {
            if (eventIds.isNotEmpty()) {
                val registrationIds =
                    EventRegistrationTable
                        .selectAll()
                        .where { EventRegistrationTable.eventId inList eventIds }
                        .map { it[EventRegistrationTable.id] }
                if (registrationIds.isNotEmpty()) PaymentCheckoutSessionTable.deleteWhere { eventRegistrationId inList registrationIds }
                EventRegistrationTable.deleteWhere { eventId inList eventIds }
                EventTable.deleteWhere { id inList eventIds }
            }
            if (sessionMemberIds.isNotEmpty()) SessionTable.deleteWhere { memberId inList sessionMemberIds }
            if (memberIds.isNotEmpty()) {
                PaymentGatewayComplianceAcknowledgmentTable.deleteWhere { acknowledgedByMemberId inList memberIds }
                AccountTable.deleteWhere { memberId inList memberIds }
                MemberTable.deleteWhere { id inList memberIds }
            }
        }
    }
}

/** A Stripe checkout client over a mock engine that counts its calls -- a call during a guest POST would be a failure of the V1.9.259 design. */
internal class CountingStripe {
    val calls = AtomicInteger(0)
    val state: PspConfigState.Configured =
        PspConfigState.Configured(
            config =
                requireNotNull(
                    (
                        PspConfig.load {
                            when (it) {
                                PspConfig.ENV_SECRET_KEY -> "sk_test_member_aware"
                                PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_member_aware"
                                else -> null
                            }
                        } as? PspConfigState.Configured
                    )?.config,
                ),
        )
    val client: StripeCheckoutClient =
        StripeCheckoutClient(
            pspConfig = state.config,
            httpClient =
                HttpClient(
                    MockEngine { _ ->
                        val n = calls.incrementAndGet()
                        respond(
                            """{"id":"cs_test_member_aware_$n","url":"https://checkout.stripe.com/c/pay/cs_test_member_aware_$n"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
        )
}

internal const val TEST_BASE_URL = "https://example.org"

/** The public event routes with test-friendly defaults; every collaborator that a test wants to inspect is a parameter. */
internal suspend fun withMemberAwareEventApp(
    mail: RecordingMail,
    stripe: CountingStripe? = null,
    noticeLimiter: EventNoticeMailLimiter = EventNoticeMailLimiter(),
    embedConfig: EmbedConfig = eventTestEmbedConfig(),
    registrationLimiter: FederationInboxRateLimiter = generousEventLimiter(),
    attemptLimiter: FederationInboxRateLimiter = generousEventLimiter(),
    friendConfig: FriendRegistrationConfig = FriendRegistrationConfig.load { null },
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        routing {
            val gateways: Map<PaymentProvider, PspCheckoutGateway> =
                stripe?.let { mapOf(PaymentProvider.STRIPE to it.client) } ?: emptyMap()
            registerEventPublicRoutes(
                checkoutGateways = gateways,
                baseUrl = TEST_BASE_URL,
                mailDispatcher = mail.dispatcher,
                branding = eventTestBranding(),
                navAvailability = eventTestNav(),
                embedConfig = embedConfig,
                pageRateLimiter = generousEventLimiter(),
                attemptRateLimiter = attemptLimiter,
                registrationRateLimiter = registrationLimiter,
                ticketPageRateLimiter = generousEventLimiter(),
                ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                icsFeedRateLimiter = generousEventLimiter(),
                noticeMailLimiter = noticeLimiter,
                friendRegistrationConfig = friendConfig,
            )
        }
    }
    block()
}

internal fun eventFormBody(vararg pairs: Pair<String, String>): String =
    Parameters.build { pairs.forEach { (k, v) -> append(k, v) } }.formUrlEncode()

/** The public GUEST form post. */
internal suspend fun ApplicationTestBuilder.postGuestForm(
    slug: String,
    name: String,
    email: String,
    message: String? = null,
    query: String = "",
): HttpResponse =
    client.post("/veranstaltung/$slug/anmeldung$query") {
        contentType(ContentType.Application.FormUrlEncoded)
        val pairs = mutableListOf("guestName" to name, "guestEmail" to email)
        if (message != null) pairs += "nachricht" to message
        setBody(eventFormBody(*pairs.toTypedArray()))
    }

/** The signed-in button post; by default a faithful same-origin browser request with the session cookie. */
internal suspend fun ApplicationTestBuilder.postAccountButton(
    slug: String,
    sessionToken: String? = null,
    origin: String? = TEST_BASE_URL,
    referer: String? = null,
    secFetchSite: String? = null,
    query: String = "",
): HttpResponse =
    client.post("/veranstaltung/$slug/anmeldung/konto$query") {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody("")
        if (sessionToken != null) header(HttpHeaders.Cookie, "lapis_session=$sessionToken")
        if (origin != null) header(HttpHeaders.Origin, origin)
        if (referer != null) header(HttpHeaders.Referrer, referer)
        if (secFetchSite != null) header("Sec-Fetch-Site", secFetchSite)
    }

internal fun HttpResponse.shouldRedirectTo(location: String) {
    status shouldBe HttpStatusCode(303, "See Other")
    headers[HttpHeaders.Location] shouldBe location
}
