package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.259 -- the detail page as a signed-in visitor sees it: who counts as signed in (session cookie only), what the card shows, the
 * disabled button for an existing registration, the headers, and the EMBED isolation.
 */
class EventPublicSignedInCardTest :
    FunSpec({
        val fixtures = EventPublicMemberFixtures()
        val mail = RecordingMail()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fixtures.cleanup() }

        fun member(
            status: MemberStatus = MemberStatus.ACTIVE,
            name: String = "Erika Beispiel",
            anonymized: Boolean = false,
        ) = fixtures.createMember(
            email = "card-${Uuid.random()}@example.org",
            status = status,
            displayName = name,
            anonymized = anonymized,
        )

        fun cookie(token: String) = "lapis_session=$token"

        test("signed out: the member hint with a login link, no account block") {
            val (_, slug) = fixtures.createEvent()
            withMemberAwareEventApp(mail = mail) {
                val html = client.get("/veranstaltung/$slug").bodyAsText()
                html shouldContain "class=\"event-member-hint\""
                html shouldContain "href=\"$TEST_BASE_URL/app#/login\""
                html shouldContain EventPublicHtml.MEMBER_HINT_TEXT
                html shouldNotContain "Angemeldet als"
                html shouldNotContain "Weitere Gäste anmelden"
                html shouldContain "Verbindlich anmelden"
            }
        }

        for (status in listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST)) {
            test("signed in as $status: name, account button, further-guests heading and text, secondary guest button") {
                val (_, slug) = fixtures.createEvent()
                val token = fixtures.sessionFor(member(status = status, name = "Erika Beispiel"))
                withMemberAwareEventApp(mail = mail) {
                    val html = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
                    html shouldContain "Angemeldet als <strong>Erika Beispiel</strong>"
                    html shouldContain "action=\"/veranstaltung/$slug/anmeldung/konto\""
                    html shouldContain "Zur Veranstaltung anmelden"
                    html shouldContain "<h3>Weitere Gäste anmelden</h3>"
                    html shouldContain EventPublicHtml.ADDITIONAL_GUESTS_TEXT
                    html shouldContain "event-submit event-submit--secondary"
                    html shouldContain ">Gast anmelden<"
                    html shouldNotContain "event-member-hint"
                    html shouldNotContain "disabled"
                }
            }
        }

        test("a status outside the allowed set (applicant, withdrawn) or an anonymised row renders the signed-out page, byte for byte") {
            val (_, slug) = fixtures.createEvent()
            withMemberAwareEventApp(mail = mail) {
                val signedOut = client.get("/veranstaltung/$slug").bodyAsText()
                val candidates =
                    listOf(
                        fixtures.sessionFor(member(status = MemberStatus.APPLICATION)),
                        fixtures.sessionFor(member(status = MemberStatus.WITHDRAWN)),
                        fixtures.sessionFor(member(anonymized = true)),
                    )
                for (token in candidates) {
                    client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText() shouldBe signedOut
                }
            }
        }

        test("an unknown, a revoked and an expired session cookie render the signed-out page, byte for byte") {
            val (_, slug) = fixtures.createEvent()
            val revoked = fixtures.sessionFor(member())
            val expired = fixtures.sessionFor(member())
            SessionStore.revoke(revoked)
            transaction {
                // expire the session by rewriting its expiry through the token hash
                val hash = SessionTokens.hash(expired)
                SessionTable.update({ SessionTable.tokenHash eq hash }) { it[expiresAt] = LocalDateTime(2020, 1, 1, 0, 0) }
            }
            withMemberAwareEventApp(mail = mail) {
                val signedOut = client.get("/veranstaltung/$slug").bodyAsText()
                for (token in listOf("zufaellig-${Uuid.random()}", revoked, expired)) {
                    client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText() shouldBe signedOut
                }
            }
        }

        test("only the test-mode X-Member-Id header, or only a Bearer token, never signs the visitor in on this surface") {
            val (_, slug) = fixtures.createEvent()
            val memberId = member()
            val token = fixtures.sessionFor(memberId)
            withMemberAwareEventApp(mail = mail) {
                val signedOut = client.get("/veranstaltung/$slug").bodyAsText()
                client.get("/veranstaltung/$slug") { header("X-Member-Id", memberId.toString()) }.bodyAsText() shouldBe signedOut
                client.get("/veranstaltung/$slug") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText() shouldBe signedOut
            }
        }

        test("EMBED with a valid cookie: byte-identical to the cookie-less embed page, and the session is never touched") {
            val (_, slug) = fixtures.createEvent()
            val memberId = member()
            val token = fixtures.sessionFor(memberId)

            fun lastUsed(): LocalDateTime? =
                transaction { SessionTable.selectAll().where { SessionTable.memberId eq memberId }.single()[SessionTable.lastUsedAt] }
            lastUsed() shouldBe null
            withMemberAwareEventApp(mail = mail, embedConfig = EmbedConfig.DISABLED) {
                val plain = client.get("/veranstaltung/$slug?embed=1").bodyAsText()
                val withCookie = client.get("/veranstaltung/$slug?embed=1") { header(HttpHeaders.Cookie, cookie(token)) }
                withCookie.bodyAsText() shouldBe plain
                withCookie.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                withCookie.headers[HttpHeaders.Vary] shouldBe null
                withCookie.headers["Referrer-Policy"] shouldBe "no-referrer"
                plain shouldNotContain "Angemeldet als"
                plain shouldNotContain "event-member-hint"
            }
            lastUsed() shouldBe null
        }

        test("headers: signed in is private + Vary + same-origin; signed out is no-store + Vary + no-referrer; never a Set-Cookie") {
            val (_, slug) = fixtures.createEvent()
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                val signedIn = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }
                signedIn.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                signedIn.headers[HttpHeaders.Vary] shouldBe "Cookie"
                signedIn.headers["Referrer-Policy"] shouldBe "same-origin"
                signedIn.headers[HttpHeaders.SetCookie] shouldBe null
                signedIn.headers["Content-Security-Policy"]!! shouldContain "form-action 'self'"
                val signedOut = client.get("/veranstaltung/$slug")
                signedOut.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                signedOut.headers[HttpHeaders.Vary] shouldBe "Cookie"
                signedOut.headers["Referrer-Policy"] shouldBe "no-referrer"
                signedOut.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        for (status in listOf(
            EventRegistrationStatus.CONFIRMED,
            EventRegistrationStatus.WAITLISTED,
            EventRegistrationStatus.PENDING_PAYMENT,
        )) {
            test("already registered ($status): disabled button with aria, a check mark, the reason and the right link") {
                val (eventId, slug) = fixtures.createEvent()
                val memberId = member()
                fixtures.insertRegistration(
                    eventId = eventId,
                    memberId = memberId,
                    status = status,
                    holdExpiresAt = if (status == EventRegistrationStatus.PENDING_PAYMENT) LocalDateTime(2035, 1, 1, 0, 0) else null,
                    fee = if (status == EventRegistrationStatus.PENDING_PAYMENT) BigDecimal("5.00") else BigDecimal.ZERO,
                    waitlistPosition = if (status == EventRegistrationStatus.WAITLISTED) 1 else null,
                )
                val token = fixtures.sessionFor(memberId)
                withMemberAwareEventApp(mail = mail) {
                    val html = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
                    html shouldContain "disabled=\"disabled\""
                    html shouldContain "aria-disabled=\"true\""
                    html shouldContain "aria-describedby=\"anmeldung-status\""
                    html shouldContain "id=\"anmeldung-status\""
                    html shouldContain "<span aria-hidden=\"true\">✓ </span>"
                    html shouldContain "href=\"$TEST_BASE_URL/app#/events/$eventId\""
                    val note =
                        when (status) {
                            EventRegistrationStatus.CONFIRMED -> "Sie sind für diese Veranstaltung angemeldet."
                            EventRegistrationStatus.WAITLISTED -> "Sie stehen auf der Warteliste."
                            else -> "Ihre Anmeldung ist noch nicht bezahlt."
                        }
                    html shouldContain note
                    html shouldContain
                        (if (status == EventRegistrationStatus.PENDING_PAYMENT) ">Jetzt bezahlen<" else ">Im Mitgliederbereich verwalten<")
                }
            }
        }

        test("a cancelled, an expired and a lapsed PENDING_PAYMENT registration leave the button active") {
            val (eventId, slug) = fixtures.createEvent()
            for (case in listOf("cancelled", "expired", "lapsed")) {
                val memberId = member()
                val registered =
                    when (case) {
                        "cancelled" -> EventRegistrationStatus.CANCELLED
                        "expired" -> EventRegistrationStatus.EXPIRED
                        else -> EventRegistrationStatus.PENDING_PAYMENT
                    }
                fixtures.insertRegistration(
                    eventId = eventId,
                    memberId = memberId,
                    status = registered,
                    holdExpiresAt = if (case == "lapsed") LocalDateTime(2020, 1, 1, 0, 0) else null,
                    fee = if (case == "lapsed") BigDecimal("5.00") else BigDecimal.ZERO,
                )
                val token = fixtures.sessionFor(memberId)
                withMemberAwareEventApp(mail = mail) {
                    val html = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
                    withClue(case) { html shouldNotContain "disabled" }
                    html shouldContain "Zur Veranstaltung anmelden"
                }
            }
        }

        test("a hostile display name is escaped") {
            val (_, slug) = fixtures.createEvent()
            val token = fixtures.sessionFor(member(name = "<script>alert(1)</script>\"&"))
            withMemberAwareEventApp(mail = mail) {
                val html = client.get("/veranstaltung/$slug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
                html shouldNotContain "<script>alert(1)</script>"
                html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
            }
        }

        test("a full event offers the waitlist wording to a signed-in visitor, a paid one announces the payment") {
            val (fullEvent, fullSlug) = fixtures.createEvent(capacity = 1)
            fixtures.insertRegistration(eventId = fullEvent, guestEmail = "other-${Uuid.random()}@example.org")
            val (_, paidSlug) = fixtures.createEvent(fee = BigDecimal("12.00"))
            val token = fixtures.sessionFor(member())
            withMemberAwareEventApp(mail = mail) {
                val full = client.get("/veranstaltung/$fullSlug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
                full shouldContain ">Auf die Warteliste setzen<"
                full shouldContain ">Gast auf die Warteliste setzen<"
                val paid = client.get("/veranstaltung/$paidSlug") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
                paid shouldContain "Danach geht es zur Zahlung."
            }
        }
    })
