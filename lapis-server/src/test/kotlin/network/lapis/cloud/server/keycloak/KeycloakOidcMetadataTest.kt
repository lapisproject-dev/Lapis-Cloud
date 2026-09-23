package network.lapis.cloud.server.keycloak

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.OidcJwks
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val ISSUER = "https://keycloak.example.org/realms/lapis"
private const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
private const val JWKS_URL = "$ISSUER/protocol/openid-connect/certs"
private const val KID = "test-key-1"

private fun testConfig(env: Map<String, String> = emptyMap()): KeycloakConfig {
    val base =
        mapOf(
            KeycloakConfig.ENV_ENABLED to "true",
            KeycloakConfig.ENV_ISSUER_URL to ISSUER,
            KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
            KeycloakConfig.ENV_CLIENT_SECRET to "secret",
        ) + env
    return KeycloakConfig.load { base[it] }
}

private fun discoveryJson(issuer: String = ISSUER): String =
    """{"issuer":"$issuer","authorization_endpoint":"$ISSUER/auth","token_endpoint":"$ISSUER/token","jwks_uri":"$JWKS_URL"}"""

private fun jwksJsonWithKid(kid: String = KID): String {
    val keyPair = FederationKeyPairGenerator.generate()
    return OidcJwks.buildJwksJson(publicKeyPem = keyPair.publicKeyPem, kid = kid)
}

class KeycloakOidcMetadataTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
            HttpClient(MockEngine(handler)) {
                install(HttpTimeout) {}
                expectSuccess = false
                followRedirects = false
            }

        fun MockRequestHandleScope.jsonResponse(body: String) =
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

        test("issuer mismatch in the fetched discovery document is rejected, never cached") {
            val client = mockClient { jsonResponse(discoveryJson(issuer = "https://evil.example.org")) }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.discoveryDocument().shouldBeNull()
        }

        test("a matching issuer is accepted") {
            val client = mockClient { jsonResponse(discoveryJson()) }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            val doc = metadata.discoveryDocument()

            doc.shouldNotBeNull()
            doc.issuer shouldBe ISSUER
            doc.jwks_uri shouldBe JWKS_URL
        }

        test("a known kid is resolved from the cached JWKS without a second fetch") {
            val jwks = jwksJsonWithKid()
            val callCount = AtomicInteger(0)
            val client =
                mockClient { request ->
                    callCount.incrementAndGet()
                    if (request.url.toString() == DISCOVERY_URL) jsonResponse(discoveryJson()) else jsonResponse(jwks)
                }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.publicKeyPem(KID).shouldNotBeNull()
            metadata.publicKeyPem(KID).shouldNotBeNull()

            // 1 discovery fetch + 1 jwks fetch, both served from cache afterwards.
            callCount.get() shouldBe 2
        }

        test("an unknown kid triggers exactly one forced refetch, then fails -- and the refetch cooldown is honoured") {
            val jwks = jwksJsonWithKid(kid = "some-other-kid")
            var jwksFetches = 0
            var fixedNow = Instant.parse("2026-01-01T00:00:00Z")
            val client =
                mockClient { request ->
                    if (request.url.toString() == DISCOVERY_URL) {
                        jsonResponse(discoveryJson())
                    } else {
                        jwksFetches++
                        jsonResponse(jwks)
                    }
                }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client, now = { fixedNow })

            // First lookup: initial jwks fetch (miss) + one forced refetch for the unknown kid == 2 jwks fetches.
            metadata.publicKeyPem(KID).shouldBeNull()
            jwksFetches shouldBe 2

            // Second lookup, still within the cooldown window and within the jwks cache TTL: no further fetches at all.
            metadata.publicKeyPem(KID).shouldBeNull()
            jwksFetches shouldBe 2

            // Advance time past the unknown-kid cooldown AND past the jwks TTL default (300s) so a fresh
            // regular cache-expiry fetch happens, which itself counts as the "initial" attempt again --
            // followed by one more forced refetch for the still-unknown kid.
            fixedNow = fixedNow.plus(UNKNOWN_KID_REFETCH_COOLDOWN).plusSeconds(KeycloakConfig.DEFAULT_JWKS_CACHE_SECONDS.toLong() + 1)
            metadata.publicKeyPem(KID).shouldBeNull()
            jwksFetches shouldBe 4
        }

        test("malformed JWKS is handled gracefully -- publicKeyPem returns null, never throws") {
            val client =
                mockClient { request ->
                    if (request.url.toString() == DISCOVERY_URL) jsonResponse(discoveryJson()) else jsonResponse("not json {{{")
                }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.publicKeyPem(KID).shouldBeNull()
        }

        test("malformed discovery document is handled gracefully -- discoveryDocument returns null, never throws") {
            val client = mockClient { jsonResponse("not json {{{") }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.discoveryDocument().shouldBeNull()
        }

        test("an oversized response body is truncated to null instead of being parsed") {
            val oversized = discoveryJson().dropLast(1) + "x".repeat(MAX_KEYCLOAK_RESPONSE_BYTES + 1) + "\"}"
            val client = mockClient { jsonResponse(oversized) }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.discoveryDocument().shouldBeNull()
        }

        test("a non-2xx status maps to null, never throws") {
            val client = mockClient { respond("server error", HttpStatusCode.InternalServerError) }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.discoveryDocument().shouldBeNull()
        }

        test("a jwks_uri outside the pinned issuer is refused by the request-URL guard rather than fetched") {
            val client =
                mockClient { request ->
                    if (request.url.toString() == DISCOVERY_URL) {
                        jsonResponse(
                            """{"issuer":"$ISSUER","authorization_endpoint":"$ISSUER/auth","token_endpoint":"$ISSUER/token",""" +
                                """"jwks_uri":"https://evil.example.org/certs"}""",
                        )
                    } else {
                        jsonResponse(jwksJsonWithKid())
                    }
                }
            val metadata = KeycloakOidcMetadata(config = testConfig(), httpClient = client)

            metadata.publicKeyPem(KID).shouldBeNull()
        }
    })
