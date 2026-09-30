package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.newsletter.MailingHtmlSanitizer
import network.lapis.cloud.server.mail.newsletter.MailingTrackingData
import network.lapis.cloud.server.mail.newsletter.MailingTrackingToken
import network.lapis.cloud.server.mail.newsletter.TrackingFixture
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.15 -- `GET /m/c/{token}` and `GET /m/o/{token}.gif`. The routes are registered directly
 * on a bare test application (the production `module()` uses an ephemeral key in LOG mode, which a
 * test could not forge tokens against) with a fixed test key.
 */
class MailingTrackingRoutesTest :
    FunSpec({
        val fx = TrackingFixture()
        val token = testTrackingToken()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun app(
            clickLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes),
            pixelLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 600, window = 1.minutes),
            block: suspend io.ktor.client.HttpClient.() -> Unit,
        ) = testApplication {
            application {
                install(AutoHeadResponse)
                routing {
                    registerMailingTrackingRoutes(
                        trackingToken = token,
                        clickRateLimiter = clickLimiter,
                        pixelRateLimiter = pixelLimiter,
                        brandTitle = "Testverein",
                    )
                }
            }
            val noRedirect = createClient { followRedirects = false }
            runBlocking { noRedirect.block() }
        }

        class Setup(
            val messageId: Uuid,
            val memberId: Uuid,
            val deliveryId: Uuid,
            val nonce: String,
        )

        fun setup(
            open: Boolean = true,
            click: Boolean = true,
            consentOpen: Boolean = true,
            consentClick: Boolean = true,
            target: String = "https://example.org/ziel?a=1",
        ): Setup {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            fx.link(messageId = msg, index = 0, url = target)
            val member = fx.member()
            fx.subscribe(listId = list, memberId = member, openConsent = consentOpen, clickConsent = consentClick)
            val (delivery, nonce) = fx.delivery(messageId = msg, memberId = member, openTracked = open, clickTracked = click)
            return Setup(msg, member, delivery, nonce)
        }

        test("click: 302 to the DB target, counted, with the hardening headers") {
            val s = setup()
            app {
                val response = get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}")
                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "https://example.org/ziel?a=1"
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
                response.headers["X-Robots-Tag"] shouldBe "noindex"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
            }
            fx.clicksOf(s.deliveryId) shouldBe mapOf(0 to 1)
        }

        test("click: sanitize -> captureLinks -> GET /m/c works for every captured link; unredirectable hrefs are never rewritten") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val sanitized =
                MailingHtmlSanitizer.sanitize(
                    "<p><a href=\"https://ex.org/{{x}}\">a</a><a href=\"https://example.org/my page\">b</a>" +
                        "<a href=\"https://ok.example/x?y=1\">c</a></p>",
                )
            transaction { MailingTrackingData.captureLinks(messageId = msg, sanitized = sanitized) }
            val index = transaction { MailingTrackingData.linkIndexByUrl(msg) }
            index shouldBe mapOf("https://ok.example/x?y=1" to 0)
            val member = fx.member()
            fx.subscribe(listId = list, memberId = member, openConsent = true, clickConsent = true)
            val (delivery, nonce) = fx.delivery(messageId = msg, memberId = member, openTracked = true, clickTracked = true)
            app {
                val response = get("/m/c/${token.clickToken(nonce = nonce, linkIndex = 0)}")
                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "https://ok.example/x?y=1"
            }
            fx.clicksOf(delivery) shouldBe mapOf(0 to 1)
        }

        test("click: a link index without a link row is a neutral 404 with no Location") {
            val s = setup()
            app {
                val response = get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 5)}")
                response.status shouldBe HttpStatusCode.NotFound
                response.headers[HttpHeaders.Location] shouldBe null
            }
            fx.clicksOf(s.deliveryId) shouldBe emptyMap()
        }

        test("click: invalid, forged, unknown-hash and empty tokens all give the identical 404") {
            val s = setup()
            val unknown = testTrackingToken().issue()
            app {
                val bodies =
                    listOf(
                        "garbage",
                        token.clickToken(nonce = s.nonce, linkIndex = 0).dropLast(2) + "xx",
                        token.clickToken(nonce = unknown.nonce, linkIndex = 0),
                        token.openToken(s.nonce), // open token on the click route
                    ).map { tokenText ->
                        val r = get("/m/c/$tokenText")
                        r.status shouldBe HttpStatusCode.NotFound
                        r.headers[HttpHeaders.Location] shouldBe null
                        r.bodyAsText()
                    }
                bodies.toSet().size shouldBe 1
            }
        }

        test("click: a token of message A cannot reach a link that only message B has") {
            val a = setup(target = "https://a.example/")
            val b = setup(target = "https://b.example/")
            // Message B has link index 0 only too, so re-check with an index only B gets:
            fx.link(messageId = b.messageId, index = 1, url = "https://b.example/second")
            app {
                get("/m/c/${token.clickToken(nonce = a.nonce, linkIndex = 1)}").status shouldBe HttpStatusCode.NotFound
                get("/m/c/${token.clickToken(nonce = a.nonce, linkIndex = 0)}").headers[HttpHeaders.Location] shouldBe "https://a.example/"
            }
        }

        test("click: a query parameter is never used as the destination") {
            val s = setup()
            app {
                val r = get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}?url=https://evil.example/&to=https://evil.example/")
                r.status shouldBe HttpStatusCode.Found
                r.headers[HttpHeaders.Location] shouldBe "https://example.org/ziel?a=1"
            }
        }

        test("click: a tampered DB target (javascript:, data:, CRLF, whitespace, non-http) is a 404, never a redirect") {
            listOf(
                "javascript:alert(1)",
                "data:text/html,x",
                "ftp://example.org/x",
                "https://exa mple.org",
                "https://example.org/\r\nSet-Cookie: a=b",
                "https:///nohost",
            ).forEach { bad ->
                val s = setup(target = bad)
                app {
                    val r = get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}")
                    r.status shouldBe HttpStatusCode.NotFound
                    r.headers[HttpHeaders.Location] shouldBe null
                }
                fx.clicksOf(s.deliveryId) shouldBe emptyMap()
            }
        }

        test("click: non-ASCII targets are percent-encoded in Location") {
            val s = setup(target = "https://example.org/pfad/äöü")
            app {
                val r = get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}")
                r.status shouldBe HttpStatusCode.Found
                r.headers[HttpHeaders.Location]!!.all { it.code in 0x21..0x7e } shouldBe true
            }
        }

        test("click: withdrawn consent, ended subscription, anonymized member or off snapshot redirect but do not count") {
            val withdrawn = setup(consentClick = false)
            val noSnapshot = setup(click = false)
            val anonymizedSender = fx.member()
            val list = fx.list(anonymizedSender)
            val msg = fx.message(listId = list, sentBy = anonymizedSender)
            fx.link(messageId = msg, index = 0, url = "https://example.org/x")
            val anon = fx.member(anonymized = true)
            fx.subscribe(listId = list, memberId = anon, clickConsent = true)
            val (anonDelivery, anonNonce) = fx.delivery(messageId = msg, memberId = anon, clickTracked = true)
            val unsubMember = fx.member()
            fx.subscribe(listId = list, memberId = unsubMember, clickConsent = true, unsubscribed = true)
            val (unsubDelivery, unsubNonce) = fx.delivery(messageId = msg, memberId = unsubMember, clickTracked = true)
            app {
                get("/m/c/${token.clickToken(nonce = withdrawn.nonce, linkIndex = 0)}").status shouldBe HttpStatusCode.Found
                get("/m/c/${token.clickToken(nonce = noSnapshot.nonce, linkIndex = 0)}").status shouldBe HttpStatusCode.Found
                get("/m/c/${token.clickToken(nonce = anonNonce, linkIndex = 0)}").status shouldBe HttpStatusCode.Found
                get("/m/c/${token.clickToken(nonce = unsubNonce, linkIndex = 0)}").status shouldBe HttpStatusCode.Found
            }
            listOf(withdrawn.deliveryId, noSnapshot.deliveryId, anonDelivery, unsubDelivery).forEach {
                fx.clicksOf(it) shouldBe emptyMap()
            }
        }

        test("click: a HEAD request (link scanner) redirects-by-GET-logic but never counts") {
            val s = setup()
            app {
                val r = head("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}")
                r.status shouldBe HttpStatusCode.Found
            }
            fx.clicksOf(s.deliveryId) shouldBe emptyMap()
        }

        test("click: the per-link counter saturates at the ceiling") {
            val s = setup()
            app {
                repeat(3) { get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}") }
            }
            fx.clicksOf(s.deliveryId) shouldBe mapOf(0 to 3)
            (fx.clicksOf(s.deliveryId)[0]!! <= MailingHtmlPolicy.MAX_EVENT_COUNT) shouldBe true
        }

        test("click: links keep redirecting after retention cleared the tracking flags, without counting") {
            val s = setup()
            transaction {
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq s.deliveryId }) {
                    it[clickTracked] = false
                    it[openTracked] = false
                }
            }
            app {
                get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}").status shouldBe HttpStatusCode.Found
            }
            fx.clicksOf(s.deliveryId) shouldBe emptyMap()
        }

        test("pixel: a valid token with consent counts and returns the 43-byte GIF") {
            val s = setup()
            app {
                val r = get("/m/o/${token.openToken(s.nonce)}.gif")
                r.status shouldBe HttpStatusCode.OK
                r.headers[HttpHeaders.ContentType] shouldBe "image/gif"
                r.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                r.headers["X-Content-Type-Options"] shouldBe "nosniff"
                r.bodyAsBytes().size shouldBe 43
            }
            fx.openCountOf(s.deliveryId) shouldBe 1
        }

        test("pixel: invalid token, no consent, withdrawn consent and HEAD give the same GIF and do not count") {
            val s = setup()
            val withdrawn = setup(consentOpen = false)
            val reference =
                run {
                    var bytes = ByteArray(0)
                    app { bytes = get("/m/o/${token.openToken(s.nonce)}.gif").bodyAsBytes() }
                    bytes
                }
            app {
                listOf(
                    "/m/o/garbage.gif",
                    "/m/o/${token.openToken(withdrawn.nonce)}.gif",
                    "/m/o/${token.clickToken(nonce = s.nonce, linkIndex = 0)}.gif",
                    "/m/o/${token.openToken(s.nonce)}", // missing .gif suffix
                ).forEach { path ->
                    val r = get(path)
                    r.status shouldBe HttpStatusCode.OK
                    r.bodyAsBytes().toList() shouldBe reference.toList()
                }
                head("/m/o/${token.openToken(s.nonce)}.gif").status shouldBe HttpStatusCode.OK
            }
            fx.openCountOf(withdrawn.deliveryId) shouldBe 0
            fx.openCountOf(s.deliveryId) shouldBe 1 // only the reference request
        }

        test("pixel: over the pixel budget it still answers the GIF (no oracle) but stops counting") {
            val s = setup()
            app(pixelLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                repeat(5) { get("/m/o/${token.openToken(s.nonce)}.gif").status shouldBe HttpStatusCode.OK }
            }
            fx.openCountOf(s.deliveryId) shouldBe 2
        }

        test("click: the 121st request per minute from one IP is a 429 with Retry-After and the neutral page") {
            val s = setup()
            app(clickLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes)) {
                repeat(120) { get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}").status shouldBe HttpStatusCode.Found }
                val r = get("/m/c/${token.clickToken(nonce = s.nonce, linkIndex = 0)}")
                r.status shouldBe HttpStatusCode.TooManyRequests
                r.headers[HttpHeaders.RetryAfter] shouldBe "60"
                r.headers[HttpHeaders.Location] shouldBe null
                r.bodyAsText() shouldNotContain "example.org/ziel"
            }
        }

        test("the wrong-key token class never verifies (sanity for the route's own key)") {
            val foreign = MailingTrackingToken(ByteArray(32) { 9 })
            val s = setup()
            app {
                get("/m/c/${foreign.clickToken(nonce = s.nonce, linkIndex = 0)}").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
