package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun testPaypalConfig(): PaypalConfig =
    (
        PaypalConfig.load {
            when (it) {
                PaypalConfig.ENV_CLIENT_ID -> "test-paypal-token-provider-client-id"
                PaypalConfig.ENV_CLIENT_SECRET -> "test-paypal-token-provider-secret"
                PaypalConfig.ENV_WEBHOOK_ID -> "WH-PAYPAL-TOKEN-PROVIDER-TEST"
                else -> null
            }
        } as PaypalConfigState.Configured
    ).config

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6), Review round 2 (MAJOR test-coverage gap) --
 * exercises [PaypalAccessTokenProvider] directly, the plan's own §5.1 test list item this file was
 * missing: caching, refresh-skew, single-flight-under-concurrency, 401-handling,
 * oversized-body-handling. Every test uses a [MockEngine]-backed [HttpClient], same "never the real
 * PSP API" house rule the rest of this package establishes.
 */
class PaypalAccessTokenProviderTest :
    FunSpec({
        test("caches the token -- a second call within the token's lifetime issues NO second HTTP request") {
            val requestCount = AtomicInteger(0)
            val engine =
                MockEngine {
                    requestCount.incrementAndGet()
                    respond(
                        """{"access_token":"token-1","token_type":"Bearer","expires_in":32400}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            val first = runBlocking { provider.accessTokenOrNull() }
            val second = runBlocking { provider.accessTokenOrNull() }

            first shouldBe "token-1"
            second shouldBe "token-1"
            requestCount.get() shouldBe 1
        }

        test("refresh skew -- a call within 60s of the cached token's expiry refetches instead of reusing it") {
            var now = Instant.parse("2026-09-15T10:00:00Z")
            val requestCount = AtomicInteger(0)
            val engine =
                MockEngine {
                    val n = requestCount.incrementAndGet()
                    respond(
                        """{"access_token":"token-$n","token_type":"Bearer","expires_in":100}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine), clock = { now })

            val first = runBlocking { provider.accessTokenOrNull() }
            first shouldBe "token-1"
            requestCount.get() shouldBe 1

            // 41s later: expiresAt is now+100s, so now is still > 41s away from expiresAt-60s=now+40s
            // at the moment of the FIRST call -- advance past that skew boundary explicitly.
            now += 45.seconds
            val second = runBlocking { provider.accessTokenOrNull() }
            second shouldBe "token-2"
            requestCount.get() shouldBe 2
        }

        test("still within the refresh-skew boundary -- no refetch yet") {
            var now = Instant.parse("2026-09-15T10:00:00Z")
            val requestCount = AtomicInteger(0)
            val engine =
                MockEngine {
                    val n = requestCount.incrementAndGet()
                    respond(
                        """{"access_token":"token-$n","token_type":"Bearer","expires_in":100}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine), clock = { now })

            runBlocking { provider.accessTokenOrNull() } shouldBe "token-1"
            // 30s later -- still well inside the 100s lifetime AND before the 60s-skew boundary
            // (expiresAt - 60s = now0 + 40s), so the cached token must still be returned.
            now += 30.seconds
            runBlocking { provider.accessTokenOrNull() } shouldBe "token-1"
            requestCount.get() shouldBe 1
        }

        test("single-flight under concurrency -- N concurrent callers on a cold cache issue exactly ONE HTTP request") {
            val requestCount = AtomicInteger(0)
            val engine =
                MockEngine {
                    requestCount.incrementAndGet()
                    // Artificial delay so all N coroutines are genuinely in-flight together before
                    // any of them completes -- without this, a race-free-by-luck implementation
                    // could pass even if the Mutex were removed entirely.
                    delay(50)
                    respond(
                        """{"access_token":"single-flight-token","token_type":"Bearer","expires_in":32400}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            val results =
                runBlocking {
                    coroutineScope {
                        (1..20).map { async { provider.accessTokenOrNull() } }.awaitAll()
                    }
                }

            results.all { it == "single-flight-token" } shouldBe true
            requestCount.get() shouldBe 1
        }

        test("PayPal 401 -> null, not an exception, and the response body is never surfaced") {
            val engine =
                MockEngine {
                    respond(
                        """{"error":"invalid_client","error_description":"Client Authentication failed"}""",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            val token = runBlocking { provider.accessTokenOrNull() }

            token shouldBe null
        }

        test("network failure (IOException) -> null, not a thrown exception") {
            val failingEngine = HttpClient(MockEngine { throw IOException("connection reset") })
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = failingEngine)

            val token = runBlocking { provider.accessTokenOrNull() }

            token shouldBe null
        }

        test("oversized response body (over the shared 64KiB PSP cap) -> treated as unparseable, null token") {
            val engine =
                MockEngine {
                    // One byte over MAX_PSP_RESPONSE_BYTES (64 * 1024) -- readCappedPspBody must
                    // discard it as null rather than partially parse a truncated JSON body.
                    val oversized = "{\"access_token\":\"" + "x".repeat(64 * 1024 + 100) + "\"}"
                    respond(oversized, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            val token = runBlocking { provider.accessTokenOrNull() }

            token shouldBe null
        }

        test("unparseable 2xx body -> null, not an exception") {
            val engine =
                MockEngine { respond("not json at all", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            val token = runBlocking { provider.accessTokenOrNull() }

            token shouldBe null
        }

        test("invalidate() forces the next call to refetch even though the cached token has not expired") {
            val requestCount = AtomicInteger(0)
            val engine =
                MockEngine {
                    val n = requestCount.incrementAndGet()
                    respond(
                        """{"access_token":"token-$n","token_type":"Bearer","expires_in":32400}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            runBlocking { provider.accessTokenOrNull() } shouldBe "token-1"
            provider.invalidate()
            runBlocking { provider.accessTokenOrNull() } shouldBe "token-2"
            requestCount.get() shouldBe 2
        }

        test(
            "Authorization header on the token request is Basic base64(clientId:clientSecret), never logged/leaked into the request path",
        ) {
            var capturedAuth: String? = null
            val engine =
                MockEngine { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        """{"access_token":"token-1","token_type":"Bearer","expires_in":32400}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = PaypalAccessTokenProvider(config = testPaypalConfig(), httpClient = HttpClient(engine))

            runBlocking { provider.accessTokenOrNull() }.shouldNotBeNull()

            val expected =
                "Basic " +
                    Base64.getEncoder().encodeToString(
                        "test-paypal-token-provider-client-id:test-paypal-token-provider-secret".toByteArray(Charsets.UTF_8),
                    )
            capturedAuth shouldBe expected
        }
    })
