package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import network.lapis.cloud.server.federation.REBINDING_SIMULATION_HOSTNAME
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private val OWN_ID = Uuid.parse("22222222-2222-2222-2222-222222222222")

class PartnerFeedFetcherSsrfTest :
    FunSpec({
        suspend fun PartnerFeedFetcher.run(
            host: String = PARSER_HOST,
            etag: String? = null,
        ) = fetch(host = host, etag = etag, ownServerId = OWN_ID, knownRemoteId = null, now = PARSER_NOW)

        fun failed(outcome: PartnerFetchOutcome) = outcome.shouldBeInstanceOf<PartnerFetchOutcome.Failed>().error

        // ── address guard (the production resolver, NO fake) ───────────────────────────────────────────

        test("the production resolver refuses loopback, link-local, RFC 1918, CGNAT, ULA, NAT64, 6to4, Teredo, mapped, 0/8 and 240/4") {
            listOf(
                "127.0.0.1",
                "169.254.169.254",
                "10.1.2.3",
                "192.168.0.1",
                "172.16.5.5",
                "100.64.1.1",
                "0.0.0.0",
                "240.0.0.1",
                "[::1]",
                "[fd00::1]",
                "[64:ff9b::7f00:1]",
                "[2002:7f00:1::1]",
                "[2001:0:4136:e378:8000:63bf:3fff:fdd2]",
                "[::ffff:127.0.0.1]",
                "[::ffff:10.0.0.1]",
            ).forEach { host -> PartnerFeedFetcher.productionResolve(host) shouldBe null }
        }

        test("a name that does not resolve is not public either") {
            PartnerFeedFetcher.productionResolve("this-host-does-not-exist.invalid") shouldBe null
        }

        test("the fetcher maps a refused address to NOT_PUBLIC_ADDRESS and never opens a connection") {
            val net = FakeNetwork()
            val fetcher = fakeFetcher(net = net, unresolvable = setOf(PARSER_HOST)) { respond("x") }
            failed(fetcher.run()) shouldBe PartnerFetchErrorCode.NOT_PUBLIC_ADDRESS
            net.requests.get() shouldBe 0
        }

        // ── DNS rebinding: one resolution, one connection to exactly that address ─────────────────────

        test("DNS rebinding: the connection goes to the pinned address of the ONE resolution, with the original Host header") {
            val net = FakeNetwork()
            val resolved = PartnerFeedFetcher.productionResolve(REBINDING_SIMULATION_HOSTNAME)!!
            val fetcher =
                PartnerFeedFetcher(
                    resolveTarget = { net.resolutions.incrementAndGet().let { resolved } },
                    clientFactory = fakeFetcherClientFactory(net = net) { json(body = feedJson(), etag = "\"v1\"") },
                )
            val outcome = fetcher.run(host = REBINDING_SIMULATION_HOSTNAME)
            // the feed validates its URLs against the host; this one uses another host -> events dropped, but the transport is what is tested
            outcome.shouldBeInstanceOf<PartnerFetchOutcome.Fetched>()
            net.resolutions.get() shouldBe 1
            net.lastUrlHost shouldBe resolved.pinnedAddress.hostAddress
            net.lastHostHeader shouldBe REBINDING_SIMULATION_HOSTNAME
            // a fresh lookup right now would answer differently -- exactly the attack the pin defeats
            InetAddress.getAllByName(REBINDING_SIMULATION_HOSTNAME).first() shouldNotBe resolved.pinnedAddress
        }

        test(
            "the request goes to the pinned address with the partner host as Host header and sends If-None-Match only when an ETag is known",
        ) {
            val net = FakeNetwork()
            var ifNoneMatch: String? = "unset"
            val fetcher =
                fakeFetcher(net = net) { request ->
                    ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
                    json(body = feedJson())
                }
            fetcher.run(etag = "\"abc\"").shouldBeInstanceOf<PartnerFetchOutcome.Fetched>()
            ifNoneMatch shouldBe "\"abc\""
            net.lastUrlHost shouldBe "93.184.216.34"
            net.lastHostHeader shouldBe PARSER_HOST
            fetcher.run(etag = null)
            ifNoneMatch shouldBe null
        }

        // ── redirects, size, type, time, status ───────────────────────────────────────────────────────

        test("a redirect is never followed: REDIRECT and exactly one request") {
            val net = FakeNetwork()
            val fetcher =
                fakeFetcher(net = net) {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://127.0.0.1/internal"))
                }
            failed(fetcher.run()) shouldBe PartnerFetchErrorCode.REDIRECT
            net.requests.get() shouldBe 1
        }

        test("a declared Content-Length over 512 KiB is TOO_LARGE at once") {
            val body = ByteArray(614400) { ' '.code.toByte() }
            val fetcher =
                fakeFetcher {
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.ContentLength to listOf("614400"),
                            ),
                    )
                }
            failed(fetcher.run()) shouldBe PartnerFetchErrorCode.TOO_LARGE
        }

        test("a streamed body over 512 KiB without a length is TOO_LARGE") {
            val big = ByteArray(PartnerFeedFetcher.MAX_BODY_BYTES + 10) { ' '.code.toByte() }
            val fetcher = fakeFetcher { json(body = big) }
            failed(fetcher.run()) shouldBe PartnerFetchErrorCode.TOO_LARGE
        }

        test("text/html is WRONG_CONTENT_TYPE, a 500 is HTTP_STATUS, garbage JSON is INVALID_FEED") {
            failed(
                fakeFetcher { respond("<html/>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }.run(),
            ) shouldBe PartnerFetchErrorCode.WRONG_CONTENT_TYPE
            failed(fakeFetcher { json(body = "{}".toByteArray(), status = HttpStatusCode.InternalServerError) }.run()) shouldBe
                PartnerFetchErrorCode.HTTP_STATUS
            failed(fakeFetcher { json(body = "not json".toByteArray()) }.run()) shouldBe PartnerFetchErrorCode.INVALID_FEED
        }

        test("a body that trickles in slower than the total budget is TIMEOUT") {
            val fetcher =
                fakeFetcher(totalTimeout = 300.milliseconds) {
                    delay(5_000)
                    json(body = feedJson())
                }
            failed(fetcher.run()) shouldBe PartnerFetchErrorCode.TIMEOUT
        }

        test("304 with a stored ETag is NotModified") {
            val fetcher = fakeFetcher { respond("", HttpStatusCode.NotModified) }
            fetcher.run(etag = "\"abc\"") shouldBe PartnerFetchOutcome.NotModified
        }

        test("the fetcher reports our own server id and a changed id as codes") {
            failed(
                fakeFetcher { json(body = feedJson(serverId = OWN_ID)) }.run(),
            ) shouldBe PartnerFetchErrorCode.OWN_SERVER_ID
            val changed =
                fakeFetcher { json(body = feedJson()) }.fetch(
                    host = PARSER_HOST,
                    etag = null,
                    ownServerId = OWN_ID,
                    knownRemoteId = Uuid.random(),
                    now = PARSER_NOW,
                )
            failed(changed) shouldBe PartnerFetchErrorCode.SERVER_ID_CHANGED
        }
    })
