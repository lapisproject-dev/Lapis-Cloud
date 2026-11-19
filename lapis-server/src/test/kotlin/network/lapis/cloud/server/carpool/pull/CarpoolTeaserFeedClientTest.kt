package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import network.lapis.cloud.server.carpool.feed.CARPOOL_TEASER_FEED_PATH
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedLimits
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.partner.FakeNetwork
import network.lapis.cloud.server.partner.fakeFetcherClientFactory
import network.lapis.cloud.server.partner.json
import network.lapis.cloud.server.partner.publicTargetFor
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** V1.9.314 W4 -- the ONE outgoing call of the carpool exchange: signed request, SSRF chain, status mapping, bounds. */
class CarpoolTeaserFeedClientTest :
    FunSpec({
        val own = OriginServer(host = PULL_OWN_HOST)
        val host = "a.beispiel.de"

        fun client(
            net: FakeNetwork = FakeNetwork(),
            unresolvable: Set<String> = emptySet(),
            timeout: Duration = 10.seconds,
            handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        ) = CarpoolTeaserFeedClient(
            resolveTarget = { h -> net.resolutions.incrementAndGet().let { if (h in unresolvable) null else publicTargetFor(h) } },
            clientFactory = fakeFetcherClientFactory(net = net, handler = handler),
            totalTimeout = timeout,
        )

        fun failed(o: CarpoolPullOutcome) = o.shouldBeInstanceOf<CarpoolPullOutcome.Failed>().error

        test(
            "the request is a signed GET of the constant path for the partner host: valid against the own public key, empty body, no query",
        ) {
            var seen: HttpRequestData? = null
            val c =
                client { request ->
                    seen = request
                    json(body = feedBytes(items = emptyList()), etag = "\"v1\"")
                }
            val outcome = c.fetch(host = host, etag = null, key = own.signing)
            outcome.shouldBeInstanceOf<CarpoolPullOutcome.Fetched>().etag shouldBe "\"v1\""
            val r = seen!!
            r.method shouldBe HttpMethod.Get
            r.url.encodedPath shouldBe CARPOOL_TEASER_FEED_PATH
            r.url.encodedQuery shouldBe ""
            r.headers[HttpHeaders.IfNoneMatch] shouldBe null
            HttpSignatures.verify(
                method = "GET",
                path = CARPOOL_TEASER_FEED_PATH,
                host = host,
                body = ByteArray(0),
                signatureHeader = r.headers["Signature"],
                dateHeader = r.headers[HttpHeaders.Date],
                digestHeader = r.headers["Digest"],
                publicKeyPem = own.keyPair.publicKeyPem,
            ) shouldBe HttpSignatures.VerificationResult.Valid
            // a signature made for ANOTHER host is not valid for this one (no replay towards a third server)
            HttpSignatures.verify(
                method = "GET",
                path = CARPOOL_TEASER_FEED_PATH,
                host = "c.beispiel.de",
                body = ByteArray(0),
                signatureHeader = r.headers["Signature"],
                dateHeader = r.headers[HttpHeaders.Date],
                digestHeader = r.headers["Digest"],
                publicKeyPem = own.keyPair.publicKeyPem,
            ) shouldNotBe HttpSignatures.VerificationResult.Valid
        }

        test("a known ETag is sent as If-None-Match, a 304 is NotModified") {
            var inm: String? = null
            val c =
                client { request ->
                    inm = request.headers[HttpHeaders.IfNoneMatch]
                    respond("", HttpStatusCode.NotModified)
                }
            c.fetch(host = host, etag = "\"abc\"", key = own.signing) shouldBe CarpoolPullOutcome.NotModified
            inm shouldBe "\"abc\""
        }

        test("the connection goes to the one pinned public address; a refused address never opens a connection") {
            val net = FakeNetwork()
            val c = client(net = net, unresolvable = setOf(host)) { respond("x") }
            failed(c.fetch(host = host, etag = null, key = own.signing)) shouldBe CarpoolPullErrorCode.NOT_PUBLIC_ADDRESS
            net.requests.get() shouldBe 0
            val net2 = FakeNetwork()
            client(net = net2) { json(body = feedBytes(items = emptyList())) }.fetch(host = host, etag = null, key = own.signing)
            net2.lastUrlHost shouldBe "93.184.216.34"
            net2.lastHostHeader shouldBe host
            net2.resolutions.get() shouldBe 1
        }

        test("the production resolver refuses the private, loopback and link-local ranges, IPv6 included") {
            listOf(
                "127.0.0.1",
                "169.254.169.254",
                "10.1.2.3",
                "192.168.0.1",
                "172.16.5.5",
                "100.64.1.1",
                "[::1]",
                "[fd00::1]",
                "[::ffff:127.0.0.1]",
            ).forEach { CarpoolTeaserFeedClient.productionResolve(it) shouldBe null }
        }

        test(
            "status mapping: 3xx REDIRECT (never followed), 404 UNAVAILABLE, 429 RATE_LIMITED, other HTTP_STATUS, text/html WRONG_CONTENT_TYPE",
        ) {
            val net = FakeNetwork()
            failed(
                client(net = net) { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://127.0.0.1/x")) }
                    .fetch(host = host, etag = null, key = own.signing),
            ) shouldBe CarpoolPullErrorCode.REDIRECT
            net.requests.get() shouldBe 1
            failed(
                client { json(body = ByteArray(0), status = HttpStatusCode.NotFound) }.fetch(host = host, etag = null, key = own.signing),
            ) shouldBe
                CarpoolPullErrorCode.UNAVAILABLE
            failed(
                client {
                    json(body = ByteArray(0), status = HttpStatusCode.TooManyRequests)
                }.fetch(host = host, etag = null, key = own.signing),
            ) shouldBe
                CarpoolPullErrorCode.RATE_LIMITED
            failed(
                client {
                    json(body = ByteArray(0), status = HttpStatusCode.InternalServerError)
                }.fetch(host = host, etag = null, key = own.signing),
            ) shouldBe
                CarpoolPullErrorCode.HTTP_STATUS
            failed(
                client {
                    respond(
                        "<html/>",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }.fetch(host = host, etag = null, key = own.signing),
            ) shouldBe CarpoolPullErrorCode.WRONG_CONTENT_TYPE
        }

        test("the body is capped at the shared feed limit, declared or streamed") {
            val big = ByteArray(CarpoolTeaserFeedLimits.MAX_FEED_BYTES + 10) { ' '.code.toByte() }
            failed(client { json(body = big) }.fetch(host = host, etag = null, key = own.signing)) shouldBe CarpoolPullErrorCode.TOO_LARGE
            failed(
                client {
                    respond(
                        content = big,
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.ContentLength to listOf(big.size.toString()),
                            ),
                    )
                }.fetch(host = host, etag = null, key = own.signing),
            ) shouldBe CarpoolPullErrorCode.TOO_LARGE
        }

        test("the whole call is bounded in time") {
            val c =
                client(timeout = 200.milliseconds) {
                    delay(5000)
                    json(body = feedBytes(items = emptyList()))
                }
            failed(c.fetch(host = host, etag = null, key = own.signing)) shouldBe CarpoolPullErrorCode.TIMEOUT
        }

        test("an ETag with control characters or an absurd length is not sent") {
            var inm: String? = "unset"
            val c =
                client { request ->
                    inm = request.headers[HttpHeaders.IfNoneMatch]
                    json(body = feedBytes(items = emptyList()))
                }
            c.fetch(host = host, etag = "\"a\u0001b\"", key = own.signing)
            inm shouldBe null
            c.fetch(host = host, etag = "\"" + "x".repeat(300) + "\"", key = own.signing)
            inm shouldBe null
        }
    })
