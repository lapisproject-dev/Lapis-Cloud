package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.events.EventNoticeMailLimiter
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.259, the core of the wave: the public GUEST form must not tell whether an address belongs to an account. For every event
 * situation, an account address, a duplicate and a stranger get the SAME status, `Location`, headers and body; only the mails differ, and
 * those go to the address owner, never to the visitor.
 */
class EventPublicMemberAddressOracleTest :
    FunSpec({
        val fixtures = EventPublicMemberFixtures()
        val mail = RecordingMail()

        beforeSpec { DatabaseConfig.connect() }
        afterTest { fixtures.disableGateway() }
        afterSpec { fixtures.cleanup() }

        /** What a visitor can observe of one POST. `Date` is the only header allowed to differ. */
        data class Observed(
            val status: HttpStatusCode,
            val headers: Map<String, List<String>>,
            val body: String,
        )

        suspend fun HttpResponse.observe(): Observed =
            Observed(
                status = status,
                headers = headers.entries().filter { it.key != HttpHeaders.Date }.associate { it.key to it.value },
                body = bodyAsText(),
            )

        /** The address situations of the matrix; each gets a fresh account/registration per event so the rows are independent. */
        data class Situation(
            val label: String,
            val email: String,
            val isAccountAddress: Boolean,
            val isDuplicate: Boolean = false,
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
                Situation("fresh", "fresh-$tag@example.org", isAccountAddress = false),
                Situation("ACTIVE member, other case", upper.lowercase(), isAccountAddress = true),
                Situation("FRIEND", "friend-$tag@example.org", isAccountAddress = true),
                Situation("GUEST", "guest-$tag@example.org", isAccountAddress = true),
                Situation("duplicate guest", "dup-$tag@example.org", isAccountAddress = false, isDuplicate = true),
                Situation("anonymised", "anon-$tag@example.org", isAccountAddress = false),
                Situation("withdrawn", "withdrawn-$tag@example.org", isAccountAddress = false),
                Situation("row without account", "noaccount-$tag@example.org", isAccountAddress = false),
            )
        }

        data class Variant(
            val name: String,
            val expectedLocation: (String) -> String?,
            val setup: () -> Pair<Uuid, String>,
            val noMail: Boolean = false,
        )

        val variants =
            listOf(
                Variant("free", { "/veranstaltung/$it/danke" }, { fixtures.createEvent() }),
                Variant(
                    "full (waitlist open)",
                    { "/veranstaltung/$it/danke" },
                    {
                        val e = fixtures.createEvent(capacity = 1)
                        fixtures.insertRegistration(eventId = e.first, guestEmail = "holder-${Uuid.random()}@example.org")
                        e
                    },
                ),
                Variant(
                    "paid (gateway on)",
                    { "/veranstaltung/$it/danke" },
                    {
                        fixtures.enableGateway()
                        fixtures.createEvent(fee = BigDecimal("15.00"))
                    },
                ),
                Variant("closed (event over)", { null }, {
                    fixtures.createEvent(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 20, 0))
                }, noMail = true),
                Variant(
                    "waitlist full",
                    { "/veranstaltung/$it/abgebrochen" },
                    {
                        val e = fixtures.createEvent(capacity = 1)
                        fixtures.insertRegistration(eventId = e.first, guestEmail = "holder-${Uuid.random()}@example.org")
                        for (position in 1..500) {
                            fixtures.insertRegistration(
                                eventId = e.first,
                                guestEmail = "wait-$position-${Uuid.random()}@example.org",
                                status = EventRegistrationStatus.WAITLISTED,
                                waitlistPosition = position,
                            )
                        }
                        e
                    },
                    noMail = true,
                ),
                Variant("paid, gateway off", { "/veranstaltung/$it/abgebrochen" }, {
                    fixtures.disableGateway()
                    fixtures.createEvent(fee = BigDecimal("15.00"))
                }, noMail = true),
            )

        for (variant in variants) {
            test("oracle matrix [${variant.name}]: every address gets the same status, Location, headers and body") {
                val (eventId, slug) = variant.setup()
                val stripe = if (variant.name.startsWith("paid")) CountingStripe() else null
                val all = situations(eventId)
                withMemberAwareEventApp(mail = mail, stripe = stripe) {
                    val observed = mutableListOf<Pair<Situation, Observed>>()
                    for (situation in all) {
                        mail.settle()
                        val response = postGuestForm(slug = slug, name = "Besucher", email = situation.email)
                        observed += situation to response.observe()
                        val mails = mail.settle().filter { it.to.equals(situation.email, ignoreCase = true) }
                        withClue("${variant.name} / ${situation.label}: mails") {
                            if (variant.noMail) {
                                mails.size shouldBe 0
                            } else {
                                mails.size shouldBe 1
                            }
                        }
                    }
                    val reference = observed.first().second
                    for ((situation, seen) in observed) {
                        withClue("${variant.name} / ${situation.label}") { seen shouldBe reference }
                    }
                    when (val expected = variant.expectedLocation(slug)) {
                        null -> reference.status shouldBe HttpStatusCode.NotFound
                        else -> {
                            reference.status shouldBe HttpStatusCode(303, "See Other")
                            reference.headers[HttpHeaders.Location] shouldContainExactly listOf(expected)
                        }
                    }
                    // the follow-up page is identical for everybody as well (it takes no registration id)
                    val thanks = client.get("/veranstaltung/$slug/danke").bodyAsText()
                    thanks shouldContain "Danke. Wir haben Ihnen eine E-Mail geschickt."
                    thanks shouldNotContain "Vorgang"
                    // no payment provider round-trip happened inside any of these requests
                    if (stripe != null) stripe.calls.get() shouldBe 0
                }
            }
        }

        test(
            "account addresses and strangers differ ONLY in the mails: no row for an account address, the right notice mail, no leak of visitor input",
        ) {
            val (eventId, slug) = fixtures.createEvent()
            val tag = Uuid.random().toString().take(8)
            val stored = "Mitglied.$tag@Example.ORG"
            fixtures.createMember(email = stored, status = MemberStatus.ACTIVE)
            withMemberAwareEventApp(mail = mail) {
                mail.settle()
                val response = postGuestForm(slug = slug, name = "Xq7Marker", email = stored.uppercase(), message = "Yq7Marker")
                response.shouldRedirectTo("/veranstaltung/$slug/danke")
                fixtures.registrationCount(eventId) shouldBe 0
                val mails = mail.settle()
                mails.size shouldBe 1
                val notice = mails.single()
                notice.to shouldBe stored // the address of the account row, exactly as stored
                notice.subject shouldBe "Hinweis zur Veranstaltung \"Mitglieder-Test-Veranstaltung\""
                for (text in listOf(notice.plainText, notice.html, notice.subject)) {
                    text shouldNotContain "Xq7Marker"
                    text shouldNotContain "Yq7Marker"
                }
                notice.plainText shouldContain "$TEST_BASE_URL/app#/login"
                notice.plainText shouldContain "$TEST_BASE_URL/app#/events/$eventId"
                notice.plainText shouldContain "Es wurde keine Anmeldung gespeichert."
                notice.html shouldContain "href=\"$TEST_BASE_URL/app#/login\""
            }
        }

        test("the notice is sent once per event and address within 24 hours, and the answer does not change") {
            val (_, slug) = fixtures.createEvent()
            val email = "once-${Uuid.random()}@example.org"
            fixtures.createMember(email = email)
            withMemberAwareEventApp(mail = mail) {
                mail.settle()
                val first = postGuestForm(slug = slug, name = "A", email = email).observe()
                val second = postGuestForm(slug = slug, name = "B", email = email).observe()
                second shouldBe first
                mail.settle().size shouldBe 1
            }
        }

        test("the global cap drops further notice mails without changing the answer") {
            val (_, slug) = fixtures.createEvent()
            val emails = List(3) { "cap-$it-${Uuid.random()}@example.org" }
            emails.forEach { fixtures.createMember(email = it) }
            val limiter =
                EventNoticeMailLimiter(
                    global = FederationInboxRateLimiter(maxRequests = 2, window = 1.hours, maxTrackedKeys = 1),
                )
            withMemberAwareEventApp(mail = mail, noticeLimiter = limiter) {
                mail.settle()
                val answers = emails.map { postGuestForm(slug = slug, name = "X", email = it).observe() }
                answers.distinct().size shouldBe 1
                mail.settle().size shouldBe 2
            }
        }

        test("an event that is closed is answered 404 BEFORE the address is looked at: no notice mail for an account address") {
            val (eventId, slug) =
                fixtures.createEvent(
                    startsAt = LocalDateTime(2020, 1, 1, 18, 0),
                    endsAt = LocalDateTime(2020, 1, 1, 20, 0),
                )
            val email = "closed-${Uuid.random()}@example.org"
            fixtures.createMember(email = email)
            withMemberAwareEventApp(mail = mail) {
                mail.settle()
                postGuestForm(slug = slug, name = "X", email = email).status shouldBe HttpStatusCode.NotFound
                mail.settle().size shouldBe 0
                fixtures.registrationCount(eventId) shouldBe 0
            }
        }

        test("a stranger on a free event: CONFIRMED with the confirmation mail; on a full one: WAITLISTED with the waitlist mail") {
            val (freeId, freeSlug) = fixtures.createEvent()
            val (fullId, fullSlug) = fixtures.createEvent(capacity = 1)
            fixtures.insertRegistration(eventId = fullId, guestEmail = "holder-${Uuid.random()}@example.org")
            val a = "stranger-a-${Uuid.random()}@example.org"
            val b = "stranger-b-${Uuid.random()}@example.org"
            withMemberAwareEventApp(mail = mail) {
                mail.settle()
                postGuestForm(slug = freeSlug, name = "Anna", email = a).shouldRedirectTo("/veranstaltung/$freeSlug/danke")
                postGuestForm(slug = fullSlug, name = "Berta", email = b).shouldRedirectTo("/veranstaltung/$fullSlug/danke")
                val mails = mail.settle()
                mails.single { it.to == a }.subject shouldBe "Anmeldebestätigung: Mitglieder-Test-Veranstaltung"
                mails.single { it.to == b }.subject shouldBe "Warteliste: Mitglieder-Test-Veranstaltung"
                fixtures.registrations(freeId).single()[EventRegistrationTable.status] shouldBe EventRegistrationStatus.CONFIRMED
                fixtures
                    .registrations(fullId)
                    .single { it[EventRegistrationTable.guestEmail] == b }[EventRegistrationTable.status] shouldBe
                    EventRegistrationStatus.WAITLISTED
            }
        }

        test(
            "a paying guest: PENDING_PAYMENT, NO provider call in the POST, a mail with the pay link, and the link works (GET page, POST 303, session reused)",
        ) {
            fixtures.enableGateway()
            val stripe = CountingStripe()
            val (eventId, slug) = fixtures.createEvent(fee = BigDecimal("15.00"))
            val email = "payer-${Uuid.random()}@example.org"
            withMemberAwareEventApp(mail = mail, stripe = stripe) {
                mail.settle()
                postGuestForm(slug = slug, name = "Zahler", email = email).shouldRedirectTo("/veranstaltung/$slug/danke")
                stripe.calls.get() shouldBe 0
                fixtures.registrations(eventId).single()[EventRegistrationTable.status] shouldBe EventRegistrationStatus.PENDING_PAYMENT
                val message = mail.settle().single { it.to == email }
                message.subject shouldBe "Bitte Zahlung abschließen: Mitglieder-Test-Veranstaltung"
                val payUrl = Regex("""Jetzt bezahlen: (\S+)""").find(message.plainText)!!.groupValues[1]
                payUrl shouldContain "$TEST_BASE_URL/veranstaltung/$slug/zahlung?token="
                message.html shouldContain "href=\"$payUrl\">Jetzt bezahlen</a>"
                val token = payUrl.substringAfter("token=")

                val confirm = client.get("/veranstaltung/$slug/zahlung?token=$token").bodyAsText()
                confirm shouldContain "value=\"$token\""
                val first =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(eventFormBody("token" to token))
                    }
                first.status shouldBe HttpStatusCode(303, "See Other")
                val location = first.headers[HttpHeaders.Location]!!
                location shouldContain "https://checkout.stripe.com/c/pay/"
                stripe.calls.get() shouldBe 1
                val again =
                    client.post("/veranstaltung/$slug/zahlung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(eventFormBody("token" to token))
                    }
                again.headers[HttpHeaders.Location] shouldBe location
                stripe.calls.get() shouldBe 1 // the stored checkout session was reused, no second one minted
            }
        }

        test("a duplicate guest: one duplicate notice (no new token), the stored row is untouched, the answer is the neutral one") {
            val (eventId, slug) = fixtures.createEvent()
            val email = "twice-${Uuid.random()}@example.org"
            withMemberAwareEventApp(mail = mail) {
                mail.settle()
                postGuestForm(slug = slug, name = "Doppelt", email = email).shouldRedirectTo("/veranstaltung/$slug/danke")
                mail.settle().single().subject shouldBe "Anmeldebestätigung: Mitglieder-Test-Veranstaltung"
                val before = fixtures.registrations(eventId).single()
                postGuestForm(slug = slug, name = "Doppelt", email = email).shouldRedirectTo("/veranstaltung/$slug/danke")
                val notice = mail.settle().single()
                notice.to shouldBe email
                notice.subject shouldBe "Ihre Anmeldung: Mitglieder-Test-Veranstaltung"
                notice.plainText shouldNotContain "token="
                notice.html shouldNotContain "token="
                val after = fixtures.registrations(eventId).single()
                after[EventRegistrationTable.id] shouldBe before[EventRegistrationTable.id]
                after[EventRegistrationTable.cancelTokenSha256] shouldBe before[EventRegistrationTable.cancelTokenSha256]
            }
        }

        test("a signed-in member who types their OWN address into the guest form gets no row and the member notice") {
            val (eventId, slug) = fixtures.createEvent()
            val email = "self-${Uuid.random()}@example.org"
            val memberId = fixtures.createMember(email = email)
            val token = fixtures.sessionFor(memberId)
            withMemberAwareEventApp(mail = mail) {
                mail.settle()
                val response =
                    client.post("/veranstaltung/$slug/anmeldung") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        header(HttpHeaders.Cookie, "lapis_session=$token")
                        setBody(eventFormBody("guestName" to "Ich", "guestEmail" to email))
                    }
                response.shouldRedirectTo("/veranstaltung/$slug/danke")
                fixtures.registrationCount(eventId) shouldBe 0
                mail.settle().single().subject shouldBe "Hinweis zur Veranstaltung \"Mitglieder-Test-Veranstaltung\""
            }
        }

        test("EMBED: same answers with embed=1 kept in the Location, and a paying guest gets no hand-off page any more") {
            fixtures.enableGateway()
            val stripe = CountingStripe()
            val (_, freeSlug) = fixtures.createEvent()
            val (_, paidSlug) = fixtures.createEvent(fee = BigDecimal("9.00"))
            val member = "embed-member-${Uuid.random()}@example.org"
            fixtures.createMember(email = member)
            withMemberAwareEventApp(mail = mail, stripe = stripe) {
                mail.settle()
                val stranger =
                    postGuestForm(slug = freeSlug, name = "E", email = "embed-stranger-${Uuid.random()}@example.org", query = "?embed=1")
                val account = postGuestForm(slug = freeSlug, name = "E", email = member, query = "?embed=1")
                stranger.shouldRedirectTo("/veranstaltung/$freeSlug/danke?embed=1")
                account.shouldRedirectTo("/veranstaltung/$freeSlug/danke?embed=1")
                account.observe() shouldBe stranger.observe()
                val paid =
                    postGuestForm(slug = paidSlug, name = "E", email = "embed-payer-${Uuid.random()}@example.org", query = "?embed=1")
                paid.shouldRedirectTo("/veranstaltung/$paidSlug/danke?embed=1")
                stripe.calls.get() shouldBe 0
                mail.settle()
            }
        }

        test("/danke ignores a registration id, and /bezahlt is the neutral payment-return page") {
            val (_, slug) = fixtures.createEvent()
            withMemberAwareEventApp(mail = mail) {
                val plain = client.get("/veranstaltung/$slug/danke").bodyAsText()
                client.get("/veranstaltung/$slug/danke?r=${Uuid.random()}").bodyAsText() shouldBe plain
                val paid = client.get("/veranstaltung/$slug/bezahlt?r=${Uuid.random()}")
                paid.status shouldBe HttpStatusCode.OK
                paid.headers["X-Robots-Tag"] shouldBe "noindex, nofollow"
                paid.bodyAsText() shouldContain "Danke für Ihre Zahlung"
                client.get("/veranstaltung/$slug/bezahlt").bodyAsText() shouldBe paid.bodyAsText()
            }
        }

        test("the payment provider now returns to /bezahlt, and cancel still returns to /abgebrochen") {
            val urls =
                network.lapis.cloud.server.payment.psp.PspReturnUrls
                    .eventRegistration(baseUrl = TEST_BASE_URL, slug = "s", registrationId = "abc")
            urls.successUrl shouldBe "$TEST_BASE_URL/veranstaltung/s/bezahlt?r=abc"
            urls.cancelUrl shouldBe "$TEST_BASE_URL/veranstaltung/s/abgebrochen?r=abc"
        }
    })
