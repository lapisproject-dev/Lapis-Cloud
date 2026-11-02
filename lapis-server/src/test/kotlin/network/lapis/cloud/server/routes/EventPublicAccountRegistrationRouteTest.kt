package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.259 -- `POST /veranstaltung/{slug}/anmeldung/konto`, the signed-in visitor's own registration. */
class EventPublicAccountRegistrationRouteTest :
    FunSpec({
        val fixtures = EventPublicMemberFixtures()
        val mail = RecordingMail()

        beforeSpec { DatabaseConfig.connect() }
        afterTest { fixtures.disableGateway() }
        afterSpec { fixtures.cleanup() }

        fun member(status: MemberStatus = MemberStatus.ACTIVE) =
            fixtures.createMember(email = "konto-${Uuid.random()}@example.org", status = status, displayName = "Konto Mitglied")

        test("a free event: the member is CONFIRMED, redirected back to #anmeldung, and the page then shows the disabled button") {
            val (eventId, slug) = fixtures.createEvent()
            val memberId = member()
            val token = fixtures.sessionFor(memberId)
            withMemberAwareEventApp(mail = mail) {
                val response = postAccountButton(slug = slug, sessionToken = token)
                response.shouldRedirectTo("/veranstaltung/$slug#anmeldung")
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers[HttpHeaders.SetCookie] shouldBe null
                val rows = fixtures.registrations(eventId)
                rows.size shouldBe 1
                rows.single()[EventRegistrationTable.memberId] shouldBe memberId
                rows.single()[EventRegistrationTable.status] shouldBe EventRegistrationStatus.CONFIRMED
                val page = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, "lapis_session=$token") }.bodyAsText()
                page shouldContain "aria-disabled=\"true\""
                page shouldContain "Sie sind für diese Veranstaltung angemeldet."
            }
            mail.settle().size shouldBe 1 // the confirmation mail of the registration, to the account's own address
        }

        test("a full event: the member lands on the waitlist") {
            val (eventId, slug) = fixtures.createEvent(capacity = 1)
            fixtures.insertRegistration(eventId = eventId, guestEmail = "voll-${Uuid.random()}@example.org")
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                postAccountButton(slug = slug, sessionToken = token).shouldRedirectTo("/veranstaltung/$slug#anmeldung")
                fixtures
                    .registrations(eventId)
                    .single { it[EventRegistrationTable.memberId] != null }[EventRegistrationTable.status] shouldBe
                    EventRegistrationStatus.WAITLISTED
            }
            mail.settle()
        }

        test("a paid event: PENDING_PAYMENT and a 200 hand-off page with the checkout link, never a 303 into the payment provider") {
            fixtures.enableGateway()
            val stripe = CountingStripe()
            val (eventId, slug) = fixtures.createEvent(fee = BigDecimal("25.00"))
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail, stripe = stripe) {
                val response = postAccountButton(slug = slug, sessionToken = token)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.Location] shouldBe null
                val body = response.bodyAsText()
                body shouldContain "href=\"https://checkout.stripe.com/c/pay/cs_test_member_aware_1\""
                body shouldContain "target=\"_top\""
                body shouldContain "Ihr Platz ist reserviert. Bitte schließen Sie die Zahlung ab."
                fixtures.registrations(eventId).single()[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
                stripe.calls.get() shouldBe 1
            }
            mail.settle()
        }

        test("a second POST creates no second row and redirects the same way (post/redirect/get)") {
            val (eventId, slug) = fixtures.createEvent()
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                postAccountButton(slug = slug, sessionToken = token).shouldRedirectTo("/veranstaltung/$slug#anmeldung")
                postAccountButton(slug = slug, sessionToken = token).shouldRedirectTo("/veranstaltung/$slug#anmeldung")
                fixtures.registrationCount(eventId) shouldBe 1
            }
            mail.settle()
        }

        test(
            "CSRF: a foreign, null or missing Origin, a foreign or missing Referer and a cross-site fetch are refused with 403 and no row",
        ) {
            val (eventId, slug) = fixtures.createEvent()
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                val attempts =
                    mapOf(
                        "foreign origin" to postAccountButton(slug = slug, sessionToken = token, origin = "https://evil.example"),
                        "origin null" to postAccountButton(slug = slug, sessionToken = token, origin = "null"),
                        "no origin, foreign referer" to
                            postAccountButton(slug = slug, sessionToken = token, origin = null, referer = "https://evil.example/x"),
                        "neither header" to postAccountButton(slug = slug, sessionToken = token, origin = null),
                        "cross-site fetch metadata" to postAccountButton(slug = slug, sessionToken = token, secFetchSite = "cross-site"),
                        "same-site fetch metadata" to postAccountButton(slug = slug, sessionToken = token, secFetchSite = "same-site"),
                        "foreign port" to postAccountButton(slug = slug, sessionToken = token, origin = "https://example.org:8443"),
                        "http instead of https" to postAccountButton(slug = slug, sessionToken = token, origin = "http://example.org"),
                    )
                for ((name, response) in attempts) {
                    withClue(name) {
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    }
                }
                fixtures.registrationCount(eventId) shouldBe 0
                // the benign counterpart: a same-origin Referer without Origin is accepted
                postAccountButton(slug = slug, sessionToken = token, origin = null, referer = "$TEST_BASE_URL/veranstaltung/$slug")
                    .shouldRedirectTo("/veranstaltung/$slug#anmeldung")
                fixtures.registrationCount(eventId) shouldBe 1
            }
            mail.settle()
        }

        test("no session, an expired session, a status outside the set: 303 to the event page and no row") {
            val (eventId, slug) = fixtures.createEvent()
            val expired = fixtures.sessionFor(member())
            transaction {
                val hash = SessionTokens.hash(expired)
                SessionTable.update({ SessionTable.tokenHash eq hash }) { it[expiresAt] = LocalDateTime(2020, 1, 1, 0, 0) }
            }
            val applicant = fixtures.sessionFor(member(MemberStatus.APPLICATION))
            withMemberAwareEventApp(mail = mail) {
                for (token in listOf(null, expired, applicant, "zufaellig-${Uuid.random()}")) {
                    postAccountButton(slug = slug, sessionToken = token).shouldRedirectTo("/veranstaltung/$slug")
                }
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }

        test("only a Bearer token or only the test-mode X-Member-Id header does not authenticate this button") {
            val (eventId, slug) = fixtures.createEvent()
            val memberId = member()
            val token = fixtures.sessionFor(memberId)
            withMemberAwareEventApp(mail = mail) {
                val bearer =
                    client.post("/veranstaltung/$slug/anmeldung/konto") {
                        header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                        header(HttpHeaders.Origin, TEST_BASE_URL)
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                bearer.shouldRedirectTo("/veranstaltung/$slug")
                val trusted =
                    client.post("/veranstaltung/$slug/anmeldung/konto") {
                        header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                        header(HttpHeaders.Origin, TEST_BASE_URL)
                        header("X-Member-Id", memberId.toString())
                    }
                trusted.shouldRedirectTo("/veranstaltung/$slug")
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }

        test("GET on the button route does nothing") {
            val (eventId, slug) = fixtures.createEvent()
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                val response = client.get("/veranstaltung/$slug/anmeldung/konto") { header(HttpHeaders.Cookie, "lapis_session=$token") }
                (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed) shouldBe true
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }

        test("the button route does not exist in EMBED mode, even with a valid cookie") {
            val (eventId, slug) = fixtures.createEvent()
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                postAccountButton(slug = slug, sessionToken = token, query = "?embed=1").status shouldBe HttpStatusCode.NotFound
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }

        test("an unknown or non-public event is a 404 with no row") {
            val token = fixtures.sessionFor(member())
            val (_, internal) = fixtures.createEvent(visibility = EventVisibility.MEMBERS_ONLY)
            withMemberAwareEventApp(mail = mail) {
                postAccountButton(slug = "gibt-es-nicht", sessionToken = token).status shouldBe HttpStatusCode.NotFound
                postAccountButton(slug = internal, sessionToken = token).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("the rate limits apply: the strict budget answers 429 once spent, and no row is created for the refused attempt") {
            val (eventId, slug) = fixtures.createEvent()
            val first = fixtures.sessionFor(member())
            val second = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail, registrationLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 60.minutes)) {
                postAccountButton(slug = slug, sessionToken = first).shouldRedirectTo("/veranstaltung/$slug#anmeldung")
                postAccountButton(slug = slug, sessionToken = second).status shouldBe HttpStatusCode.TooManyRequests
                fixtures.registrationCount(eventId) shouldBe 1
            }
            val (otherEvent, otherSlug) = fixtures.createEvent()
            withMemberAwareEventApp(mail = mail, attemptLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 60.minutes)) {
                postAccountButton(slug = otherSlug, sessionToken = first)
                postAccountButton(slug = otherSlug, sessionToken = second).status shouldBe HttpStatusCode.TooManyRequests
                fixtures.registrationCount(otherEvent) shouldBe 1
            }
            mail.settle()
        }

        test("the page of a closed event offers no button, and a forged POST creates nothing") {
            val (eventId, slug) =
                fixtures.createEvent(
                    startsAt = LocalDateTime(2020, 1, 1, 18, 0),
                    endsAt = LocalDateTime(2020, 1, 1, 22, 0),
                )
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                val page = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, "lapis_session=$token") }.bodyAsText()
                page shouldNotContain "anmeldung/konto"
                // a forged POST: the event is over, so it is not available -- redirected back, nothing stored
                postAccountButton(slug = slug, sessionToken = token).shouldRedirectTo("/veranstaltung/$slug")
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }
    })
