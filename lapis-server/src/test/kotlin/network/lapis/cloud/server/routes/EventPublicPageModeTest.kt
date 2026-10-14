package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.routing.get
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
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.128 -- the two presentations of the public `/veranstaltung/...` page family: the standard page with the Lapis header and the slim
 * `?embed=1` variant. Covers the chrome on every HTML page, the pass-through of the mode and the language through the registration flow,
 * the framing rules of the security headers, the token protection of the language switcher and the form/accessibility details.
 */
class EventPublicPageModeTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val secretToken = "geheimes-token-abc123"

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        val organizer: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventPublicPageModeTest Organisator"
                    it[email] = "event-page-mode-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            id
        }

        fun createEvent(
            title: String = "Seitenmodus-Test",
            description: String = "Zeile eins\nZeile zwei",
            capacity: Int? = null,
            startsAt: LocalDateTime = LocalDateTime(2030, 1, 1, 18, 0),
            endsAt: LocalDateTime = LocalDateTime(2030, 1, 1, 22, 0),
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            val slug = "event-page-mode-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[EventTable.description] = description
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal.ZERO
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

        /** Inserts a registration of [status]; returns the RAW ticket code (only its hash is stored). */
        fun createRegistration(
            eventId: Uuid,
            status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
        ): String {
            val rawCode = EventTicketPolicy.newRawCode()
            val now = DbClock.nowLocalDateTime()
            val email = "page-mode-guest-${Uuid.random()}@example.org"
            transaction {
                EventStore.insertRegistration(
                    id = Uuid.random(),
                    eventId = eventId,
                    memberId = null,
                    guestName = "Seitenmodus-Gast",
                    guestEmail = email,
                    activeParticipantKey = if (status == EventRegistrationStatus.CANCELLED) null else "g:$email",
                    status = status,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = "cancel-${Uuid.random()}",
                    registeredAt = now,
                    confirmedAt = if (status == EventRegistrationStatus.CONFIRMED) now else null,
                    ticketCodeSha256 = sha256Hex(rawCode.toByteArray(Charsets.US_ASCII)),
                    ticketIssuedAt = now,
                )
            }
            return rawCode
        }

        fun limiter(maxRequests: Int = 10_000) = FederationInboxRateLimiter(maxRequests = maxRequests, window = 1.minutes)

        suspend fun withApp(
            embedConfig: EmbedConfig = eventTestEmbedConfig(),
            pageLimiter: FederationInboxRateLimiter = limiter(),
            extraRoutes: io.ktor.server.routing.Route.() -> Unit = {},
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    registerEventPublicRoutes(
                        checkoutGateways = emptyMap(),
                        baseUrl = "https://example.org",
                        mailDispatcher =
                            MailDispatcher(
                                transport = NoOpMailTransport(),
                                scope =
                                    CoroutineScope(
                                        SupervisorJob() + Dispatchers.IO,
                                    ),
                            ),
                        branding = eventTestBranding(),
                        navAvailability = eventTestNav(),
                        embedConfig = embedConfig,
                        pageRateLimiter = pageLimiter,
                        attemptRateLimiter = limiter(),
                        registrationRateLimiter = limiter(),
                        ticketPageRateLimiter = limiter(),
                        ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                        icsFeedRateLimiter = limiter(),
                    )
                    extraRoutes()
                }
            }
            block()
        }

        fun String.withParam(param: String): String = this + (if (contains('?')) "&" else "?") + param

        fun formBody(vararg pairs: Pair<String, String>): String =
            Parameters.build { pairs.forEach { (k, v) -> append(k, v) } }.formUrlEncode()

        suspend fun ApplicationTestBuilder.postForm(
            url: String,
            vararg pairs: Pair<String, String>,
        ): HttpResponse =
            client.post(url) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody(*pairs))
            }

        fun assertChrome(html: String) {
            html shouldContain "class=\"has-chrome event-wide\""
            html shouldContain "class=\"skip-link\""
            html shouldContain "<header class=\"chrome\""
            html shouldContain "class=\"chrome-nav\""
            html shouldContain "class=\"chrome-lang\""
            html shouldContain "id=\"main\""
            html shouldContain "<footer"
        }

        fun assertSlim(html: String) {
            html shouldContain "embed-page event-embed"
            html shouldNotContain "has-chrome"
            html shouldNotContain "skip-link"
            html shouldNotContain "chrome-nav"
            html shouldNotContain "chrome-lang"
            html shouldNotContain "hreflang"
            html shouldNotContain "<footer"
        }

        /** Every GET page of the family as (name, path). */
        fun getPages(
            slug: String,
            ticketCode: String,
            cancelledCode: String,
        ): List<Pair<String, String>> =
            listOf(
                "detail" to "/veranstaltung/$slug",
                "danke" to "/veranstaltung/$slug/danke",
                "warteliste" to "/veranstaltung/$slug/warteliste",
                "abgebrochen" to "/veranstaltung/$slug/abgebrochen",
                "storno" to "/veranstaltung/$slug/storno?token=$secretToken",
                "zahlung" to "/veranstaltung/$slug/zahlung?token=$secretToken",
                "ticket" to "/veranstaltung/$slug/ticket?code=$ticketCode",
                "ticket-not-confirmed" to "/veranstaltung/$slug/ticket?code=$cancelledCode",
                "404" to "/veranstaltung/gibt-es-nicht-xyz",
            )

        test("STANDARD mode: every HTML page of the family carries the full Lapis header and footer") {
            val (eventId, slug) = createEvent()
            val code = createRegistration(eventId)
            val cancelled = createRegistration(eventId, EventRegistrationStatus.CANCELLED)
            withApp {
                for ((name, path) in getPages(slug, code, cancelled)) {
                    val html = client.get(path).bodyAsText()
                    withClue(name) { assertChrome(html) }
                }
                // POST pages: storno success, invalid input, malformed
                assertChrome(postForm("/veranstaltung/$slug/storno", "token" to "nope").bodyAsText())
                assertChrome(postForm("/veranstaltung/$slug/anmeldung", "guestName" to "", "guestEmail" to "x").bodyAsText())
                val malformed =
                    client.post("/veranstaltung/$slug/anmeldung") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                malformed.status shouldBe HttpStatusCode.BadRequest
                assertChrome(malformed.bodyAsText())
            }
        }

        test("EMBED mode: every HTML page of the family keeps the slim shape, no header, no footer") {
            val (eventId, slug) = createEvent()
            val code = createRegistration(eventId)
            val cancelled = createRegistration(eventId, EventRegistrationStatus.CANCELLED)
            withApp {
                for ((name, path) in getPages(slug, code, cancelled)) {
                    val html = client.get(path.withParam("embed=1")).bodyAsText()
                    withClue(name) { assertSlim(html) }
                }
                assertSlim(postForm("/veranstaltung/$slug/storno?embed=1", "token" to "nope").bodyAsText())
                assertSlim(postForm("/veranstaltung/$slug/anmeldung?embed=1", "guestName" to "", "guestEmail" to "x").bodyAsText())
            }
        }

        test("429 and 500 pages carry the chrome in STANDARD and the slim shape in EMBED") {
            val (_, slug) = createEvent()
            withApp(
                pageLimiter = limiter(maxRequests = 1),
                extraRoutes = {
                    get("/test-500") {
                        val ctx =
                            call.eventPageContext(
                                baseUrl = "https://example.org",
                                branding = eventTestBranding(),
                                navAvailability = eventTestNav(),
                                frameAncestors = emptyList(),
                            )
                        call.withEventPublicErrorHandling(ctx = ctx) { error("boom") }
                    }
                },
            ) {
                client.get("/veranstaltung/$slug") // consumes the single allowed request
                val limited = client.get("/veranstaltung/$slug")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                assertChrome(limited.bodyAsText())
                val limitedEmbed = client.get("/veranstaltung/$slug?embed=1")
                limitedEmbed.status shouldBe HttpStatusCode.TooManyRequests
                assertSlim(limitedEmbed.bodyAsText())

                val failed = client.get("/test-500")
                failed.status shouldBe HttpStatusCode.InternalServerError
                assertChrome(failed.bodyAsText())
                failed.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val failedEmbed = client.get("/test-500?embed=1")
                failedEmbed.status shouldBe HttpStatusCode.InternalServerError
                assertSlim(failedEmbed.bodyAsText())
            }
        }

        test("the EMBED detail page is noindex and canonicalises onto the standard URL; the standard page has canonical and hreflang") {
            val (_, slug) = createEvent()
            withApp {
                val embed = client.get("/veranstaltung/$slug?embed=1").bodyAsText()
                embed shouldContain "<meta name=\"robots\" content=\"noindex\">"
                embed shouldContain "<link href=\"https://example.org/veranstaltung/$slug\" rel=\"canonical\">"
                val standard = client.get("/veranstaltung/$slug").bodyAsText()
                standard shouldContain "<link href=\"https://example.org/veranstaltung/$slug\" rel=\"canonical\">"
                standard shouldContain "hreflang=\"en\""
                standard shouldNotContain "noindex"
            }
        }

        test("embed=1 is passed on: form action, redirects, storno/zahlung form actions, ticket links, top-level footer link") {
            val (eventId, slug) = createEvent()
            val code = createRegistration(eventId)
            withApp {
                val detail = client.get("/veranstaltung/$slug?embed=1").bodyAsText()
                detail shouldContain "action=\"/veranstaltung/$slug/anmeldung?embed=1\""
                detail shouldContain "<a href=\"/\" target=\"_top\">Zur Startseite</a>"
                detail shouldContain "href=\"/impressum\" target=\"_top\""

                client.get("/veranstaltung/$slug/storno?token=$secretToken&embed=1").bodyAsText() shouldContain
                    "action=\"/veranstaltung/$slug/storno?embed=1\""
                client.get("/veranstaltung/$slug/zahlung?token=$secretToken&embed=1").bodyAsText() shouldContain
                    "action=\"/veranstaltung/$slug/zahlung?embed=1\""

                val ticket = client.get("/veranstaltung/$slug/ticket?code=$code&embed=1").bodyAsText()
                ticket shouldContain "src=\"/veranstaltung/$slug/ticket.svg?code=$code&amp;embed=1\""
                ticket shouldContain "href=\"/veranstaltung/$slug/ticket.pdf?code=$code&amp;embed=1\""

                val honeypot = postForm("/veranstaltung/$slug/anmeldung?embed=1", "kommentar" to "spam", "guestName" to "x")
                honeypot.status shouldBe HttpStatusCode(303, "See Other")
                honeypot.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/abgebrochen?embed=1"

                val registered = postForm("/veranstaltung/$slug/anmeldung?embed=1", "guestName" to "Eva", "guestEmail" to "eva@example.org")
                registered.status shouldBe HttpStatusCode(303, "See Other")
                registered.headers[HttpHeaders.Location]!! shouldContain "/veranstaltung/$slug/danke?r="
                registered.headers[HttpHeaders.Location]!! shouldEndWith "&embed=1"

                val badToken = postForm("/veranstaltung/$slug/zahlung?embed=1", "token" to "")
                badToken.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/abgebrochen?embed=1"
            }
        }

        test("a full event sends a registrant to the waitlist page and keeps the mode") {
            val (eventId, slug) = createEvent(capacity = 1)
            createRegistration(eventId)
            withApp {
                val standard = postForm("/veranstaltung/$slug/anmeldung", "guestName" to "Zoe", "guestEmail" to "zoe@example.org")
                standard.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/warteliste"
                val embed = postForm("/veranstaltung/$slug/anmeldung?embed=1", "guestName" to "Zed", "guestEmail" to "zed@example.org")
                embed.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/warteliste?embed=1"
                val page = client.get("/veranstaltung/$slug").bodyAsText()
                page shouldContain "event-callout--waitlist"
                page shouldContain "Auf die Warteliste setzen"
                page shouldContain "Ausgebucht – Warteliste möglich"
            }
        }

        test("STANDARD mode passes ?lang= on; the page body stays German") {
            val (_, slug) = createEvent()
            withApp {
                val html = client.get("/veranstaltung/$slug?lang=en").bodyAsText()
                html shouldContain "<html lang=\"en\">"
                html shouldContain "<div lang=\"de\">"
                html shouldContain "action=\"/veranstaltung/$slug/anmeldung?lang=en\""
                val redirect =
                    postForm("/veranstaltung/$slug/anmeldung?lang=en", "kommentar" to "spam", "guestName" to "x")
                redirect.headers[HttpHeaders.Location] shouldBe "/veranstaltung/$slug/abgebrochen?lang=en"
                // EMBED has no chrome and ignores the language entirely.
                val embed = client.get("/veranstaltung/$slug?embed=1&lang=en").bodyAsText()
                embed shouldNotContain "lang=en"
                embed shouldContain "<html lang=\"de\">"
            }
        }

        test("only a single embed=1 activates EMBED; everything else is STANDARD and nothing is reflected") {
            val (_, slug) = createEvent()
            withApp {
                for (variant in listOf("embed=0", "embed=true", "embed=1&embed=1", "embed=%3Cscript%3Ealert(1)%3C/script%3E", "embed=")) {
                    val html = client.get("/veranstaltung/$slug?$variant").bodyAsText()
                    withClue(variant) {
                        assertChrome(html)
                        html shouldNotContain "alert(1)"
                        html shouldNotContain "embed=true"
                        html shouldNotContain "embed="
                    }
                }
                val hostile = client.get("/veranstaltung/$slug?lang=%3Cscript%3Ealert(2)%3C/script%3E").bodyAsText()
                hostile shouldNotContain "alert(2)"
                hostile shouldContain "<html lang=\"de\">"
            }
        }

        test("token pages: the bearer token only appears in the hidden input, never in a chrome or language-switcher link") {
            val (eventId, slug) = createEvent()
            val code = createRegistration(eventId)
            withApp {
                for (kind in listOf("storno", "zahlung")) {
                    val html = client.get("/veranstaltung/$slug/$kind?token=$secretToken&lang=en").bodyAsText()
                    withClue(kind) {
                        html.split(secretToken).size - 1 shouldBe 1
                        html shouldContain "name=\"token\" value=\"$secretToken\""
                        // the (German) language-switcher entry points at the bare detail page -- no token, no query
                        html shouldContain "<a href=\"https://example.org/veranstaltung/$slug\" hreflang=\"de\">"
                    }
                }
                val ticket = client.get("/veranstaltung/$slug/ticket?code=$code&lang=en").bodyAsText()
                // the raw code only appears in the QR and PDF URLs of the page itself (the display form is dash-grouped),
                // never in the language switcher or any other chrome link
                ticket.split(code).size - 1 shouldBe 2
            }
        }

        test("security headers: STANDARD is never frameable, even with a configured allowlist") {
            val (_, slug) = createEvent()
            withApp(embedConfig = eventTestEmbedConfigEnabled("https://partner.example")) {
                val response = client.get("/veranstaltung/$slug")
                val csp = response.headers["Content-Security-Policy"]!!
                csp shouldContain "frame-ancestors 'none'"
                csp shouldContain "img-src 'self' data:"
                response.headers["X-Frame-Options"] shouldBe "DENY"
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
        }

        test("security headers: EMBED stays 'none'/DENY while the embed feature is off") {
            val (_, slug) = createEvent()
            withApp(embedConfig = eventTestEmbedConfig()) {
                val response = client.get("/veranstaltung/$slug?embed=1")
                val csp = response.headers["Content-Security-Policy"]!!
                csp shouldContain "frame-ancestors 'none'"
                csp shouldContain "img-src 'self';"
                response.headers["X-Frame-Options"] shouldBe "DENY"
            }
        }

        test("security headers: EMBED with an allowlist may be framed by exactly those origins and sends no X-Frame-Options") {
            val (_, slug) = createEvent()
            withApp(embedConfig = eventTestEmbedConfigEnabled("https://partner.example", "https://www.partner.example:8443")) {
                for (path in listOf("/veranstaltung/$slug?embed=1", "/veranstaltung/gibt-es-nicht?embed=1")) {
                    val response = client.get(path)
                    val csp = response.headers["Content-Security-Policy"]!!
                    csp shouldContain "frame-ancestors 'self' https://partner.example https://www.partner.example:8443"
                    csp shouldContain "default-src 'none'"
                    csp shouldContain "style-src 'self'"
                    response.headers["X-Frame-Options"] shouldBe null
                }
            }
        }

        test("sanitizeFrameAncestors drops anything that is not a plain scheme://host[:port] origin") {
            val bad =
                listOf(
                    "https://evil.example; script-src *",
                    "https://a.example https://b.example",
                    "https://a.example/path",
                    "https://a.example,https://b.example",
                    "javascript:alert(1)",
                    "*",
                    "",
                )
            sanitizeFrameAncestors(bad + listOf("https://ok.example", "http://localhost:8080")) shouldBe
                listOf("https://ok.example", "http://localhost:8080")
        }

        test("the date is German and human-readable, never an ISO timestamp") {
            val (eventId, slug) = createEvent()
            val code = createRegistration(eventId)
            withApp {
                val detail = client.get("/veranstaltung/$slug").bodyAsText()
                detail shouldContain "Dienstag, 1. Januar 2030, 18:00 bis 22:00 Uhr"
                Regex("\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(detail) shouldBe false
                val ticket = client.get("/veranstaltung/$slug/ticket?code=$code").bodyAsText()
                ticket shouldContain "Dienstag, 1. Januar 2030, 18:00 bis 22:00 Uhr"
                Regex("\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(ticket) shouldBe false
            }
        }

        test("form: styled hooks, labels, autocomplete, required, honeypot hidden by CSS class and never by an inline style") {
            val (eventId, slug) = createEvent()
            val code = createRegistration(eventId)
            val cancelled = createRegistration(eventId, EventRegistrationStatus.CANCELLED)
            withApp {
                val html = client.get("/veranstaltung/$slug").bodyAsText()
                html shouldContain "class=\"event-form\""
                html shouldContain "<label for=\"guestName\">Name</label>"
                html shouldContain "id=\"guestName\""
                html shouldContain "autocomplete=\"name\""
                html shouldContain "<label for=\"guestEmail\">E-Mail-Adresse</label>"
                html shouldContain "id=\"guestEmail\""
                html shouldContain "autocomplete=\"email\""
                html shouldContain "required"
                html shouldContain "type=\"submit\""
                html shouldContain "Verbindlich anmelden"
                html shouldContain "Sie erhalten eine Bestätigung per E-Mail."
                html shouldContain "<div class=\"hp\" aria-hidden=\"true\">"
                html shouldContain "tabindex=\"-1\""
                html shouldContain "section class=\"event-card\" aria-labelledby=\"anmeldung-titel\""
                html shouldContain "id=\"anmeldung-titel\""
                for ((name, path) in getPages(slug, code, cancelled)) {
                    val page = client.get(path).bodyAsText()
                    withClue(name) {
                        page shouldNotContain "style="
                        page shouldNotContain "<script"
                    }
                }
            }
        }

        test("occupancy is binary: the Plätze row never carries a digit, for an open and for a full event") {
            val (_, openSlug) = createEvent(capacity = 50)
            val (fullId, fullSlug) = createEvent(capacity = 1)
            createRegistration(fullId)
            withApp {
                for (slug in listOf(openSlug, fullSlug)) {
                    val html = client.get("/veranstaltung/$slug").bodyAsText()
                    val row = Regex("<dt>Plätze</dt><dd>([^<]*)</dd>").find(html)!!.groupValues[1]
                    withClue(row) { row.any { it.isDigit() } shouldBe false }
                }
            }
        }

        test("an ended event has no Plätze row and no form, and says so in a status callout") {
            val (_, ended) = createEvent(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 22, 0))
            withApp {
                val html = client.get("/veranstaltung/$ended").bodyAsText()
                html shouldContain "role=\"status\""
                html shouldContain "Diese Veranstaltung hat stattgefunden."
                html shouldNotContain "<dt>Plätze</dt>"
                html shouldNotContain "<form"
            }
        }

        test("invalid input: 400 with a human message and a way back, nothing the visitor typed is reflected") {
            val (_, slug) = createEvent()
            withApp {
                val response = postForm("/veranstaltung/$slug/anmeldung?embed=1", "guestName" to "", "guestEmail" to "<b>mist</b>")
                response.status shouldBe HttpStatusCode.BadRequest
                val html = response.bodyAsText()
                html shouldContain "Bitte geben Sie Ihren Namen und eine gültige E-Mail-Adresse an."
                html shouldContain "href=\"/veranstaltung/$slug?embed=1\""
                html shouldNotContain "mist"
            }
        }

        test("the EMBED page renders deterministically") {
            val view = goldenView()
            EventPublicHtml.eventPage(ctx = eventTestContext(embed = true), view = view) shouldBe
                EventPublicHtml.eventPage(ctx = eventTestContext(embed = true), view = view)
        }

        test("golden: the EMBED detail page matches the checked-in fixture byte for byte") {
            val expected =
                EventPublicPageModeTest::class.java
                    .getResourceAsStream("/golden/event-page-embed.html")!!
                    .readBytes()
                    .toString(Charsets.UTF_8)
            EventPublicHtml.eventPage(ctx = eventTestContext(embed = true), view = goldenView()) shouldBe expected.trimEnd('\n')
        }

        test("EventPageContext.href appends exactly one mode parameter and never duplicates it") {
            val standard = eventTestContext()
            standard.href("/veranstaltung/a/storno?token=t") shouldBe "/veranstaltung/a/storno?token=t"
            val english = eventTestContext(lang = PublicLanguage.EN)
            english.href("/veranstaltung/a") shouldBe "/veranstaltung/a?lang=en"
            english.href("/veranstaltung/a?x=1") shouldBe "/veranstaltung/a?x=1&lang=en"
            val embed = eventTestContext(embed = true, lang = PublicLanguage.EN)
            embed.href("/veranstaltung/a") shouldBe "/veranstaltung/a?embed=1"
            embed.href("/veranstaltung/a/ticket.svg?code=C") shouldBe "/veranstaltung/a/ticket.svg?code=C&embed=1"
        }
    })

private fun goldenView(): EventPublicHtml.View =
    EventPublicHtml.View(
        title = "Sommerfest",
        slug = "sommerfest",
        description = "Zeile eins\nZeile zwei\n\nAbsatz zwei",
        locationText = "Gemeindehaus",
        onlineUrl = null,
        startsAt = LocalDateTime(2026, 10, 20, 20, 15),
        endsAt = LocalDateTime(2026, 10, 20, 22, 15),
        feeLabel = "kostenlos",
        full = false,
        registrationOpen = true,
    )
