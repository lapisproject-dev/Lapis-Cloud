package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.events.EventNoticeMailLimiter
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.rpc.FriendRegistrationConfig
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.260, the embed twin of [EventPublicMemberAddressOracleTest]: `POST /api/embed/v1/event/{slug}/registration` must not tell
 * whether an address belongs to an account. Every accepted case is the byte-identical `200 {"outcome":"ACCEPTED"}`; the
 * address-independent reasons come first; only the mails (to the address owner) differ.
 */
class EmbedEventMemberAddressOracleTest :
    FunSpec({
        val fixtures = EventPublicMemberFixtures()
        val mail = RecordingMail()
        val origin = "https://partei.example"
        val accepted = """{"outcome":"ACCEPTED"}"""

        beforeSpec { DatabaseConfig.connect() }
        afterTest { fixtures.disableGateway() }
        afterSpec { fixtures.cleanup() }

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = origin, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun withEmbedApp(
            stripe: CountingStripe? = null,
            noticeLimiter: EventNoticeMailLimiter = EventNoticeMailLimiter(),
            registrationLimiter: FederationInboxRateLimiter = generousEventLimiter(),
            withFormRoutes: Boolean = false,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    val gateways = stripe?.let { mapOf(PaymentProvider.STRIPE to it.client) } ?: emptyMap()
                    val friendConfig = FriendRegistrationConfig.load { null }
                    if (withFormRoutes) {
                        registerEventPublicRoutes(
                            checkoutGateways = gateways,
                            baseUrl = TEST_BASE_URL,
                            mailDispatcher = mail.dispatcher,
                            branding = eventTestBranding(),
                            navAvailability = eventTestNav(),
                            embedConfig = eventTestEmbedConfig(),
                            pageRateLimiter = generousEventLimiter(),
                            attemptRateLimiter = generousEventLimiter(),
                            registrationRateLimiter = registrationLimiter,
                            ticketPageRateLimiter = generousEventLimiter(),
                            ticketCodeFailureLimiter =
                                network.lapis.cloud.server.security
                                    .LoginRateLimiter(maxFailures = 10_000),
                            icsFeedRateLimiter = generousEventLimiter(),
                            noticeMailLimiter = noticeLimiter,
                            friendRegistrationConfig = friendConfig,
                        )
                    }
                    registerEmbedEventRoutes(
                        config = enabledConfig,
                        checkoutGateways = gateways,
                        mailDispatcher = mail.dispatcher,
                        baseUrl = TEST_BASE_URL,
                        attemptRateLimiter = generousEventLimiter(),
                        registrationRateLimiter = registrationLimiter,
                        pageRateLimiter = generousEventLimiter(),
                        noticeMailLimiter = noticeLimiter,
                        friendRegistrationConfig = friendConfig,
                    )
                }
            }
            block()
        }

        suspend fun ApplicationTestBuilder.postEmbed(
            slug: String,
            body: String,
        ): HttpResponse =
            client.post("/api/embed/v1/event/$slug/registration") {
                header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        suspend fun ApplicationTestBuilder.postEmbed(
            slug: String,
            name: String,
            email: String,
        ): HttpResponse = postEmbed(slug, """{"guestName":"$name","guestEmail":"$email"}""")

        /** What a caller can observe of one POST; `Date` is the only header allowed to differ. */
        data class Observed(
            val status: HttpStatusCode,
            val headers: Map<String, List<String>>,
            val body: List<Byte>,
        )

        suspend fun HttpResponse.observe(): Observed =
            Observed(
                status = status,
                headers = headers.entries().filter { it.key != HttpHeaders.Date }.associate { it.key to it.value },
                body = bodyAsBytes().toList(),
            )

        data class Situation(
            val label: String,
            val email: String,
        )

        fun situations(eventId: Uuid): List<Situation> {
            val tag = Uuid.random().toString().take(8)
            val upper = "Gross-$tag@Example.ORG"
            fixtures.createMember(email = upper, status = MemberStatus.ACTIVE)
            fixtures.createMember(email = "friend-$tag@example.org", status = MemberStatus.FRIEND)
            fixtures.createMember(email = "guest-$tag@example.org", status = MemberStatus.GUEST)
            fixtures.createMember(email = "anon-$tag@example.org", status = MemberStatus.ACTIVE, anonymized = true)
            fixtures.createMember(email = "withdrawn-$tag@example.org", status = MemberStatus.WITHDRAWN)
            fixtures.createMember(email = "noaccount-$tag@example.org", status = MemberStatus.ACTIVE, withAccount = false)
            fixtures.insertRegistration(eventId = eventId, guestEmail = "dup-$tag@example.org")
            return listOf(
                Situation("fresh", "fresh-$tag@example.org"),
                Situation("ACTIVE member, other case", upper.lowercase()),
                Situation("FRIEND", "friend-$tag@example.org"),
                Situation("GUEST", "guest-$tag@example.org"),
                Situation("duplicate guest", "dup-$tag@example.org"),
                Situation("anonymised", "anon-$tag@example.org"),
                Situation("withdrawn", "withdrawn-$tag@example.org"),
                Situation("row without account", "noaccount-$tag@example.org"),
            )
        }

        data class Variant(
            val name: String,
            val paid: Boolean = false,
            val setup: () -> Pair<Uuid, String>,
        )

        val acceptedVariants =
            listOf(
                Variant("free", setup = { fixtures.createEvent() }),
                Variant("full (waitlist open)", setup = {
                    val e = fixtures.createEvent(capacity = 1)
                    fixtures.insertRegistration(eventId = e.first, guestEmail = "holder-${Uuid.random()}@example.org")
                    e
                }),
                Variant("paid (gateway on)", paid = true, setup = {
                    fixtures.enableGateway()
                    fixtures.createEvent(fee = BigDecimal("15.00"))
                }),
            )

        for (variant in acceptedVariants) {
            test("oracle matrix [${variant.name}]: every address gets byte-identical status, headers and body") {
                val (eventId, slug) = variant.setup()
                val stripe = if (variant.paid) CountingStripe() else null
                val all = situations(eventId)
                withEmbedApp(stripe = stripe) {
                    val observed = mutableListOf<Pair<Situation, Observed>>()
                    for (situation in all) {
                        mail.settle()
                        observed += situation to postEmbed(slug, "Besucher", situation.email).observe()
                        val mails = mail.settle().filter { it.to.equals(situation.email, ignoreCase = true) }
                        withClue("${variant.name} / ${situation.label}: mails") { mails.size shouldBe 1 }
                    }
                    val reference = observed.first().second
                    for ((situation, seen) in observed) {
                        withClue("${variant.name} / ${situation.label}") { seen shouldBe reference }
                    }
                    reference.status shouldBe HttpStatusCode.OK
                    reference.body shouldBe accepted.toByteArray().toList()
                    if (stripe != null) stripe.calls.get() shouldBe 0
                }
            }
        }

        test(
            "address-independent reasons come BEFORE the address lookup: closed / waitlist full / no gateway are identical and send no mail",
        ) {
            val closed =
                fixtures.createEvent(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 20, 0))
            val waitlistFull =
                fixtures.createEvent(capacity = 1).also { e ->
                    fixtures.insertRegistration(eventId = e.first, guestEmail = "holder-${Uuid.random()}@example.org")
                    for (position in 1..500) {
                        fixtures.insertRegistration(
                            eventId = e.first,
                            guestEmail = "wait-$position-${Uuid.random()}@example.org",
                            status = EventRegistrationStatus.WAITLISTED,
                            waitlistPosition = position,
                        )
                    }
                }
            fixtures.disableGateway()
            val noGateway = fixtures.createEvent(fee = BigDecimal("15.00"))
            val account = "reason-${Uuid.random()}@example.org"
            fixtures.createMember(email = account)
            val stranger = "stranger-${Uuid.random()}@example.org"
            val expected =
                listOf(
                    closed to (HttpStatusCode.NotFound to """{"outcome":"NOT_AVAILABLE"}"""),
                    waitlistFull to (HttpStatusCode.Conflict to """{"outcome":"WAITLIST_FULL"}"""),
                    noGateway to (HttpStatusCode.ServiceUnavailable to """{"outcome":"UNAVAILABLE"}"""),
                )
            withEmbedApp {
                mail.settle()
                for ((event, want) in expected) {
                    val withAccount = postEmbed(event.second, "X", account).observe()
                    val withStranger = postEmbed(event.second, "X", stranger).observe()
                    withAccount shouldBe withStranger
                    withAccount.status shouldBe want.first
                    String(withAccount.body.toByteArray()) shouldBe want.second
                }
                mail.settle().size shouldBe 0
            }
        }

        test("account address: no registration row, exactly one notice mail to the stored address; a second attempt sends nothing") {
            val (eventId, slug) = fixtures.createEvent()
            val tag = Uuid.random().toString().take(8)
            val stored = "Mitglied.$tag@Example.ORG"
            fixtures.createMember(email = stored)
            withEmbedApp {
                mail.settle()
                val first = postEmbed(slug, "Xq7Marker", stored.uppercase()).observe()
                fixtures.registrationCount(eventId) shouldBe 0
                val mails = mail.settle()
                mails.size shouldBe 1
                val notice = mails.single()
                notice.to shouldBe stored
                notice.subject shouldBe "Hinweis zur Veranstaltung \"Mitglieder-Test-Veranstaltung\""
                notice.plainText shouldNotContain "Xq7Marker"
                postEmbed(slug, "Xq7Marker", stored.uppercase()).observe() shouldBe first
                mail.settle().size shouldBe 0
            }
        }

        test("global cap: further notice mails are dropped, the answer stays the same") {
            val (_, slug) = fixtures.createEvent()
            val emails = List(3) { "cap-$it-${Uuid.random()}@example.org" }
            emails.forEach { fixtures.createMember(email = it) }
            val limiter =
                EventNoticeMailLimiter(
                    global = FederationInboxRateLimiter(maxRequests = 2, window = 1.hours, maxTrackedKeys = 1),
                )
            withEmbedApp(noticeLimiter = limiter) {
                mail.settle()
                val answers = emails.map { postEmbed(slug, "X", it).observe() }
                answers.distinct().size shouldBe 1
                mail.settle().size shouldBe 2
            }
        }

        test("shared limiter: form POST then embed POST for the same account address and event send exactly one mail, and vice versa") {
            val (_, slug) = fixtures.createEvent()
            val a = "shared-a-${Uuid.random()}@example.org"
            val b = "shared-b-${Uuid.random()}@example.org"
            fixtures.createMember(email = a)
            fixtures.createMember(email = b)
            withEmbedApp(withFormRoutes = true) {
                mail.settle()
                postGuestForm(slug = slug, name = "X", email = a)
                postEmbed(slug, "X", a)
                mail.settle().count { it.to == a } shouldBe 1
                postEmbed(slug, "X", b)
                postGuestForm(slug = slug, name = "X", email = b)
                mail.settle().count { it.to == b } shouldBe 1
            }
        }

        test("paid event, fresh address: PENDING_PAYMENT with a 30 minute hold, pay-link mail, no provider call, neutral answer") {
            fixtures.enableGateway()
            val stripe = CountingStripe()
            val (eventId, slug) = fixtures.createEvent(fee = BigDecimal("15.00"))
            val email = "payer-${Uuid.random()}@example.org"
            withEmbedApp(stripe = stripe) {
                mail.settle()
                val before = DbClock.nowLocalDateTime()
                val response = postEmbed(slug, "Zahler", email)
                response.status shouldBe HttpStatusCode.OK
                String(response.bodyAsBytes()) shouldBe accepted
                stripe.calls.get() shouldBe 0
                val row = fixtures.registrations(eventId).single()
                row[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
                val hold = row[EventRegistrationTable.holdExpiresAt]!!
                val target = before.plusDuration(EventPolicy.STANDARD_HOLD)
                (hold >= target.plusDuration((-1).minutes)) shouldBe true
                (hold <= DbClock.nowLocalDateTime().plusDuration(EventPolicy.STANDARD_HOLD + 1.minutes)) shouldBe true
                val message = mail.settle().single { it.to == email }
                message.plainText shouldContain "Jetzt bezahlen"
                message.plainText shouldContain "/veranstaltung/$slug/zahlung?token="
            }
        }

        test("waitlist: WAITLISTED row, waitlist mail, neutral answer") {
            val (eventId, slug) = fixtures.createEvent(capacity = 1)
            fixtures.insertRegistration(eventId = eventId, guestEmail = "holder-${Uuid.random()}@example.org")
            val email = "wait-${Uuid.random()}@example.org"
            withEmbedApp {
                mail.settle()
                String(postEmbed(slug, "W", email).bodyAsBytes()) shouldBe accepted
                fixtures
                    .registrations(eventId)
                    .single { it[EventRegistrationTable.guestEmail] == email }[EventRegistrationTable.status] shouldBe
                    EventRegistrationStatus.WAITLISTED
                mail.settle().single { it.to == email }.subject shouldBe "Warteliste: Mitglieder-Test-Veranstaltung"
            }
        }

        test("duplicate: one duplicate notice, no new token, row unchanged, second attempt sends nothing") {
            val (eventId, slug) = fixtures.createEvent()
            val email = "twice-${Uuid.random()}@example.org"
            withEmbedApp {
                mail.settle()
                postEmbed(slug, "D", email)
                mail.settle().single().subject shouldBe "Anmeldebestätigung: Mitglieder-Test-Veranstaltung"
                val before = fixtures.registrations(eventId).single()
                String(postEmbed(slug, "D", email).bodyAsBytes()) shouldBe accepted
                val notice = mail.settle().single()
                notice.to shouldBe email
                notice.plainText shouldNotContain "token="
                val after = fixtures.registrations(eventId).single()
                after[EventRegistrationTable.cancelTokenSha256] shouldBe before[EventRegistrationTable.cancelTokenSha256]
                after[EventRegistrationTable.status] shouldBe before[EventRegistrationTable.status]
                after[EventRegistrationTable.registeredAt] shouldBe before[EventRegistrationTable.registeredAt]
                postEmbed(slug, "D", email)
                mail.settle().size shouldBe 0
            }
        }

        test("anonymised and inactive accounts behave like a stranger: a guest row is created") {
            val (eventId, slug) = fixtures.createEvent()
            val anon = "anon2-${Uuid.random()}@example.org"
            val gone = "gone2-${Uuid.random()}@example.org"
            fixtures.createMember(email = anon, anonymized = true)
            fixtures.createMember(email = gone, status = MemberStatus.WITHDRAWN)
            withEmbedApp {
                mail.settle()
                postEmbed(slug, "A", anon)
                postEmbed(slug, "G", gone)
                fixtures.registrationCount(eventId) shouldBe 2
                mail.settle()
            }
        }

        test("hostile input: CRLF/markup in the name, uppercase address, oversized fields, extra JSON fields") {
            val (eventId, slug) = fixtures.createEvent()
            val account = "hostile-${Uuid.random()}@example.org"
            fixtures.createMember(email = account)
            withEmbedApp {
                mail.settle()
                val crlf = postEmbed(slug, """{"guestName":"A\r\nBcc: x@evil.example <script>","guestEmail":"${account.uppercase()}"}""")
                crlf.status shouldBe HttpStatusCode.OK
                String(crlf.bodyAsBytes()) shouldBe accepted
                val notice = mail.settle().single()
                notice.to shouldBe account
                notice.plainText shouldNotContain "evil.example"
                notice.plainText shouldNotContain "<script>"

                val long = postEmbed(slug, """{"guestName":"${"x".repeat(301)}","guestEmail":"a@example.org"}""")
                long.status shouldBe HttpStatusCode.BadRequest

                val extra =
                    postEmbed(
                        slug,
                        """{"guestName":"E","guestEmail":"extra-${Uuid.random()}@example.org","redirectUrl":"https://evil.example","registrationId":"x"}""",
                    )
                extra.status shouldBe HttpStatusCode.OK
                String(extra.bodyAsBytes()) shouldBe accepted
                fixtures.registrationCount(eventId) shouldBe 1
                mail.settle()
            }
        }

        test("the widget's accepted sentence is character-identical to the form's thank-you page") {
            val js = checkNotNull(object {}.javaClass.getResource("/embed/lapis-widgets.js")).readText()
            val sentence = Regex("""accepted: "([^"]+)"""").find(js)!!.groupValues[1]
            val (_, slug) = fixtures.createEvent()
            withEmbedApp(withFormRoutes = true) {
                val page = client.get("/veranstaltung/$slug/danke").bodyAsText()
                page shouldContain sentence
            }
        }

        test("honeypot with an account address: ACCEPTED, no mail, no row") {
            val (eventId, slug) = fixtures.createEvent()
            val account = "pot-${Uuid.random()}@example.org"
            fixtures.createMember(email = account)
            withEmbedApp {
                mail.settle()
                val response = postEmbed(slug, """{"guestName":"Bot","guestEmail":"$account","kommentar":"x"}""")
                response.status shouldBe HttpStatusCode.OK
                String(response.bodyAsBytes()) shouldBe accepted
                mail.settle().size shouldBe 0
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }
    })
