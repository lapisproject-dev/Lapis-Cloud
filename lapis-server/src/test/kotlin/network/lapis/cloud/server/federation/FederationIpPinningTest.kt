package network.lapis.cloud.server.federation

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TLS_TEST_KEYSTORE_PASSWORD = "changeit-lapis-cloud-test-only"
private const val TLS_TEST_HOSTNAME = "federation-test.lapisproject.invalid"
private const val TLS_TEST_WRONG_HOSTNAME = "wrong-host.lapisproject.invalid"

/**
 * DNS-rebinding-fix test suite (`feature/dns-rebinding-fix`) for
 * [requireSafeFederationUrl]/[federationHttpClient]/[FederationIpPinningPlugin]. Three groups, see
 * each `context` block's own KDoc-style comment:
 *
 * - **T1**: proves the DNS-rebinding attack PRECONDITION is real in this test environment (a
 *   hostname resolving to a different address on successive lookups) using
 *   [RebindingSimulationInetAddressResolverProvider] -- establishing the threat is real before
 *   proving the fix defeats it.
 * - **T2**: proves [FederationIpPinningPlugin] rewrites the outgoing request to the PINNED address
 *   captured at [requireSafeFederationUrl] time, never re-resolving, via a [MockEngine]-backed
 *   client (application-level contract; the "CIO itself performs zero DNS lookups for a literal
 *   IP" half of the guarantee is a JDK/Ktor-source fact, verified directly against the actual
 *   pinned Ktor 3.5.1 sources -- see [FederationIpPinningPlugin] KDoc -- not something a
 *   [MockEngine] test can observe).
 * - **T3**: the requirement-3 centerpiece -- a REAL CIO+TLS connection, through the ACTUAL
 *   [FederationIpPinningPlugin] (not a hand-rolled lookalike -- the request is built against the
 *   ORIGINAL hostname, exactly like every real call site's `client.get(actorUri)`, and the plugin
 *   itself performs the rewrite to the pinned loopback IP before the engine connects), proving
 *   hostname verification still runs against the ORIGINAL hostname (SNI) and that a
 *   certificate/hostname mismatch is still correctly rejected, not silently bypassed by the
 *   IP-pinning rewrite.
 */
class FederationIpPinningTest :
    FunSpec({

        // ── T1: the rebinding-attack precondition is real in this test environment ──────────

        test("T1: the rebinding-simulation resolver returns a DIFFERENT address on successive lookups") {
            val first = requireSafeFederationUrl("https://$REBINDING_SIMULATION_HOSTNAME/actor")
            val second = requireSafeFederationUrl("https://$REBINDING_SIMULATION_HOSTNAME/actor")

            // Same hostname, same call, two different physical addresses -- exactly the DNS-rebinding
            // attack precondition (a malicious resolver answering differently on successive lookups).
            // Both addresses individually pass requireSafeFederationUrl's safety check (TEST-NET-3,
            // RFC 5737, is not loopback/link-local/site-local/multicast/any-local to java.net's own
            // isXxx() checks) -- this is what makes the scenario realistic: an attacker-controlled
            // resolver answering with public-looking addresses at check-time, not something the
            // safety check itself would reject.
            first.pinnedAddress shouldNotBe second.pinnedAddress
            first.originalHost shouldBe REBINDING_SIMULATION_HOSTNAME
            second.originalHost shouldBe REBINDING_SIMULATION_HOSTNAME
        }

        // ── T2: FederationIpPinningPlugin uses the CAPTURED address, never re-resolves ───────

        test("T2: the plugin rewrites the request to the PINNED address even though a fresh lookup right now would differ") {
            // Capture a target ONCE, the same way every real call site does.
            val target = requireSafeFederationUrl("https://$REBINDING_SIMULATION_HOSTNAME/actor")

            // Prove a lookup happening "right now" (concurrently with the pinned client's request)
            // would return something else -- if the plugin were re-resolving instead of using the
            // captured target, this is the address it would wrongly connect to.
            val freshLookupRightNow = InetAddress.getAllByName(REBINDING_SIMULATION_HOSTNAME).first()
            freshLookupRightNow shouldNotBe target.pinnedAddress

            var observedHost: String? = null
            var observedHostHeader: String? = null

            fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
                HttpClient(MockEngine(handler)) {
                    install(FederationIpPinningPlugin) { this.target = target }
                }

            val client =
                mockClient { request ->
                    observedHost = request.url.host
                    observedHostHeader = request.headers[HttpHeaders.Host]
                    respond("ok", HttpStatusCode.OK)
                }

            client.get("https://${target.originalHost}/actor")

            observedHost shouldBe target.pinnedAddress.hostAddress
            observedHostHeader shouldBe target.originalHost
        }

        test("T2b: the plugin refuses to be reused for a different host than it was built for") {
            val target = requireSafeFederationUrl("https://$REBINDING_SIMULATION_HOSTNAME/actor")

            fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
                HttpClient(MockEngine(handler)) {
                    install(FederationIpPinningPlugin) { this.target = target }
                }

            val client = mockClient { respond("ok", HttpStatusCode.OK) }

            val result = runCatching { client.get("https://some-other-host.lapisproject.invalid/actor") }
            result.isFailure shouldBe true
        }

        test("T2c: an ordinary request with no explicit port gets a bare Host header, not 'host:0'") {
            // Regression test for a real bug found live while writing this suite: HttpRequestBuilder.url
            // is a mutable URLBuilder, whose .port stays the raw DEFAULT_PORT sentinel (0) for an
            // unspecified port -- unlike the immutable Url.port getter, which normalizes 0 to the
            // protocol's default port. The plugin's Host-header logic must replicate that
            // normalization itself; without it, every ordinary federation request (no call site ever
            // sets an explicit port) would get an incorrect "host:0" Host header.
            val target = requireSafeFederationUrl("https://$REBINDING_SIMULATION_HOSTNAME/actor")
            var observedHostHeader: String? = null

            val client =
                HttpClient(
                    MockEngine { request ->
                        observedHostHeader = request.headers[HttpHeaders.Host]
                        respond("ok", HttpStatusCode.OK)
                    },
                ) {
                    install(FederationIpPinningPlugin) { this.target = target }
                }

            client.get("https://${target.originalHost}/actor")

            observedHostHeader shouldBe target.originalHost
        }

        test("T2d: a request with an explicit non-default port gets 'host:port' in the Host header") {
            val target = requireSafeFederationUrl("https://$REBINDING_SIMULATION_HOSTNAME:8443/actor")
            var observedHostHeader: String? = null

            val client =
                HttpClient(
                    MockEngine { request ->
                        observedHostHeader = request.headers[HttpHeaders.Host]
                        respond("ok", HttpStatusCode.OK)
                    },
                ) {
                    install(FederationIpPinningPlugin) { this.target = target }
                }

            client.get("https://${target.originalHost}:8443/actor")

            observedHostHeader shouldBe "${target.originalHost}:8443"
        }

        // ── T3: real TLS through the ACTUAL plugin -- SNI/hostname verification against the ────
        // ── ORIGINAL host, not the IP -- not a hand-rolled lookalike client ────────────────────

        test(
            "T3: a self-signed cert covering the SNI'd hostname is accepted even though FederationIpPinningPlugin rewrites the socket target to a loopback IP",
        ) {
            withSelfSignedHttpsServer(hostname = TLS_TEST_HOSTNAME) { port, trustManager ->
                val target = SafeFederationTarget(originalHost = TLS_TEST_HOSTNAME, pinnedAddress = InetAddress.getByName("127.0.0.1"))
                val client = federationLikeTestClient(target = target, trustManager = trustManager)
                try {
                    // Built against the ORIGINAL hostname + the test server's real port, exactly like
                    // every production call site's `client.get(actorUri)` -- FederationIpPinningPlugin
                    // (installed for real below, not mocked) is what rewrites `request.url.host` to
                    // the pinned loopback IP before the engine connects; engine.https.serverName stays
                    // pinned to the original hostname for SNI/certificate verification.
                    val response = client.get("https://${target.originalHost}:$port/")
                    response.status shouldBe HttpStatusCode.OK
                } finally {
                    client.close()
                }
            }
        }

        test("T3b: a hostname NOT covered by the certificate is still rejected -- IP-pinning does not weaken TLS verification") {
            withSelfSignedHttpsServer(hostname = TLS_TEST_HOSTNAME) { port, trustManager ->
                val target =
                    SafeFederationTarget(originalHost = TLS_TEST_WRONG_HOSTNAME, pinnedAddress = InetAddress.getByName("127.0.0.1"))
                val client = federationLikeTestClient(target = target, trustManager = trustManager)
                try {
                    val result = runCatching { client.get("https://${target.originalHost}:$port/") }
                    result.isFailure shouldBe true
                } finally {
                    client.close()
                }
            }
        }
    })

