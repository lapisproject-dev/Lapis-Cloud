package network.lapis.cloud.server.routes

import com.nimbusds.jose.util.Base64URL
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.OidcDiscoveryDto
import network.lapis.cloud.server.federation.OidcDynamicClientRegistrationResponse
import network.lapis.cloud.server.federation.OidcJwks
import network.lapis.cloud.server.federation.OidcJwt
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.federation.OidcTokenResponseDto
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val TEST_JSON = Json { ignoreUnknownKeys = true }

/**
 * Exercises the V0.8.2 OIDC Issuer surface (`/.well-known/openid-configuration`,
 * `/federation/oidc/jwks`, `/federation/oidc/authorize`, `/federation/oidc/authorize/consent`,
 * `/federation/oidc/token`, `/federation/oidc/register`) end to end through the REAL, fully-wired
 * [network.lapis.cloud.server.module] -- same house pattern [AuthRoutesTest]/[FederationRoutesTest]
 * already establish.
 *
 * **RP-side (`/federation/oidc/rp/login`, `/federation/oidc/rp/callback`) and Back-Channel Logout
 * happy paths are NOT exercised here** -- both require a REAL outbound HTTPS fetch to an external
 * "home server", and this sandbox has no general internet egress (same documented limitation
 * [FederationRoutesTest] already states for V0.8.1's own outbound-fetch happy paths). What IS
 * tested instead: the SSRF-guard-rejects-before-any-fetch path (a loopback-resolving `home_server`
 * is rejected) and the Back-Channel-Logout-receiver's "reject before fetch" path (an unregistered
 * `iss` is rejected without ever attempting a JWKS fetch) -- both fully exercisable without
 * network, and both the actually security-relevant properties this wave's adversarial-test
 * requirements call for. [network.lapis.cloud.server.federation.OidcJwtTest] independently covers
 * every signature/claim-verification adversarial case (`alg:none`, RS256->HS256 confusion, tamper,
 * expiry, `iss`/`aud`/`nonce`) against real, locally-generated keypairs -- no network needed there.
 */
class OidcRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdClientIds = mutableListOf<String>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OidcAuthorizationCodeTable.deleteWhere { OidcAuthorizationCodeTable.memberId inList createdMemberIds }
                OidcIssuedTokenTable.deleteWhere { OidcIssuedTokenTable.memberId inList createdMemberIds }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                val registrationIds =
                    OidcClientRegistrationTable
                        .selectAll()
                        .where { OidcClientRegistrationTable.clientId inList createdClientIds }
                        .map { it[OidcClientRegistrationTable.id] }
                OidcClientRedirectUriTable.deleteWhere { OidcClientRedirectUriTable.clientRegistrationId inList registrationIds }
                OidcClientRegistrationTable.deleteWhere { OidcClientRegistrationTable.clientId inList createdClientIds }
            }
        }

        fun createTestMember(email: String): Pair<Uuid, String> {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "OIDC Routes Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash("irrelevant-password-1234")
                }
            }
            createdMemberIds += id
            val rawToken = SessionStore.createSession(id).rawToken
            return id to rawToken
        }

        suspend fun registerDcrClient(
            client: HttpClient,
            clientName: String,
            redirectUri: String = "https://rp.example/callback",
        ): OidcDynamicClientRegistrationResponse {
            val response =
                client.post("/federation/oidc/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"client_name":"$clientName","redirect_uris":["$redirectUri"],""" +
                            """"backchannel_logout_uri":"https://rp.example/backchannel-logout"}""",
                    )
                }
            response.status shouldBe HttpStatusCode.Created
            val dto = TEST_JSON.decodeFromString(OidcDynamicClientRegistrationResponse.serializer(), response.bodyAsText())
            createdClientIds += dto.client_id
            return dto
        }

        suspend fun consentAndExtractCode(
            noRedirectClient: HttpClient,
            rawSession: String,
            clientId: String,
            redirectUri: String,
            scope: String,
            state: String,
            codeChallenge: String,
            nonce: String,
            decision: String = "allow",
        ): HttpResponse =
            noRedirectClient.post("/federation/oidc/authorize/consent") {
                header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("decision", decision)
                            append("client_id", clientId)
                            append("redirect_uri", redirectUri)
                            append("scope", scope)
                            append("state", state)
                            append("code_challenge", codeChallenge)
                            append("nonce", nonce)
                        }.formUrlEncode(),
                )
            }

        fun codeFromLocation(response: HttpResponse): String {
            val location = requireNotNull(response.headers[HttpHeaders.Location])
            return requireNotNull(Regex("code=([^&]+)").find(location)) { "no code in $location" }.groupValues[1]
        }

        suspend fun exchangeCode(
            client: HttpClient,
            clientId: String,
            clientSecret: String,
            code: String,
            codeVerifier: String,
            redirectUri: String = "https://rp.example/callback",
        ): HttpResponse =
            client.post("/federation/oidc/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("redirect_uri", redirectUri)
                            append("client_id", clientId)
                            append("client_secret", clientSecret)
                            append("code_verifier", codeVerifier)
                        }.formUrlEncode(),
                )
            }

        // ── Discovery / JWKS ────────────────────────────────────────────────

        test("GET /.well-known/openid-configuration returns a spec-shaped discovery document") {
            testApplication {
                application { module() }
                val response = client.get("/.well-known/openid-configuration")
                response.status shouldBe HttpStatusCode.OK
                val dto = TEST_JSON.decodeFromString(OidcDiscoveryDto.serializer(), response.bodyAsText())
                dto.authorization_endpoint shouldContain "/federation/oidc/authorize"
                dto.token_endpoint shouldContain "/federation/oidc/token"
                dto.jwks_uri shouldContain "/federation/oidc/jwks"
                dto.registration_endpoint shouldContain "/federation/oidc/register"
                dto.code_challenge_methods_supported shouldBe listOf("S256")
                dto.id_token_signing_alg_values_supported shouldBe listOf("RS256")
                // Voting is never a scope, full stop.
                dto.scopes_supported.any { it.contains("vote", ignoreCase = true) } shouldBe false
            }
        }

        test("GET /federation/oidc/jwks JSON shape has kty=RSA, use=sig, alg=RS256 and a kid, no private material") {
            testApplication {
                application { module() }
                val body = client.get("/federation/oidc/jwks").bodyAsText()
                body shouldContain "\"kty\":\"RSA\""
                body shouldContain "\"use\":\"sig\""
                body shouldContain "\"alg\":\"RS256\""
                body shouldContain "\"kid\""
                body shouldContain "\"n\""
                body shouldContain "\"e\""
                body.contains("-----BEGIN") shouldBe false
                body.contains("PRIVATE") shouldBe false
            }
        }

        // ── Dynamic Client Registration ─────────────────────────────────────

        test("POST /federation/oidc/register rejects a non-HTTPS redirect_uri") {
            testApplication {
                application { module() }
                val response =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"client_name":"Insecure RP","redirect_uris":["http://insecure.example/callback"]}""")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("POST /federation/oidc/register with valid HTTPS redirect_uris succeeds and returns a usable client_id/client_secret") {
            testApplication {
                application { module() }
                val dto = registerDcrClient(client, "Valid RP ${Uuid.random()}")
                dto.client_id.isBlank() shouldBe false
                dto.client_secret.isBlank() shouldBe false
                dto.token_endpoint_auth_method shouldBe "client_secret_post"
            }
        }

        test("POST /federation/oidc/register is rate-limited after repeated attempts from the same caller") {
            testApplication {
                application { module() }
                var lastStatus: HttpStatusCode = HttpStatusCode.OK
                // LoginRateLimiter's default maxFailures is 5 -- 8 attempts guarantees the limiter trips.
                repeat(8) {
                    val response = registerDcrClientRaw(client, "Rate Limit RP $it ${Uuid.random()}")
                    lastStatus = response.status
                    if (response.status == HttpStatusCode.Created) {
                        val dto = TEST_JSON.decodeFromString(OidcDynamicClientRegistrationResponse.serializer(), response.bodyAsText())
                        createdClientIds += dto.client_id
                    }
                }
                lastStatus shouldBe HttpStatusCode.TooManyRequests
            }
        }

        // ── /authorize validation ────────────────────────────────────────────

        test("GET /authorize with an unregistered client_id is rejected") {
            testApplication {
                application { module() }
                val response =
                    client.get(
                        "/federation/oidc/authorize?response_type=code&client_id=unknown-client&redirect_uri=" +
                            "https://rp.example/callback&scope=openid&state=s1&code_challenge=c1&code_challenge_method=S256&nonce=n1",
                    )
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("GET /authorize with a redirect_uri NOT registered for the client is rejected (open-redirect prevention)") {
            testApplication {
                application { module() }
                val dto = registerDcrClient(client, "Redirect Mismatch RP ${Uuid.random()}")

                val response =
                    client.get(
                        "/federation/oidc/authorize?response_type=code&client_id=${dto.client_id}&redirect_uri=" +
                            "https://attacker.example/steal&scope=openid&state=s1&code_challenge=c1&code_challenge_method=S256&nonce=n1",
                    )
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("GET /authorize with code_challenge_method=plain is rejected (S256 only)") {
            testApplication {
                application { module() }
                val dto = registerDcrClient(client, "Plain PKCE RP ${Uuid.random()}")

                val response =
                    client.get(
                        "/federation/oidc/authorize?response_type=code&client_id=${dto.client_id}&redirect_uri=" +
                            "https://rp.example/callback&scope=openid&state=s1&code_challenge=c1&code_challenge_method=plain&nonce=n1",
                    )
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("GET /authorize with no active session redirects to the login page, returnTo round-tripping the original request") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }
                val dto = registerDcrClient(client, "No Session RP ${Uuid.random()}")

                val authorizeUrl =
                    "/federation/oidc/authorize?response_type=code&client_id=${dto.client_id}&redirect_uri=" +
                        "https://rp.example/callback&scope=openid&state=s1&code_challenge=c1&code_challenge_method=S256&nonce=n1"
                val response = noRedirectClient.get(authorizeUrl)
                response.status shouldBe HttpStatusCode.Found
                val location = requireNotNull(response.headers[HttpHeaders.Location])
                location shouldContain "/#/login"
                location shouldContain "returnTo="
            }
        }

        // ── Full Issuer flow: authorize (session) -> consent -> code -> token ─

        test("full authorization_code + PKCE flow: consent issues a code, /token exchanges it for a valid, JWKS-verifiable ID token") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }

                val dcrDto = registerDcrClient(client, "Full Flow RP ${Uuid.random()}")
                val (_, rawSession) = createTestMember("oidc-full-flow-${Uuid.random()}@example.org")

                val codeVerifier = "test-code-verifier-${Uuid.random()}-with-enough-length-1234567890"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
                val state = "state-${Uuid.random()}"
                val nonce = "nonce-${Uuid.random()}"

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient,
                        rawSession,
                        dcrDto.client_id,
                        "https://rp.example/callback",
                        "openid profile_basic",
                        state,
                        codeChallenge,
                        nonce,
                    )
                consentResponse.status shouldBe HttpStatusCode.Found
                requireNotNull(consentResponse.headers[HttpHeaders.Location]) shouldContain "https://rp.example/callback?code="
                requireNotNull(consentResponse.headers[HttpHeaders.Location]) shouldContain "state=$state"
                val code = codeFromLocation(consentResponse)

                val tokenResponse = exchangeCode(client, dcrDto.client_id, dcrDto.client_secret, code, codeVerifier)
                tokenResponse.status shouldBe HttpStatusCode.OK
                val tokenDto = TEST_JSON.decodeFromString(OidcTokenResponseDto.serializer(), tokenResponse.bodyAsText())
                tokenDto.access_token.isBlank() shouldBe false
                requireNotNull(tokenDto.refresh_token).isBlank() shouldBe false

                // Verify the ID token against this server's OWN published JWKS (real signature path).
                val jwksBody = client.get("/federation/oidc/jwks").bodyAsText()
                val kid = requireNotNull(OidcJwt.extractUnverifiedKid(tokenDto.id_token))
                val publicKeyPem = requireNotNull(OidcJwks.findRsaPublicKeyPem(jwksBody, kid))
                val issuer =
                    TEST_JSON
                        .decodeFromString(OidcDiscoveryDto.serializer(), client.get("/.well-known/openid-configuration").bodyAsText())
                        .issuer
                val verification = OidcJwt.verifyIdToken(tokenDto.id_token, publicKeyPem, issuer, dcrDto.client_id, nonce)
                verification.shouldBeInstanceOf<OidcJwt.VerificationResult.Valid>()

                // Refresh-token rotation: the OLD refresh token must no longer work after a refresh.
                val refreshTokenValue = requireNotNull(tokenDto.refresh_token)
                val refreshBody =
                    Parameters
                        .build {
                            append("grant_type", "refresh_token")
                            append("refresh_token", refreshTokenValue)
                            append("client_id", dcrDto.client_id)
                            append("client_secret", dcrDto.client_secret)
                        }.formUrlEncode()
                val refreshResponse1 =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(refreshBody)
                    }
                refreshResponse1.status shouldBe HttpStatusCode.OK
                val rotatedTokenDto = TEST_JSON.decodeFromString(OidcTokenResponseDto.serializer(), refreshResponse1.bodyAsText())
                val rotatedRefreshTokenValue = requireNotNull(rotatedTokenDto.refresh_token)

                val refreshResponse2Reuse =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(refreshBody)
                    }
                refreshResponse2Reuse.status shouldBe HttpStatusCode.BadRequest

                // Reuse-detection cascade (RFC 9700 SS4.14.2): replaying the OLD (already-rotated-away)
                // refresh token above must not just be rejected itself -- it must also revoke the
                // CURRENTLY-VALID rotated token from refreshResponse1, so a stolen-and-since-rotated
                // token cannot be kept alive indefinitely by whoever holds the newest copy.
                val rotatedRefreshBody =
                    Parameters
                        .build {
                            append("grant_type", "refresh_token")
                            append("refresh_token", rotatedRefreshTokenValue)
                            append("client_id", dcrDto.client_id)
                            append("client_secret", dcrDto.client_secret)
                        }.formUrlEncode()
                val refreshResponse3AfterReuseCascade =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(rotatedRefreshBody)
                    }
                refreshResponse3AfterReuseCascade.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/token: correct code but WRONG code_verifier is rejected with invalid_grant (PKCE tamper test)") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }
                val dcrDto = registerDcrClient(client, "PKCE Tamper RP ${Uuid.random()}")
                val (_, rawSession) = createTestMember("oidc-pkce-tamper-${Uuid.random()}@example.org")

                val codeVerifier = "correct-verifier-${Uuid.random()}-padding-padding-padding"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient,
                        rawSession,
                        dcrDto.client_id,
                        "https://rp.example/callback",
                        "openid",
                        "s1",
                        codeChallenge,
                        "n1",
                    )
                val code = codeFromLocation(consentResponse)

                val tokenResponse =
                    exchangeCode(client, dcrDto.client_id, dcrDto.client_secret, code, "totally-the-wrong-verifier-value-padding-pad")
                tokenResponse.status shouldBe HttpStatusCode.BadRequest
                tokenResponse.bodyAsText() shouldContain "invalid_grant"
            }
        }

        test("/token: the SAME authorization code redeemed twice -- the second attempt is rejected (single-use)") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }
                val dcrDto = registerDcrClient(client, "Single Use Code RP ${Uuid.random()}")
                val (_, rawSession) = createTestMember("oidc-single-use-${Uuid.random()}@example.org")

                val codeVerifier = "single-use-verifier-${Uuid.random()}-padding-pad"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient,
                        rawSession,
                        dcrDto.client_id,
                        "https://rp.example/callback",
                        "openid",
                        "s1",
                        codeChallenge,
                        "n1",
                    )
                val code = codeFromLocation(consentResponse)

                val first = exchangeCode(client, dcrDto.client_id, dcrDto.client_secret, code, codeVerifier)
                first.status shouldBe HttpStatusCode.OK
                val second = exchangeCode(client, dcrDto.client_id, dcrDto.client_secret, code, codeVerifier)
                second.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/token: an authorization code redeemed with a DIFFERENT redirect_uri than the one used at /authorize is rejected") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }
                val dcrDto = registerDcrClient(client, "Redirect Mixup RP ${Uuid.random()}")
                val (_, rawSession) = createTestMember("oidc-redirect-mixup-${Uuid.random()}@example.org")

                val codeVerifier = "mixup-verifier-${Uuid.random()}-padding-padding"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient,
                        rawSession,
                        dcrDto.client_id,
                        "https://rp.example/callback",
                        "openid",
                        "s1",
                        codeChallenge,
                        "n1",
                    )
                val code = codeFromLocation(consentResponse)

                // NOT the redirect_uri this code was minted for.
                val tokenResponse =
                    exchangeCode(
                        client,
                        dcrDto.client_id,
                        dcrDto.client_secret,
                        code,
                        codeVerifier,
                        redirectUri = "https://rp.example/callback/other",
                    )
                tokenResponse.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/token: an expired authorization code is rejected") {
            testApplication {
                application { module() }
                val dcrDto = registerDcrClient(client, "Expired Code RP ${Uuid.random()}")
                val (memberId, _) = createTestMember("oidc-expired-code-${Uuid.random()}@example.org")

                val clientRegistrationId =
                    transaction {
                        OidcClientRegistrationTable
                            .selectAll()
                            .where { OidcClientRegistrationTable.clientId eq dcrDto.client_id }
                            .single()[OidcClientRegistrationTable.id]
                    }
                val codeVerifier = "expired-verifier-padding-padding-padding-1"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
                val rawCode = "expired-code-${Uuid.random()}"
                val past = Clock.System.now().minus(1.hours)
                transaction {
                    OidcAuthorizationCodeTable.insert {
                        it[id] = Uuid.random()
                        it[codeHash] = SessionTokens.hash(rawCode)
                        it[OidcAuthorizationCodeTable.clientRegistrationId] = clientRegistrationId
                        it[OidcAuthorizationCodeTable.memberId] = memberId
                        it[redirectUri] = "https://rp.example/callback"
                        it[scope] = "openid"
                        it[OidcAuthorizationCodeTable.codeChallenge] = codeChallenge
                        it[nonce] = "n1"
                        it[createdAt] = past.minus(1.hours).toLocalDateTime(TimeZone.UTC)
                        it[expiresAt] = past.toLocalDateTime(TimeZone.UTC)
                        it[consumedAt] = null
                    }
                }

                val tokenResponse = exchangeCode(client, dcrDto.client_id, dcrDto.client_secret, rawCode, codeVerifier)
                tokenResponse.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/token with an invalid client_secret is rejected with invalid_client, generic message") {
            testApplication {
                application { module() }
                val dcrDto = registerDcrClient(client, "Bad Secret RP ${Uuid.random()}")

                val response = exchangeCode(client, dcrDto.client_id, "totally-wrong-secret", "whatever", "whatever-verifier")
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "invalid_client"
            }
        }

        // ── RP-side SSRF-defense reuse (no network needed) ──────────────────

        test("POST /federation/oidc/rp/login with a loopback-resolving home_server is rejected (SSRF-guard reuse, before any fetch)") {
            testApplication {
                application { module() }
                val response =
                    client.post("/federation/oidc/rp/login") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(Parameters.build { append("home_server", "https://localhost") }.formUrlEncode())
                    }
                response.status shouldBe HttpStatusCode.BadGateway
                response.bodyAsText() shouldContain "Anmeldung fehlgeschlagen"
            }
        }

        // ── Back-Channel Logout receiver "reject before fetch" ──────────────

        test("POST /federation/oidc/backchannel-logout with an unregistered iss is rejected immediately, no fetch attempted") {
            testApplication {
                application { module() }
                // A structurally well-formed but entirely unsigned/unverified JWT whose payload
                // merely CLAIMS an issuer this server has never registered against -- the route
                // must reject this via a DB lookup alone, never attempting requireSafeFederationUrl/
                // an outbound JWKS fetch for an issuer it doesn't know (which, if it DID fetch,
                // would either hang or fail in this network-less sandbox -- the fact this test
                // returns promptly with 400 is itself evidence the "reject before fetch" ordering
                // held).
                val header = base64UrlJson("""{"alg":"RS256","typ":"JWT","kid":"whatever"}""")
                val payload = base64UrlJson("""{"iss":"https://never-registered-${Uuid.random()}.example","sub":"x","aud":"y"}""")
                val fakeToken = "$header.$payload.fakesignature"

                val response =
                    client.post("/federation/oidc/backchannel-logout") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(Parameters.build { append("logout_token", fakeToken) }.formUrlEncode())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Unknown issuer"
            }
        }

        test("POST /federation/oidc/backchannel-logout with a malformed logout_token is rejected") {
            testApplication {
                application { module() }
                val response =
                    client.post("/federation/oidc/backchannel-logout") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(Parameters.build { append("logout_token", "not-a-jwt-at-all") }.formUrlEncode())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private suspend fun registerDcrClientRaw(
    client: HttpClient,
    clientName: String,
): HttpResponse =
    client.post("/federation/oidc/register") {
        contentType(ContentType.Application.Json)
        setBody(
            """{"client_name":"$clientName","redirect_uris":["https://rp.example/callback"],""" +
                """"backchannel_logout_uri":"https://rp.example/backchannel-logout"}""",
        )
    }

private fun base64UrlJson(json: String): String = Base64URL.encode(json.toByteArray(Charsets.UTF_8)).toString()
