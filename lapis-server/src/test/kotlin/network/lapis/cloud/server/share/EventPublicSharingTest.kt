package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.routes.eventTestBranding
import network.lapis.cloud.server.routes.eventTestEmbedConfig
import network.lapis.cloud.server.routes.eventTestNav
import network.lapis.cloud.server.routes.registerEventPublicRoutes
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jsoup.Jsoup
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val BASE = "https://example.org"
private const val CSP_PLAIN =
    "default-src 'none'; img-src 'self' data:; style-src 'self'; font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
private const val CSP_SCRIPT =
    "default-src 'none'; img-src 'self' data:; style-src 'self'; font-src 'self'; script-src 'self'; " +
        "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

class EventPublicSharingTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val eventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                if (eventIds.isNotEmpty()) EventTable.deleteWhere { id inList eventIds }
                if (memberIds.isNotEmpty()) MemberTable.deleteWhere { id inList memberIds }
            }
        }

        val organizer: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventPublicSharingTest Organisator"
                    it[email] = "event-share-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            memberIds += id
            id
        }

        fun createEvent(
            title: String = "Teilen-Test",
            description: String = "Erster Absatz.\n\nZweiter Absatz.",
            location: String? = "Testort",
            onlineUrl: String? = null,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
        ): String {
            val id = Uuid.random()
            val slug = "event-share-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[EventTable.title] = title
                    it[EventTable.description] = description
                    it[locationText] = location
                    it[EventTable.onlineUrl] = onlineUrl
                    it[onlineKind] = if (onlineUrl != null) EventOnlineKind.EXTERNAL_LINK else EventOnlineKind.NONE
                    it[onlineUrlPublic] = onlineUrl != null
                    it[startsAt] = LocalDateTime(2030, 11, 12, 19, 0)
                    it[endsAt] = LocalDateTime(2030, 11, 12, 21, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
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
            return slug
        }

        fun limiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun withApp(
            share: ShareConfig,
            branding: ResolvedBranding = eventTestBranding(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                install(XForwardedHeaders) { useLastProxy() }
                routing {
                    registerEventPublicRoutes(
                        checkoutGateways = emptyMap(),
                        baseUrl = BASE,
                        mailDispatcher =
                            MailDispatcher(
                                transport = NoOpMailTransport(),
                                scope =
                                    CoroutineScope(
                                        SupervisorJob() + Dispatchers.IO,
                                    ),
                            ),
                        branding = branding,
                        navAvailability = eventTestNav(),
                        embedConfig = eventTestEmbedConfig(),
                        pageRateLimiter = limiter(),
                        attemptRateLimiter = limiter(),
                        registrationRateLimiter = limiter(),
                        ticketPageRateLimiter = limiter(),
                        ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                        icsFeedRateLimiter = limiter(),
                        shareConfig = share,
                    )
                }
            }
            block()
        }

        val enabled = ShareConfig.load(baseUrl = BASE, env = { null })

        test("detail page: share bar, share.js and the widened CSP") {
            val slug = createEvent()
            withApp(enabled) {
                val response = client.get("/veranstaltung/$slug")
                response.headers["Content-Security-Policy"] shouldBe CSP_SCRIPT
                val doc = Jsoup.parse(response.bodyAsText())
                doc.select("section.share").size shouldBe 1
                doc.select("script[src=/s/assets/share.js][defer]").size shouldBe 1
                doc.select("section.share").single().attr("data-share-url") shouldBe "$BASE/veranstaltung/$slug"
                // the bar sits outside the German content wrapper
                doc.select("div[lang=de] section.share").size shouldBe 0
            }
        }

        test("DISABLED: no bar, no script, CSP byte-identical to before") {
            val slug = createEvent()
            withApp(ShareConfig.DISABLED) {
                val response = client.get("/veranstaltung/$slug")
                response.headers["Content-Security-Policy"] shouldBe CSP_PLAIN
                val html = response.bodyAsText()
                html shouldNotContain "class=\"share\""
                html shouldNotContain "share.js"
                html shouldNotContain "application/ld+json"
            }
        }

        test("only facebook: bar without script, CSP unchanged") {
            val slug = createEvent()
            withApp(ShareConfig.load(baseUrl = BASE, env = { "facebook" })) {
                val response = client.get("/veranstaltung/$slug")
                response.headers["Content-Security-Policy"] shouldBe CSP_PLAIN
                val html = response.bodyAsText()
                html shouldContain "class=\"share\""
                html shouldNotContain "share.js"
            }
        }

        test("no bar and the old CSP on embed, 404 and unpublished events") {
            val slug = createEvent()
            val draft = createEvent(status = EventStatus.DRAFT)
            val internal = createEvent(visibility = EventVisibility.MEMBERS_ONLY)
            withApp(enabled) {
                for (path in listOf(
                    "/veranstaltung/$slug?embed=1",
                    "/veranstaltung/gibt-es-nicht",
                    "/veranstaltung/$draft",
                    "/veranstaltung/$internal",
                )) {
                    val response = client.get(path)
                    response.headers["Content-Security-Policy"] shouldBe
                        CSP_PLAIN.let { if (path.endsWith("embed=1")) it.replace("img-src 'self' data:", "img-src 'self'") else it }
                    response.bodyAsText() shouldNotContain "class=\"share\""
                }
                for (page in listOf("danke", "warteliste", "abgebrochen", "ticket?code=x", "storno?token=x", "zahlung?token=x")) {
                    val response = client.get("/veranstaltung/$slug/$page")
                    response.headers["Content-Security-Policy"] shouldBe CSP_PLAIN
                    response.bodyAsText() shouldNotContain "class=\"share\""
                }
            }
        }

        test("a spoofed Host or X-Forwarded-Host never reaches a share URL") {
            val slug = createEvent()
            withApp(enabled) {
                val html =
                    client
                        .get("/veranstaltung/$slug") {
                            header(HttpHeaders.Host, "evil.example")
                            header("X-Forwarded-Host", "evil.example")
                        }.bodyAsText()
                html shouldNotContain "evil.example"
                val doc = Jsoup.parse(html)
                doc.select("link[rel=canonical]").single().attr("href") shouldBe "$BASE/veranstaltung/$slug"
                doc.select("meta[property=og:url]").single().attr("content") shouldBe "$BASE/veranstaltung/$slug"
                doc.select("a[href^=https://www.facebook.com]").single().attr("href") shouldContain
                    "u=https%3A%2F%2Fexample.org%2Fveranstaltung%2F"
            }
        }

        test("?lang=en: English labels, language-specific URL, German content wrapper") {
            val slug = createEvent()
            withApp(enabled) {
                val doc = Jsoup.parse(client.get("/veranstaltung/$slug?lang=en").bodyAsText())
                doc.select("section.share").single().attr("data-share-url") shouldBe "$BASE/veranstaltung/$slug?lang=en"
                doc.select("section.share h2").text() shouldBe "Share"
                doc.select("div[lang=de] h1.event-title").size shouldBe 1
            }
        }

        test("Open Graph: description, type, card and site name; hostile text stays escaped") {
            val slug = createEvent(title = "Fest \"<b>x</b>\" & mehr")
            withApp(enabled) {
                val doc = Jsoup.parse(client.get("/veranstaltung/$slug").bodyAsText())
                val description = doc.select("meta[property=og:description]").single().attr("content")
                description shouldContain "Di, 12.11.2030, 19:00 Uhr"
                description shouldContain "Testort"
                description shouldContain "Erster Absatz."
                description shouldNotContain "Zweiter"
                doc.select("meta[property=og:type]").single().attr("content") shouldBe "website"
                doc.select("meta[name=twitter:card]").single().attr("content") shouldBe "summary"
                doc.select("meta[property=og:site_name]").single().attr("content") shouldBe "Testverein"
                doc.select("meta[property=og:title]").single().attr("content") shouldBe "Fest \"<b>x</b>\" & mehr"
            }
        }

        test("a purely online event says Online") {
            val slug = createEvent(location = null, onlineUrl = "https://meet.example/x")
            withApp(enabled) {
                val doc = Jsoup.parse(client.get("/veranstaltung/$slug").bodyAsText())
                doc.select("meta[property=og:description]").single().attr("content") shouldContain "Online"
            }
        }

        test("og:image: raster logo is used, svg logo and no logo are not") {
            val slug = createEvent()

            fun logo(path: String?) = ResolvedBranding(title = "Testverein", logoAvailable = path != null, logoPath = path)
            withApp(enabled, logo("/etc/logo.png")) {
                Jsoup
                    .parse(
                        client.get("/veranstaltung/$slug").bodyAsText(),
                    ).select("meta[property=og:image]")
                    .single()
                    .attr("content") shouldBe
                    "$BASE/api/branding/logo"
            }
            withApp(enabled, logo("/etc/logo.svg")) {
                Jsoup.parse(client.get("/veranstaltung/$slug").bodyAsText()).select("meta[property=og:image]").size shouldBe 0
            }
            withApp(enabled, logo(null)) {
                Jsoup.parse(client.get("/veranstaltung/$slug").bodyAsText()).select("meta[property=og:image]").size shouldBe 0
            }
        }

        test("JSON-LD is valid JSON, script-safe, and carries the offset start date") {
            val slug = createEvent(title = "</script><script>alert(1)</script>\u2028x")
            withApp(enabled) {
                val html = client.get("/veranstaltung/$slug").bodyAsText()
                val doc = Jsoup.parse(html)
                val raw = doc.select("script[type=application/ld+json]").single().data()
                val block = html.substringAfter("<script type=\"application/ld+json\">").substringBefore("</script>")
                block shouldNotContain "<"
                block shouldNotContain "\u2028"
                block shouldContain "\\u003c/script\\u003e"
                val json: JsonObject = Json.parseToJsonElement(raw).jsonObject
                json["@type"]!!.jsonPrimitive.content shouldBe "Event"
                Regex("^2030-11-12T19:00(:00)?[+-]\\d{2}:\\d{2}$").matches(json["startDate"]!!.jsonPrimitive.content) shouldBe true
                json["location"]!!.jsonObject["@type"]!!.jsonPrimitive.content shouldBe "Place"
                // no inline script apart from the data block
                doc.select("script:not([type=application/ld+json])").all { it.hasAttr("src") } shouldBe true
            }
        }

        test("every external reference is relative or on the page's own origin") {
            val slug = createEvent()
            withApp(enabled) {
                val doc = Jsoup.parse(client.get("/veranstaltung/$slug").bodyAsText())
                val refs =
                    doc
                        .select(
                            "script[src], img[src], link[rel=stylesheet][href]",
                        ).map { it.attr("src").ifEmpty { it.attr("href") } }
                refs.all { it.startsWith("/") || it.startsWith(BASE) } shouldBe true
            }
        }

        test("escapeForScriptBlock neutralizes every dangerous character and keeps valid JSON") {
            val escaped = EventShareMeta.escapeForScriptBlock("{\"a\":\"<>&\u2028\u2029\"}")
            escaped shouldBe "{\"a\":\"\\u003c\\u003e\\u0026\\u2028\\u2029\"}"
            Json
                .parseToJsonElement(escaped)
                .jsonObject["a"]!!
                .jsonPrimitive.content shouldBe "<>&\u2028\u2029"
        }
    })
