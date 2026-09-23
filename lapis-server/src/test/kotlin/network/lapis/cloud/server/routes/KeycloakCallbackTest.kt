package network.lapis.cloud.server.routes

import com.nimbusds.jwt.JWTClaimsSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.KeycloakLoginAttemptTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.OidcJwks
import network.lapis.cloud.server.federation.OidcJwt
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.keycloak.KeycloakDiscoveryDto
import network.lapis.cloud.server.keycloak.KeycloakOidcMetadata
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val TEST_JSON = Json { ignoreUnknownKeys = true }
private const val ISSUER = "https://keycloak-callback-test.example.org"
private const val CLIENT_ID = "lapis-cloud-test-client"
private const val KID = "test-kid-1"

/**
 * V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern" -- exercises
 * `GET /auth/keycloak/callback`'s security ordering. Mirrors [OidcRoutesTest]'s own documented
 * posture: state-consumption/replay/expiry/error-parameter cases run entirely WITHOUT network
 * (they are rejected before `metadata.discoveryDocument()` is ever called), and the cases that DO
 * need a full token-exchange + ID-token-verification round trip use a [MockEngine]-backed
 * [HttpClient] for BOTH [KeycloakOidcMetadata]'s discovery/JWKS fetch and the token-endpoint POST
 * -- same "MockEngine injected via the constructor, never real network I/O" house rule
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProviderTest] documents. A real
 * RSA-2048 keypair signs every ID token (never a mock), mirroring [OidcJwtTest]'s own posture.
 */
class KeycloakCallbackTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val keyPair = FederationKeyPairGenerator.generate()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    // The happy-path test mints a real session -- delete it before the member row.
                    SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                    KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Keycloak-Callback Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun testConfig(): KeycloakConfig {
            val values =
                mapOf(
                    KeycloakConfig.ENV_ENABLED to "true",
                    KeycloakConfig.ENV_ISSUER_URL to ISSUER,
                    KeycloakConfig.ENV_CLIENT_ID to CLIENT_ID,
                    KeycloakConfig.ENV_CLIENT_SECRET to "test-client-secret",
                )
            return KeycloakConfig.load(env = { name -> values[name] })
        }

        fun discoveryJson(): String =
            TEST_JSON.encodeToString(
                KeycloakDiscoveryDto.serializer(),
                KeycloakDiscoveryDto(
                    issuer = ISSUER,
                    authorization_endpoint = "$ISSUER/protocol/openid-connect/auth",
                    token_endpoint = "$ISSUER/protocol/openid-connect/token",
                    jwks_uri = "$ISSUER/protocol/openid-connect/certs",
                ),
            )

        fun jwksJson(): String = OidcJwks.buildJwksJson(publicKeyPem = keyPair.publicKeyPem, kid = KID)

        /** MockEngine-backed metadata -- serves discovery + JWKS, never real network. */
        fun mockMetadata(): KeycloakOidcMetadata {
            val engine =
                MockEngine { request ->
                    when {
                        request.url.toString().endsWith("/.well-known/openid-configuration") ->
                            respond(discoveryJson(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        request.url.toString().endsWith("/protocol/openid-connect/certs") ->
                            respond(jwksJson(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            return KeycloakOidcMetadata(config = testConfig(), httpClient = HttpClient(engine) { expectSuccess = false })
        }

        // Review finding N3 fix: mockMetadata() above always serves a discovery document whose
        // endpoints are on the pinned issuer's own host -- these two new tests need a TAMPERED
        // discovery document (endpoint on a DIFFERENT host) to exercise `KeycloakIssuerUrlGuard`'s
        // re-validation in `registerKeycloakAuthRoutes`'s `/start` and `/logout-redirect` handlers.
        fun mockMetadataWithDiscovery(discovery: KeycloakDiscoveryDto): KeycloakOidcMetadata {
            val json = TEST_JSON.encodeToString(KeycloakDiscoveryDto.serializer(), discovery)
            val engine =
                MockEngine { request ->
                    when {
                        request.url.toString().endsWith("/.well-known/openid-configuration") ->
                            respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        request.url.toString().endsWith("/protocol/openid-connect/certs") ->
                            respond(jwksJson(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            return KeycloakOidcMetadata(config = testConfig(), httpClient = HttpClient(engine) { expectSuccess = false })
        }

        fun signIdToken(claims: JWTClaimsSet): String = OidcJwt.sign(claimsSet = claims, kid = KID, privateKeyPem = keyPair.privateKeyPem)

        // idTokenProvider is a LAMBDA, not a plain value -- several call sites construct this
        // factory before their own `lateinit var idToken` is assigned (the factory is only
        // actually invoked, lazily, once the callback handler runs, which is always after the
        // assignment in test code below).
        fun tokenEndpointClientFactory(
            idTokenProvider: () -> String = { "" },
            tokenEndpointStatus: HttpStatusCode = HttpStatusCode.OK,
        ): () -> HttpClient =
            {
                val engine =
                    MockEngine { request ->
                        if (request.url.toString().endsWith("/protocol/openid-connect/token")) {
                            if (tokenEndpointStatus != HttpStatusCode.OK) {
                                respondError(tokenEndpointStatus)
                            } else {
                                val body =
                                    """{"access_token":"unused-access-token","token_type":"Bearer","expires_in":300,""" +
                                        """"id_token":"${idTokenProvider()}","scope":"openid email profile"}"""
                                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        } else {
                            respondError(HttpStatusCode.NotFound)
                        }
                    }
                HttpClient(engine) { expectSuccess = false }
            }

        // Security-audit fix (MAJOR 1) -- `bindingValue` is the RAW browser-binding cookie value
        // this fixture stores the HASH of on the row (`null` simulates either a pre-migration row
        // that never got one, or a test that deliberately never sends the matching cookie).
        // `KeycloakLoginAttemptFixture.bindingCookieValue` is what a real `/start` call would have
        // set as the cookie -- tests that want the callback to pass the new binding check attach it
        // via [withBindingCookie] below; tests that want to exercise a MISSING/WRONG cookie either
        // omit it or pass a different value into [withBindingCookie] directly.
        data class KeycloakLoginAttemptFixture(
            val state: String,
            val bindingCookieValue: String,
        )

        fun createLoginAttempt(
            nonce: String = "test-nonce-${Uuid.random()}",
            expiresInMinutes: Long = 10,
            consumed: Boolean = false,
            bindingValue: String? = SessionTokens.newRawToken(),
        ): KeycloakLoginAttemptFixture {
            val state = SessionTokens.newRawToken()
            val now = DbClock.nowLocalDateTime(TimeZone.UTC)
            val expiresAt = (now.toInstant(TimeZone.UTC) + expiresInMinutes.minutes).toLocalDateTime(TimeZone.UTC)
            transaction {
                KeycloakLoginAttemptTable.insert {
                    it[id] = Uuid.random()
                    it[stateHash] = SessionTokens.hash(state)
                    it[codeVerifier] = "test-code-verifier"
                    it[KeycloakLoginAttemptTable.nonce] = nonce
                    it[redirectUri] = "https://example.org/auth/keycloak/callback"
                    it[createdAt] = now
                    it[KeycloakLoginAttemptTable.expiresAt] = expiresAt
                    it[consumedAt] = if (consumed) now else null
                    it[browserBindingHash] = bindingValue?.let { raw -> SessionTokens.hash(raw) }
                }
            }
            // bindingValue may be null (no hash stored) -- the fixture still carries SOME cookie
            // value so a test can deliberately send a WRONG cookie against a row with no stored
            // hash; a test that wants no cookie sent at all simply never calls [withBindingCookie].
            return KeycloakLoginAttemptFixture(state = state, bindingCookieValue = bindingValue ?: SessionTokens.newRawToken())
        }

        /** Attaches the MAJOR-1 browser-binding cookie a real `/start` call would have set. */
        fun io.ktor.client.request.HttpRequestBuilder.withBindingCookie(value: String) {
            header(HttpHeaders.Cookie, "$KEYCLOAK_LOGIN_BINDING_COOKIE_NAME=$value")
        }

        // Round-2 security-audit fix (Finding 2) test helpers -- extract a query-string value from a
        // redirect Location/authorize URL, or a cookie's raw value from a Set-Cookie header.
        // `SessionTokens.newRawToken()` is Base64URL-safe (no `=`/`+`/`/`, see its own KDoc), so
        // none of these values are ever percent-encoded on the wire -- a plain regex is sufficient,
        // no URL-decoding needed.
        fun extractQueryParam(
            url: String,
            name: String,
        ): String {
            val match = Regex("[?&]$name=([^&]+)").find(url)
            return requireNotNull(match) { "no $name param in: $url" }.groupValues[1]
        }

        fun extractCookieValue(
            setCookieHeader: String,
            cookieName: String,
        ): String {
            val match = Regex("${Regex.escape(cookieName)}=([^;]+)").find(setCookieHeader)
            return requireNotNull(match) { "no $cookieName cookie in: $setCookieHeader" }.groupValues[1]
        }

        fun claimsBuilder(
            email: String,
            nonce: String,
            audience: String = CLIENT_ID,
            expiresInMinutes: Long = 10,
            emailVerified: Boolean = true,
        ): JWTClaimsSet {
            val now = Clock.System.now()
            return JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .subject("kc-subject-${Uuid.random()}")
                .audience(audience)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("nonce", nonce)
                .issueTime(OidcJwt.toJavaDate(now))
                .expirationTime(OidcJwt.toJavaDate(now.plus(expiresInMinutes.minutes)))
                .build()
        }

        // ── Network-free cases (state consumption happens before any HTTP call) ────────

        test("unknown state -> 401") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                        )
                    }
                }
                val response = client.get("/auth/keycloak/callback?state=never-issued&code=whatever")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("replayed state -> 401 on the SECOND call (proves atomic single-use consumption)") {
            testApplication {
                val config = testConfig()
                application {
                    routing {
                        // Review finding 10 fix: this test's default `tokenHttpClientFactory` used
                        // to be the class' real default (`{ keycloakHttpClient() }`) -- neither call
                        // below actually reaches the token-endpoint POST (both are rejected earlier,
                        // by the `error` param / by the already-consumed `state`), but that was true
                        // only by construction of THIS test's two requests, not enforced by the test
                        // itself, contradicting the class KDoc's "never real network I/O" contract.
                        // Inject the same MockEngine-backed stub every other test in this file uses.
                        registerKeycloakAuthRoutes(
                            config = config,
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(),
                        )
                    }
                }
                // First call fails downstream (no real code exchange mocked here), but the STATE
                // itself is consumed atomically on this very first hit regardless of what happens
                // after -- see registerKeycloakAuthRoutes KDoc.
                val fixture = createLoginAttempt()
                client.get("/auth/keycloak/callback?state=${fixture.state}&error=access_denied") {
                    withBindingCookie(fixture.bindingCookieValue)
                }

                // Review finding 10 fix: assert the DB-level effect of "consumed atomically", not
                // just the resulting HTTP status of the replay below.
                val stateHashValue = SessionTokens.hash(fixture.state)
                val consumedAt =
                    transaction {
                        KeycloakLoginAttemptTable
                            .selectAll()
                            .where { KeycloakLoginAttemptTable.stateHash eq stateHashValue }
                            .single()[KeycloakLoginAttemptTable.consumedAt]
                    }
                consumedAt shouldNotBe null

                val replay =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                replay.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("expired attempt -> 401") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                val fixture = createLoginAttempt(expiresInMinutes = -5)
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("error=access_denied -> 401") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                val fixture = createLoginAttempt()
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&error=access_denied") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("missing code (no error either) -> 401") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                val fixture = createLoginAttempt()
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── Cases requiring a full (mocked) token exchange ──────────────────────────

        test("token-endpoint 500 -> 502") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(tokenEndpointStatus = HttpStatusCode.InternalServerError),
                        )
                    }
                }
                val fixture = createLoginAttempt()
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.BadGateway
            }
        }

        test("ID token with wrong aud -> 401") {
            testApplication {
                val nonce = "nonce-wrong-aud"
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                idToken = signIdToken(claimsBuilder(email = "wrong-aud@example.org", nonce = nonce, audience = "some-other-client"))
                val fixture = createLoginAttempt(nonce = nonce)
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("ID token with wrong nonce -> 401") {
            testApplication {
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                idToken = signIdToken(claimsBuilder(email = "wrong-nonce@example.org", nonce = "the-token-s-own-nonce"))
                val fixture = createLoginAttempt(nonce = "a-completely-different-attempt-nonce")
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("ID token that is already expired -> 401") {
            testApplication {
                val nonce = "nonce-expired"
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                idToken = signIdToken(claimsBuilder(email = "expired@example.org", nonce = nonce, expiresInMinutes = -60))
                val fixture = createLoginAttempt(nonce = nonce)
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("happy path: verified ID token + matching, unlinked member -> session cookie + redirect") {
            testApplication {
                val nonce = "nonce-happy-${Uuid.random()}"
                val email = "keycloak-happy-${Uuid.random()}@example.org"
                val memberId = createMember(email)
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                idToken = signIdToken(claimsBuilder(email = email, nonce = nonce))
                val fixture = createLoginAttempt(nonce = nonce)
                // Ktor's default test client follows redirects -- a plain client.get() here would
                // report the status of the FOLLOWED "/app#/dashboard" request (404, no such route
                // in this minimal test app) instead of the callback's own 302. Same
                // `noRedirectClient` idiom OidcRoutesTest/SocialPublicRoutesTest already establish.
                val noRedirectClient = createClient { followRedirects = false }
                val response =
                    noRedirectClient.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }

                response.status shouldBe HttpStatusCode.Found
                requireNotNull(response.headers[HttpHeaders.SetCookie]) { "no session cookie set on happy path" }

                val linked =
                    transaction { KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq memberId }.single() }
                linked[KeycloakAccountLinkTable.keycloakIssuer] shouldBe ISSUER
            }
        }

        // ── Security-audit fix (MAJOR 1): browser-binding cookie required on /callback ──────

        test("valid, unconsumed state but NO binding cookie -> 401") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                val fixture = createLoginAttempt()
                // Deliberately NOT calling withBindingCookie -- simulates the login-CSRF attack: a
                // victim's browser opens a captured `/callback?state=...&code=...` URL without ever
                // having called `/start` itself, so it never received the binding cookie.
                val response = client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever")
                response.status shouldBe HttpStatusCode.Unauthorized

                // The state is still consumed (single-use property holds regardless of the binding
                // outcome) -- assert the audit trail names the real rejection reason.
                val failedRows =
                    transaction {
                        OidcGuestLoginEventTable
                            .selectAll()
                            .where { OidcGuestLoginEventTable.eventType eq OidcLoginEventType.KEYCLOAK_LOGIN_FAILED }
                            .orderBy(OidcGuestLoginEventTable.occurredAt to SortOrder.DESC)
                            .limit(1)
                            .toList()
                    }
                failedRows.single()[OidcGuestLoginEventTable.reason] shouldBe "BROWSER_BINDING_MISMATCH"
            }
        }

        test("valid, unconsumed state but WRONG binding cookie value -> 401") {
            testApplication {
                // Round-2 security-audit fix (Finding 2, sub-item 6): a token-exchange stub that
                // WOULD SUCCEED if the handler ever reached it -- a valid signed ID token for a real
                // member, matching this attempt's own nonce. The old version of this test passed no
                // `tokenHttpClientFactory` (defaulting to a real, unreachable network client), so a
                // regression that let a wrong-cookie request past the binding check could only ever
                // surface as SOME failure (401 from downstream verification, or 502 from a failed
                // token exchange) -- never definitively proving the binding check itself is what
                // rejects the request. With a stub that WOULD succeed, a regressed binding check
                // would make this test's request actually log the member in (302 + session cookie)
                // instead of 401, so the passing assertion below is proof the binding check runs and
                // rejects BEFORE token exchange is ever attempted.
                val nonce = "nonce-wrong-binding-would-succeed-${Uuid.random()}"
                val email = "keycloak-wrong-binding-would-succeed-${Uuid.random()}@example.org"
                createMember(email)
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                idToken = signIdToken(claimsBuilder(email = email, nonce = nonce))
                val fixture = createLoginAttempt(nonce = nonce)
                // A cookie IS sent, but it does not hash to the value stored on the attempt row --
                // e.g. an attacker who has some OTHER attempt's binding cookie from their own
                // browser and guesses/reuses it against a different, victim-relevant state.
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie("some-completely-different-value-${Uuid.random()}")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("happy path WITH the correct binding cookie still succeeds (regression guard)") {
            testApplication {
                val nonce = "nonce-happy-binding-${Uuid.random()}"
                val email = "keycloak-happy-binding-${Uuid.random()}@example.org"
                createMember(email)
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                idToken = signIdToken(claimsBuilder(email = email, nonce = nonce))
                val fixture = createLoginAttempt(nonce = nonce)
                val noRedirectClient = createClient { followRedirects = false }
                val response =
                    noRedirectClient.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Found
                requireNotNull(response.headers[HttpHeaders.SetCookie]) { "no session cookie set with a correct binding cookie" }
            }
        }

        test(
            "no matching member -> 401, never creates a member, and the audit row's reason is the " +
                "fixed NO_MATCHING_MEMBER code, never the raw email",
        ) {
            testApplication {
                val nonce = "nonce-nomatch-${Uuid.random()}"
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                val email = "no-such-member-${Uuid.random()}@example.org"
                idToken = signIdToken(claimsBuilder(email = email, nonce = nonce))
                val memberCountBefore = transaction { MemberTable.selectAll().count() }
                val fixture = createLoginAttempt(nonce = nonce)

                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&code=whatever") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized

                val memberCountAfter = transaction { MemberTable.selectAll().count() }
                memberCountAfter shouldBe memberCountBefore

                // Review finding F4 fix (round 3): previously nothing asserted what the
                // KEYCLOAK_LINK_MISS audit row's `reason` column actually contains -- see
                // KeycloakAuthRoutes.kt's own "Do NOT persist the raw email here" comment at this
                // call site. Assert the fixed code constant, and that no row's `reason` ever carries
                // the raw email string.
                val linkMissRows =
                    transaction {
                        OidcGuestLoginEventTable
                            .selectAll()
                            .where { OidcGuestLoginEventTable.eventType eq OidcLoginEventType.KEYCLOAK_LINK_MISS }
                            .orderBy(OidcGuestLoginEventTable.occurredAt to SortOrder.DESC)
                            .limit(1)
                            .toList()
                    }
                linkMissRows.size shouldBe 1
                linkMissRows.single()[OidcGuestLoginEventTable.reason] shouldBe "NO_MATCHING_MEMBER"
                val rowsLeakingEmail =
                    transaction {
                        OidcGuestLoginEventTable
                            .selectAll()
                            .where { OidcGuestLoginEventTable.reason eq email }
                            .count()
                    }
                rowsLeakingEmail shouldBe 0L
            }
        }

        // ── Review finding N3 fix: issuer-URL re-validation (review finding 7) on discovery-
        // document-supplied redirect targets -- /start's authorization_endpoint and /logout-
        // redirect's end_session_endpoint must both fail closed on a tampered discovery document
        // instead of ever redirecting the browser to an attacker-controlled host ─────────────────

        test("/start: a discovery document whose authorization_endpoint is outside the pinned issuer -> 502, never redirects") {
            testApplication {
                val maliciousDiscovery =
                    KeycloakDiscoveryDto(
                        issuer = ISSUER,
                        authorization_endpoint = "https://attacker.example.net/steal-the-code",
                        token_endpoint = "$ISSUER/protocol/openid-connect/token",
                        jwks_uri = "$ISSUER/protocol/openid-connect/certs",
                    )
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadataWithDiscovery(maliciousDiscovery),
                            startRateLimiter = LoginRateLimiter(),
                        )
                    }
                }
                // Ktor's default test client follows redirects -- if the guard failed to catch this,
                // a plain client.get() would silently follow the 302 straight to the attacker host
                // instead of reporting it. Use a non-redirecting client so a regression here shows up
                // as a 302 (Found) instead of masquerading as some other status from attacker.example.net.
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/auth/keycloak/start")
                response.status shouldBe HttpStatusCode.BadGateway
                response.headers[HttpHeaders.Location] shouldBe null
            }
        }

        test(
            "/logout-redirect: a discovery document whose end_session_endpoint is outside the pinned issuer -> endSessionUrl=null, no crash",
        ) {
            testApplication {
                val maliciousDiscovery =
                    KeycloakDiscoveryDto(
                        issuer = ISSUER,
                        authorization_endpoint = "$ISSUER/protocol/openid-connect/auth",
                        token_endpoint = "$ISSUER/protocol/openid-connect/token",
                        jwks_uri = "$ISSUER/protocol/openid-connect/certs",
                        end_session_endpoint = "https://attacker.example.net/fake-logout",
                    )
                application {
                    // Review finding N3 fix: production ContentNegotiation is installed app-wide by
                    // `initRpc` (see `Application.kt` comment on that call), which this minimal test
                    // app doesn't set up -- explicit install here, same idiom
                    // `KeycloakEmergencyAdminLoginTest.kt` already uses for testing a JSON-responding
                    // route in isolation.
                    install(ContentNegotiation) { json() }
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadataWithDiscovery(maliciousDiscovery),
                            startRateLimiter = LoginRateLimiter(),
                        )
                    }
                }
                val response = client.post("/auth/keycloak/logout-redirect")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe """{"endSessionUrl":null}"""
            }
        }

        // ── Round-2 security-audit fix (Finding 2): dedicated coverage for the previous round's
        // /start-side changes -- until now only /callback's HALF of the binding-cookie mechanism was
        // tested (via createLoginAttempt's hand-constructed fixture); the IPv6-bypass fix, the purge
        // simplification, the error-param sanitizer, and /start's OWN cookie-setting code had no
        // dedicated tests at all ─────────────────────────────────────────────────────────────────

        test("/start sets the browser-binding cookie correctly, hashing to the persisted attempt row's browser_binding_hash") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/auth/keycloak/start")
                response.status shouldBe HttpStatusCode.Found

                val setCookie = requireNotNull(response.headers[HttpHeaders.SetCookie]) { "no binding cookie set by /start" }
                setCookie shouldContain KEYCLOAK_LOGIN_BINDING_COOKIE_NAME
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "Secure"
                setCookie shouldContain "SameSite=Lax"

                val bindingValue = extractCookieValue(setCookieHeader = setCookie, cookieName = KEYCLOAK_LOGIN_BINDING_COOKIE_NAME)
                val location = requireNotNull(response.headers[HttpHeaders.Location]) { "no redirect Location from /start" }
                val state = extractQueryParam(url = location, name = "state")
                val stateHashValue = SessionTokens.hash(state)

                val storedBindingHash =
                    transaction {
                        KeycloakLoginAttemptTable
                            .selectAll()
                            .where { KeycloakLoginAttemptTable.stateHash eq stateHashValue }
                            .single()[KeycloakLoginAttemptTable.browserBindingHash]
                    }
                storedBindingHash shouldBe SessionTokens.hash(bindingValue)
            }
        }

        test("full /start -> /callback chain succeeds end-to-end with the REAL cookie /start set (not a hand-constructed fixture)") {
            testApplication {
                val email = "keycloak-e2e-${Uuid.random()}@example.org"
                createMember(email)
                lateinit var idToken: String
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenEndpointClientFactory(idTokenProvider = { idToken }),
                        )
                    }
                }
                val noRedirectClient = createClient { followRedirects = false }

                val startResponse = noRedirectClient.get("/auth/keycloak/start")
                startResponse.status shouldBe HttpStatusCode.Found
                val setCookie = requireNotNull(startResponse.headers[HttpHeaders.SetCookie]) { "no binding cookie set by /start" }
                val bindingValue = extractCookieValue(setCookieHeader = setCookie, cookieName = KEYCLOAK_LOGIN_BINDING_COOKIE_NAME)
                val location = requireNotNull(startResponse.headers[HttpHeaders.Location]) { "no redirect Location from /start" }
                val state = extractQueryParam(url = location, name = "state")
                val nonce = extractQueryParam(url = location, name = "nonce")

                idToken = signIdToken(claimsBuilder(email = email, nonce = nonce))

                val callbackResponse =
                    noRedirectClient.get("/auth/keycloak/callback?state=$state&code=whatever") {
                        header(HttpHeaders.Cookie, "$KEYCLOAK_LOGIN_BINDING_COOKIE_NAME=$bindingValue")
                    }
                callbackResponse.status shouldBe HttpStatusCode.Found
                requireNotNull(callbackResponse.headers[HttpHeaders.SetCookie]) {
                    "no session cookie set on the real /start -> /callback chain"
                }
            }
        }

        test("/start's own flood limiter fires: the request past the configured budget gets 429") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = testConfig(),
                            metadata = mockMetadata(),
                            startRateLimiter = LoginRateLimiter(),
                            // A small budget (rather than the production default of 30/minute) keeps
                            // this test fast -- same idiom EmbedRateLimitTest already establishes for
                            // its own FederationInboxRateLimiter-backed flood tests.
                            startFloodLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes),
                        )
                    }
                }
                val noRedirectClient = createClient { followRedirects = false }
                repeat(3) {
                    noRedirectClient.get("/auth/keycloak/start").status shouldBe HttpStatusCode.Found
                }
                val overBudget = noRedirectClient.get("/auth/keycloak/start")
                overBudget.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("/start's opportunistic purge deletes a row whose expiresAt is older than the cleanup cutoff") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                // KEYCLOAK_LOGIN_ATTEMPT_CLEANUP_AGE is 1 hour -- an attempt whose expiresAt is 70
                // minutes in the past is well past that cutoff regardless of whether it was ever
                // consumed (see the simplified purge predicate's KDoc).
                val fixture = createLoginAttempt(expiresInMinutes = -70)
                val stateHashValue = SessionTokens.hash(fixture.state)
                val countBefore =
                    transaction {
                        KeycloakLoginAttemptTable.selectAll().where { KeycloakLoginAttemptTable.stateHash eq stateHashValue }.count()
                    }
                countBefore shouldBe 1L

                val noRedirectClient = createClient { followRedirects = false }
                // Any real /start call triggers the sweep -- it doesn't need to be related to the
                // expired row above.
                noRedirectClient.get("/auth/keycloak/start")

                val countAfter =
                    transaction {
                        KeycloakLoginAttemptTable.selectAll().where { KeycloakLoginAttemptTable.stateHash eq stateHashValue }.count()
                    }
                countAfter shouldBe 0L
            }
        }

        test("error param with a CRLF log-injection payload is sanitized to the fixed PROVIDER_ERROR_OTHER audit reason") {
            testApplication {
                application {
                    routing {
                        registerKeycloakAuthRoutes(config = testConfig(), metadata = mockMetadata(), startRateLimiter = LoginRateLimiter())
                    }
                }
                val fixture = createLoginAttempt()
                val response =
                    client.get("/auth/keycloak/callback?state=${fixture.state}&error=foo%0D%0AFAKE-LOG-LINE") {
                        withBindingCookie(fixture.bindingCookieValue)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized

                val failedRows =
                    transaction {
                        OidcGuestLoginEventTable
                            .selectAll()
                            .where { OidcGuestLoginEventTable.eventType eq OidcLoginEventType.KEYCLOAK_LOGIN_FAILED }
                            .orderBy(OidcGuestLoginEventTable.occurredAt to SortOrder.DESC)
                            .limit(1)
                            .toList()
                    }
                // sanitizeOidcErrorCode's fixed fallback constant -- never the raw, CRLF-carrying value.
                failedRows.single()[OidcGuestLoginEventTable.reason] shouldBe "PROVIDER_ERROR_OTHER"
            }
        }
    })
