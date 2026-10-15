package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.security.LoginRateLimiter
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.140 -- token and follow-up pages of the `/veranstaltung/...` family are never indexed: `X-Robots-Tag: noindex, nofollow` on EVERY
 * response (page, 404, 429, 400), `<meta name="robots" content="noindex, nofollow">` in every HTML body (standard and embed mode, never two
 * robots metas), and NOT on the detail page (200), which stays indexable in the standard mode.
 */
class EventFollowUpNoIndexTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val noIndexValue = "noindex, nofollow"
        val unknownSlug = "gibt-es-nicht-noindex-xyz"

        fun limiter(maxRequests: Int) = FederationInboxRateLimiter(maxRequests = maxRequests, window = 1.minutes)

        suspend fun withApp(
            maxRequests: Int = 10_000,
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
                                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                            ),
                        branding = eventTestBranding(),
                        navAvailability = eventTestNav(),
                        embedConfig = eventTestEmbedConfig(),
                        pageRateLimiter = limiter(maxRequests),
                        attemptRateLimiter = limiter(maxRequests),
                        registrationRateLimiter = limiter(maxRequests),
                        ticketPageRateLimiter = limiter(maxRequests),
                        ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                        icsFeedRateLimiter = limiter(maxRequests),
                    )
                }
            }
            block()
        }

        /** (name, method, path) of every handler that must carry the noindex header. */
        val followUps: List<Triple<String, String, String>> =
            listOf(
                Triple("anmeldung POST", "POST", "/veranstaltung/$unknownSlug/anmeldung"),
                Triple("danke", "GET", "/veranstaltung/$unknownSlug/danke"),
                Triple("warteliste", "GET", "/veranstaltung/$unknownSlug/warteliste"),
                Triple("abgebrochen", "GET", "/veranstaltung/$unknownSlug/abgebrochen"),
                Triple("storno GET", "GET", "/veranstaltung/$unknownSlug/storno?token=t"),
                Triple("storno POST", "POST", "/veranstaltung/$unknownSlug/storno"),
                Triple("zahlung GET", "GET", "/veranstaltung/$unknownSlug/zahlung?token=t"),
                Triple("zahlung POST", "POST", "/veranstaltung/$unknownSlug/zahlung"),
                Triple("ticket", "GET", "/veranstaltung/$unknownSlug/ticket?code=NOPE"),
                Triple("ticket.svg", "GET", "/veranstaltung/$unknownSlug/ticket.svg?code=NOPE"),
                Triple("ticket.pdf", "GET", "/veranstaltung/$unknownSlug/ticket.pdf?code=NOPE"),
            )

        suspend fun ApplicationTestBuilder.call(
            method: String,
            path: String,
        ): HttpResponse =
            if (method == "POST") {
                client.post(path) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("token=nope&guestName=&guestEmail=x")
                }
            } else {
                client.get(path)
            }

        fun String.withEmbed(): String = this + (if (contains('?')) "&" else "?") + "embed=1"

        fun robotsMetaCount(html: String): Int = Regex("name=\"robots\"").findAll(html).count()

        fun HttpResponse.isHtml(): Boolean = contentType()?.match(ContentType.Text.Html) == true

        test("every follow-up handler sets the noindex header and the HTML ones the noindex meta tag, in standard and embed mode") {
            withApp {
                for ((name, method, path) in followUps) {
                    for (embed in listOf(false, true)) {
                        val url = if (embed) path.withEmbed() else path
                        val response = call(method, url)
                        withClue("$name embed=$embed status=${response.status}") {
                            response.headers["X-Robots-Tag"] shouldBe noIndexValue
                            if (response.isHtml()) {
                                val html = response.bodyAsText()
                                html shouldContain "<meta name=\"robots\" content=\"$noIndexValue\">"
                                robotsMetaCount(html) shouldBe 1
                            }
                        }
                    }
                }
            }
        }

        test("the header and the meta tag also sit on 429 responses") {
            withApp(maxRequests = 0) {
                for ((name, method, path) in followUps) {
                    val response = call(method, path)
                    withClue(name) {
                        response.status shouldBe HttpStatusCode.TooManyRequests
                        response.headers["X-Robots-Tag"] shouldBe noIndexValue
                        if (response.isHtml()) response.bodyAsText() shouldContain "<meta name=\"robots\" content=\"$noIndexValue\">"
                    }
                }
            }
        }

        test("the thanks page (200) carries the noindex header and meta tag, and keeps CSP and Referrer-Policy") {
            withApp {
                val response = client.get("/veranstaltung/$unknownSlug/danke")
                response.status shouldBe HttpStatusCode.OK
                response.headers["X-Robots-Tag"] shouldBe noIndexValue
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
                (response.headers["Content-Security-Policy"] ?: "") shouldContain "frame-ancestors"
                response.bodyAsText() shouldContain "<meta name=\"robots\" content=\"$noIndexValue\">"
            }
        }

        test("invalid registration input (400) is noindex and still a 400") {
            withApp {
                val response = call("POST", "/veranstaltung/$unknownSlug/anmeldung")
                response.headers["X-Robots-Tag"] shouldBe noIndexValue
                response.bodyAsText() shouldContain "<meta name=\"robots\" content=\"$noIndexValue\">"
            }
        }

        test("negative: the .ics feed carries no noindex header") {
            withApp {
                client.get("/veranstaltung.ics").headers["X-Robots-Tag"] shouldBe null
            }
        }

        test("a 404 page of the detail route is itself noindex in the meta tag (non-200 pages are never index-worthy)") {
            withApp {
                val response = client.get("/veranstaltung/$unknownSlug")
                response.status shouldBe HttpStatusCode.NotFound
                val html = response.bodyAsText()
                html shouldContain "<meta name=\"robots\" content=\"$noIndexValue\">"
                robotsMetaCount(html) shouldBe 1
                response.headers["X-Robots-Tag"] shouldBe null
                html shouldNotContain "content=\"noindex\""
            }
        }
    })