/**
 * Builds a self-signed [HashAlgorithm.SHA256]/[SignatureAlgorithm.RSA] certificate for [hostname]
 * via Ktor's own first-party test-certificate generator (`buildKeyStore`, no Bouncy Castle needed),
 * starts a plain-JDK [HttpsServer] on `127.0.0.1:0` (ephemeral port) presenting that certificate,
 * runs [block] with the bound port and an [X509TrustManager] that trusts (only) this certificate,
 * and always stops the server afterward.
 */
private suspend fun withSelfSignedHttpsServer(
    hostname: String,
    block: suspend (port: Int, trustManager: X509TrustManager) -> Unit,
) {
    val keyStore =
        buildKeyStore {
            certificate("server") {
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = 2048
                password = TLS_TEST_KEYSTORE_PASSWORD
                domains = listOf(hostname)
            }
        }

    val keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, TLS_TEST_KEYSTORE_PASSWORD.toCharArray())
        }
    val serverSslContext =
        SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }

    val trustStore =
        java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("server", keyStore.getCertificate("server"))
        }
    val trustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                init(trustStore)
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    val server =
        HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverSslContext)
            createContext("/") { exchange ->
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    try {
        block(server.address.port, trustManager)
    } finally {
        server.stop(0)
    }
}

/**
 * [federationHttpClient]'s CIO+TLS-SNI+[FederationIpPinningPlugin] shape, built and installed for
 * real (not mocked, not hand-rolled) -- the ONLY difference from [federationHttpClient] itself is
 * an injected [trustManager] (federationHttpClient has none -- production federation targets
 * present publicly-CA-trusted certificates, so no custom trust store is needed there; this test's
 * self-signed certificate needs one, which is why this can't just call [federationHttpClient]
 * directly). Every other line mirrors [federationHttpClient] exactly, including installing the
 * ACTUAL [FederationIpPinningPlugin] -- so this test exercises the real production rewrite-and-SNI
 * mechanism end to end against a real CIO+TLS connection, not a claim about it.
 */
private fun federationLikeTestClient(
    target: SafeFederationTarget,
    trustManager: X509TrustManager,
): HttpClient =
    HttpClient(CIO) {
        engine {
            https {
                serverName = target.originalHost
                this.trustManager = trustManager
            }
        }
        install(FederationIpPinningPlugin) { this.target = target }
        expectSuccess = false
        followRedirects = false
    }
