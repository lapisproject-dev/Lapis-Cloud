package network.lapis.cloud.server.routes

import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
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
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.OidcJwks
import network.lapis.cloud.server.federation.OidcJwt
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.keycloak.KeycloakDiscoveryDto
import network.lapis.cloud.server.keycloak.KeycloakMemberProvisioner
import network.lapis.cloud.server.keycloak.KeycloakOidcMetadata
import network.lapis.cloud.server.keycloak.KeycloakProfileSync
import network.lapis.cloud.server.keycloak.RecordingProvisioningMailer
import network.lapis.cloud.server.keycloak.deleteMembersCompletely
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.member.EmailChangeFixture
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
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
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ISSUER = "https://keycloak-jit-callback.example.org"
private const val CLIENT_ID = "lapis-cloud-jit-client"
private const val KID = "jit-kid-1"
private val TEST_JSON = Json { ignoreUnknownKeys = true }

/**
 * Welle V1.9.73 -- `GET /auth/keycloak/callback` with just-in-time provisioning and the profile sync, against a SIMULATED identity
 * provider (a MockEngine serving discovery, JWKS and the token endpoint; a real RSA key signs the ID tokens). NOT a test against
 * a real Keycloak. Every refusal case asserts that no member was created.
 */
class KeycloakJitCallbackTest :
    FunSpec({
        val fixture = EmailChangeFixture()
        val provisionedIds = mutableListOf<Uuid>()
        val keyPair = FederationKeyPairGenerator.generate()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            val all = provisionedIds + fixture.createdMemberIds
            transaction {
                SessionTable.deleteWhere { SessionTable.memberId inList all }
                KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId inList all }
            }
            deleteMembersCompletely(provisionedIds)
            fixture.cleanUp()
        }

        fun config(
            autoProvision: Boolean = true,
            ratePerHour: Int? = null,
            syncProfile: Boolean = false,
            group: String? = "apolda",
        ): KeycloakConfig {
            val env =
                buildMap {
                    put(KeycloakConfig.ENV_ENABLED, "true")
                    put(KeycloakConfig.ENV_ISSUER_URL, ISSUER)
                    put(KeycloakConfig.ENV_CLIENT_ID, CLIENT_ID)
                    put(KeycloakConfig.ENV_CLIENT_SECRET, "test-client-secret")
                    put(KeycloakConfig.ENV_AUTO_PROVISION, autoProvision.toString())
                    put(KeycloakConfig.ENV_SYNC_PROFILE, syncProfile.toString())
                    if (group != null) put(KeycloakConfig.ENV_PROVISION_GROUP, group)
                    put(KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR, (ratePerHour ?: 500).toString())
                }
            return KeycloakConfig.load { env[it] }
        }

        fun metadata(config: KeycloakConfig): KeycloakOidcMetadata {
            val discovery =
                TEST_JSON.encodeToString(
                    KeycloakDiscoveryDto.serializer(),
                    KeycloakDiscoveryDto(
                        issuer = ISSUER,
                        authorization_endpoint = "$ISSUER/protocol/openid-connect/auth",
                        token_endpoint = "$ISSUER/protocol/openid-connect/token",
                        jwks_uri = "$ISSUER/protocol/openid-connect/certs",
                    ),
                )
            val jwks = OidcJwks.buildJwksJson(publicKeyPem = keyPair.publicKeyPem, kid = KID)
            val engine =
                MockEngine { request ->
                    when {
                        request.url.toString().endsWith("/.well-known/openid-configuration") ->
                            respond(discovery, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        request.url.toString().endsWith("/protocol/openid-connect/certs") ->
                            respond(jwks, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            return KeycloakOidcMetadata(config = config, httpClient = HttpClient(engine) { expectSuccess = false })
        }

        fun tokenClient(
            idToken: () -> String,
            accessToken: () -> String = { "unused-access-token" },
        ): () -> HttpClient =
            {
                HttpClient(
                    MockEngine { request ->
                        if (request.url.toString().endsWith("/protocol/openid-connect/token")) {
                            respond(
                                """{"access_token":"${accessToken()}","token_type":"Bearer","expires_in":300,"id_token":"${idToken()}","scope":"openid email profile"}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respondError(HttpStatusCode.NotFound)
                        }
                    },
                ) { expectSuccess = false }
            }

        data class Attempt(
            val state: String,
            val cookie: String,
        )

        fun createAttempt(nonce: String): Attempt {
            val state = SessionTokens.newRawToken()
            val binding = SessionTokens.newRawToken()
            val now = DbClock.nowLocalDateTime(TimeZone.UTC)
            transaction {
                KeycloakLoginAttemptTable.insert {
                    it[id] = Uuid.random()
                    it[stateHash] = SessionTokens.hash(state)
                    it[codeVerifier] = "test-code-verifier"
                    it[KeycloakLoginAttemptTable.nonce] = nonce
                    it[redirectUri] = "https://example.org/auth/keycloak/callback"
                    it[createdAt] = now
                    it[expiresAt] = (now.toInstant(TimeZone.UTC) + 10.minutes).toLocalDateTime(TimeZone.UTC)
                    it[consumedAt] = null
                    it[browserBindingHash] = SessionTokens.hash(binding)
                }
            }
            return Attempt(state = state, cookie = binding)
        }

        fun claims(
            email: String,
            nonce: String,
            subject: String = "kc-sub-${Uuid.random()}",
            name: String? = "Erika Muster",
            groups: Any? = listOf("apolda"),
            emailVerified: Boolean = true,
            audience: String = CLIENT_ID,
            issuer: String = ISSUER,
            expiresInMinutes: Long = 10,
            extra: Map<String, Any?> = emptyMap(),
        ): JWTClaimsSet {
            val now = Clock.System.now()
            val builder =
                JWTClaimsSet
                    .Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .claim("email", email)
                    .claim("email_verified", emailVerified)
                    .claim("nonce", nonce)
                    .issueTime(OidcJwt.toJavaDate(now))
                    .expirationTime(OidcJwt.toJavaDate(now + expiresInMinutes.minutes))
            if (name != null) builder.claim("name", name)
            if (groups != null) builder.claim("groups", groups)
            extra.forEach { (k, v) -> builder.claim(k, v) }
            return builder.build()
        }

        fun sign(claims: JWTClaimsSet): String = OidcJwt.sign(claimsSet = claims, kid = KID, privateKeyPem = keyPair.privateKeyPem)

        fun existingMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid = fixture.member(email = email, role = role)

        fun linkRow(id: Uuid) =
            transaction { KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq id }.singleOrNull() }

        fun events(
            memberId: Uuid? = null,
            type: OidcLoginEventType,
        ) = transaction {
            OidcGuestLoginEventTable
                .selectAll()
                .where { OidcGuestLoginEventTable.eventType eq type }
                .orderBy(OidcGuestLoginEventTable.occurredAt to SortOrder.DESC)
                .limit(50)
                .filter { memberId == null || it[OidcGuestLoginEventTable.memberId] == memberId }
        }

        fun hasSession(setCookies: List<String>) = setCookies.any { it.startsWith("$SESSION_COOKIE_NAME=") }

        fun linkMember(
            id: Uuid,
            subject: String,
        ) {
            val now = DbClock.nowLocalDateTime()
            transaction {
                KeycloakAccountLinkTable.insert {
                    it[KeycloakAccountLinkTable.id] = Uuid.random()
                    it[memberId] = id
                    it[keycloakIssuer] = ISSUER
                    it[keycloakSubject] = subject
                    it[linkedAt] = now
                    it[linkedBy] = null
                    it[lastLoginAt] = now
                }
            }
        }

        fun memberCount() = transaction { MemberTable.selectAll().count() }

        /** Drives one callback with the given ID token (a lambda, so a test can sign it after the app started). */
        fun callback(
            cfg: KeycloakConfig,
            nonce: String,
            idToken: () -> String,
            accessToken: () -> String = { "unused-access-token" },
            mailer: RecordingProvisioningMailer = RecordingProvisioningMailer(),
            smtp: SmtpConfigState = configuredSmtp(),
            withProvisioner: Boolean = true,
            withSync: Boolean = false,
            block: suspend (status: HttpStatusCode, body: String, setCookies: List<String>) -> Unit,
        ) {
            testApplication {
                val notifier = KeycloakProvisioningNotifier(mailer = mailer, smtpConfigState = smtp)
                application {
                    routing {
                        registerKeycloakAuthRoutes(
                            config = cfg,
                            metadata = metadata(cfg),
                            startRateLimiter = LoginRateLimiter(),
                            tokenHttpClientFactory = tokenClient(idToken = idToken, accessToken = accessToken),
                            provisioner = if (withProvisioner) KeycloakMemberProvisioner(config = cfg, notifier = notifier) else null,
                            profileSync = if (withSync) KeycloakProfileSync(notifier = notifier) else null,
                        )
                    }
                }
                val attempt = createAttempt(nonce)
                val noRedirect = createClient { followRedirects = false }
                val response =
                    noRedirect.get("/auth/keycloak/callback?state=${attempt.state}&code=whatever") {
                        header(HttpHeaders.Cookie, "$KEYCLOAK_LOGIN_BINDING_COOKIE_NAME=${attempt.cookie}")
                    }
                block(response.status, response.bodyAsText(), response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
            }
        }

        fun trackNewMember(email: String): Uuid? {
            val id =
                transaction {
                    MemberTable
                        .selectAll()
                        .where { MemberTable.email eq email }
                        .singleOrNull()
                        ?.get(MemberTable.id)
                }
            if (id != null && id !in fixture.createdMemberIds) provisionedIds += id
            return id
        }

        fun assertRefused(
            email: String,
            nonce: String,
            token: () -> String,
            accessToken: () -> String = { "unused-access-token" },
            expectedStatus: HttpStatusCode = HttpStatusCode.Unauthorized,
        ) {
            val before = memberCount()
            callback(cfg = config(), nonce = nonce, idToken = token, accessToken = accessToken) { status, _, setCookies ->
                status shouldBe expectedStatus
                hasSession(setCookies) shouldBe false
            }
            memberCount() shouldBe before
            transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() } shouldBe 0
        }

        test("end to end: a group member without a local member is created, logged in, and the events and the admin notice are written") {
            val admin = fixture.member(role = AccountRole.ADMIN)
            val board = fixture.member(role = AccountRole.BOARD)
            val email = "e2e-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            val mailer = RecordingProvisioningMailer()
            callback(cfg = config(), nonce = nonce, idToken = { sign(claims(email = email, nonce = nonce)) }, mailer = mailer) {
                status,
                _,
                setCookies,
                ->
                status shouldBe HttpStatusCode.Found
                hasSession(setCookies) shouldBe true
            }
            val id = requireNotNull(trackNewMember(email))
            linkRow(id)!![KeycloakAccountLinkTable.keycloakIssuer] shouldBe ISSUER
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.role] } shouldBe
                AccountRole.MEMBER
            transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] } shouldBe MemberStatus.ACTIVE
            events(id, OidcLoginEventType.KEYCLOAK_LOGIN_SUCCESS).size shouldBe 1
            events(id, OidcLoginEventType.KEYCLOAK_LINK_CREATED).size shouldBe 1
            val recipients = mailer.provisioned.map { it.email }
            recipients shouldContain fixture.emailOf(admin)
            recipients shouldNotContain fixture.emailOf(board)
            transaction { SessionTable.selectAll().where { SessionTable.memberId eq id }.count() } shouldBe 1
        }

        test("a failing mailer or a missing SMTP configuration never blocks the login") {
            fixture.member(role = AccountRole.ADMIN)
            listOf<Pair<RecordingProvisioningMailer, SmtpConfigState>>(
                RecordingProvisioningMailer(failing = true) to configuredSmtp(),
                RecordingProvisioningMailer() to SmtpConfigState.NotConfigured,
            ).forEach { (mailer, smtp) ->
                val email = "mail-" + Uuid.random().toString().take(10) + "@example.org"
                val nonce = "n-${Uuid.random()}"
                callback(
                    cfg = config(),
                    nonce = nonce,
                    idToken = { sign(claims(email = email, nonce = nonce)) },
                    mailer = mailer,
                    smtp = smtp,
                ) {
                    status,
                    _,
                    _,
                    ->
                    status shouldBe HttpStatusCode.Found
                }
                (trackNewMember(email) != null) shouldBe true
            }
        }

        test("option off: exactly the old behaviour -- 401, the generic page, KEYCLOAK_LINK_MISS, no member") {
            val email = "off-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            val before = memberCount()
            callback(
                cfg = config(autoProvision = false, group = null),
                nonce = nonce,
                idToken = { sign(claims(email = email, nonce = nonce)) },
            ) { status, body, _ ->
                status shouldBe HttpStatusCode.Unauthorized
                body shouldContain "Kein zugeordnetes Mitgliedskonto"
            }
            memberCount() shouldBe before
            events(type = OidcLoginEventType.KEYCLOAK_LINK_MISS).first()[OidcGuestLoginEventTable.reason] shouldBe "NO_MATCHING_MEMBER"
            events(type = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED).first()[OidcGuestLoginEventTable.reason] shouldBe
                "LINK_NO_MATCHING_MEMBER"
        }

        test("without the group claim: refused, generic page (no hint about the group), LINK_MISS and PROVISION_GROUP_MISSING") {
            val email = "nogroup-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            callback(
                cfg = config(),
                nonce = nonce,
                idToken = { sign(claims(email = email, nonce = nonce, groups = null)) },
            ) { status, body, _ ->
                status shouldBe HttpStatusCode.Unauthorized
                body shouldContain "Kein zugeordnetes Mitgliedskonto"
                body shouldNotContain "Gruppe"
            }
            transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() } shouldBe 0
            events(type = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED).first()[OidcGuestLoginEventTable.reason] shouldBe
                "PROVISION_GROUP_MISSING"
            events(type = OidcLoginEventType.KEYCLOAK_LINK_MISS).first()[OidcGuestLoginEventTable.reason] shouldBe "NO_MATCHING_MEMBER"
        }

        test("the name page: a group member whose token carries no name gets the dedicated message and nothing is created") {
            val email = "noname-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            callback(
                cfg = config(),
                nonce = nonce,
                idToken = { sign(claims(email = email, nonce = nonce, name = null)) },
            ) { status, body, _ ->
                status shouldBe HttpStatusCode.Unauthorized
                body shouldContain "enthält keinen Namen"
            }
            transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() } shouldBe 0
        }

        test("the rate limit page, HTML-escaped, and nothing is created beyond the limit") {
            // earlier tests of this spec already created members through the provisioner: allow exactly ONE more
            val recent =
                transaction {
                    MemberStatusHistoryTable
                        .selectAll()
                        .where { MemberStatusHistoryTable.sourceKind eq "KEYCLOAK_JIT" }
                        .count()
                        .toInt()
                }
            val cfg = config(ratePerHour = recent + 1)
            val first = "rate1-" + Uuid.random().toString().take(10) + "@example.org"
            val second = "rate2-" + Uuid.random().toString().take(10) + "@example.org"
            val n1 = "n-${Uuid.random()}"
            val n2 = "n-${Uuid.random()}"
            callback(cfg = cfg, nonce = n1, idToken = { sign(claims(email = first, nonce = n1)) }) { status, _, _ ->
                status shouldBe HttpStatusCode.Found
            }
            trackNewMember(first)
            callback(cfg = cfg, nonce = n2, idToken = { sign(claims(email = second, nonce = n2)) }) { status, body, _ ->
                status shouldBe HttpStatusCode.Unauthorized
                body shouldContain "vorübergehend ausgelastet"
                body shouldNotContain "<script"
            }
            transaction { MemberTable.selectAll().where { MemberTable.email eq second }.count() } shouldBe 0
            events(type = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED).first()[OidcGuestLoginEventTable.reason] shouldBe
                "PROVISION_RATE_LIMITED"
        }

        test("a member with the same address is linked, not duplicated (the existing member wins over the group)") {
            val email = "wins-" + Uuid.random().toString().take(10) + "@example.org"
            val id = existingMember(email)
            val nonce = "n-${Uuid.random()}"
            val before = memberCount()
            callback(cfg = config(), nonce = nonce, idToken = { sign(claims(email = email.uppercase(), nonce = nonce)) }) { status, _, _ ->
                status shouldBe HttpStatusCode.Found
            }
            memberCount() shouldBe before
            (linkRow(id) != null) shouldBe true
        }

        test("a hostile role claim in the token does not raise the role") {
            val email = "role-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            callback(
                cfg = config(),
                nonce = nonce,
                idToken = {
                    sign(
                        claims(email = email, nonce = nonce, groups = listOf("admin", "apolda"), extra = mapOf("role" to "ADMIN")),
                    )
                },
            ) { status, _, _ -> status shouldBe HttpStatusCode.Found }
            val id = requireNotNull(trackNewMember(email))
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.role] } shouldBe
                AccountRole.MEMBER
        }

        // ── forged or foreign tokens: the group is only trusted from a VERIFIED ID token ──

        test("an unsigned (alg=none) token carrying the group creates nothing") {
            val email = "none-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            assertRefused(email, nonce, token = { PlainJWT(claims(email = email, nonce = nonce)).serialize() })
        }

        test("a token whose payload was changed after signing (group inserted, old signature) creates nothing") {
            val email = "tamper-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            val honest = sign(claims(email = email, nonce = nonce, groups = null)).split(".")
            val forgedPayload =
                Base64.getUrlEncoder().withoutPadding().encodeToString(claims(email = email, nonce = nonce).toString().toByteArray())
            assertRefused(email, nonce, token = { "${honest[0]}.$forgedPayload.${honest[2]}" })
        }

        test("a token for another audience, another issuer, a wrong nonce or already expired creates nothing") {
            fun refused(build: (String, String) -> JWTClaimsSet) {
                val email = "bad-" + Uuid.random().toString().take(10) + "@example.org"
                val nonce = "n-${Uuid.random()}"
                // the attempt stores `nonce`; the token may carry another one
                assertRefused(email, nonce, token = { sign(build(email, nonce)) })
            }
            refused { e, n -> claims(email = e, nonce = n, audience = "some-other-client") }
            refused { e, n -> claims(email = e, nonce = n, issuer = "https://evil.example.org") }
            refused { e, n -> claims(email = e, nonce = "not-$n") }
            refused { e, n -> claims(email = e, nonce = n, expiresInMinutes = -5) }
        }

        test("the group only in the ACCESS token (never decoded) creates nothing") {
            val email = "access-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            val accessWithGroup = sign(claims(email = email, nonce = nonce))
            assertRefused(
                email,
                nonce,
                token = { sign(claims(email = email, nonce = nonce, groups = null)) },
                accessToken = { accessWithGroup },
            )
        }

        test("an unverified address in an otherwise valid group token creates nothing") {
            val email = "unverified-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            assertRefused(email, nonce, token = { sign(claims(email = email, nonce = nonce, emailVerified = false)) })
        }

        test("group removal later: a linked member still logs in without the claim (existing behaviour), and nothing is created") {
            val email = "keep-" + Uuid.random().toString().take(10) + "@example.org"
            val nonce = "n-${Uuid.random()}"
            callback(
                cfg = config(),
                nonce = nonce,
                idToken = { sign(claims(email = email, nonce = nonce, subject = "keep-sub")) },
            ) { status, _, _ ->
                status shouldBe HttpStatusCode.Found
            }
            val id = requireNotNull(trackNewMember(email))
            val nonce2 = "n-${Uuid.random()}"
            val before = memberCount()
            callback(
                cfg = config(),
                nonce = nonce2,
                idToken = { sign(claims(email = email, nonce = nonce2, subject = "keep-sub", groups = null)) },
            ) { status, _, _ -> status shouldBe HttpStatusCode.Found }
            memberCount() shouldBe before
            events(id, OidcLoginEventType.KEYCLOAK_LOGIN_SUCCESS).size shouldBe 2
        }

        // ── profile sync through the route ──

        test("profile sync on: a linked member's name and (verified, free) address follow the token; the session is the NEW one") {
            val oldMail = "sync-old-" + Uuid.random().toString().take(8) + "@example.org"
            val newMail = "sync-new-" + Uuid.random().toString().take(8) + "@example.org"
            val id = existingMember(oldMail)
            linkMember(id, "sync-sub")
            val nonce = "n-${Uuid.random()}"
            val mailer = RecordingProvisioningMailer()
            callback(
                cfg = config(syncProfile = true),
                nonce = nonce,
                idToken = {
                    sign(
                        claims(email = newMail, nonce = nonce, subject = "sync-sub", name = "Synchronisierter Name", groups = null),
                    )
                },
                mailer = mailer,
                withSync = true,
            ) { status, _, setCookies ->
                status shouldBe HttpStatusCode.Found
                hasSession(setCookies) shouldBe true
            }
            fixture.emailOf(id) shouldBe newMail
            transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.displayName] } shouldBe
                "Synchronisierter Name"
            // the sync ran BEFORE the session was created, so exactly the fresh session of this login is alive
            fixture.liveSessions(id) shouldBe 1
            mailer.synced.single().email shouldBe oldMail
        }

        test("profile sync off: the same token changes nothing") {
            val oldMail = "nosync-old-" + Uuid.random().toString().take(8) + "@example.org"
            val id = existingMember(oldMail)
            linkMember(id, "nosync-sub")
            val nonce = "n-${Uuid.random()}"
            callback(
                cfg = config(syncProfile = false),
                nonce = nonce,
                idToken = {
                    sign(
                        claims(
                            email = "nosync-new-" + Uuid.random().toString().take(8) + "@example.org",
                            nonce = nonce,
                            subject = "nosync-sub",
                            name = "Anders",
                        ),
                    )
                },
                withSync = false,
            ) { status, _, _ -> status shouldBe HttpStatusCode.Found }
            fixture.emailOf(id) shouldBe oldMail
        }

        test("a sync that blows up never blocks the login") {
            val oldMail = "boom-" + Uuid.random().toString().take(8) + "@example.org"
            val id = existingMember(oldMail)
            linkMember(id, "boom-sub")
            val nonce = "n-${Uuid.random()}"
            // a notifier that throws on the warning to the OLD address must not matter
            callback(
                cfg = config(syncProfile = true),
                nonce = nonce,
                idToken = {
                    sign(
                        claims(
                            email = "boom-new-" + Uuid.random().toString().take(8) + "@example.org",
                            nonce = nonce,
                            subject = "boom-sub",
                        ),
                    )
                },
                mailer = RecordingProvisioningMailer(failing = true),
                withSync = true,
            ) { status, _, _ -> status shouldBe HttpStatusCode.Found }
            (fixture.emailOf(id) != oldMail) shouldBe true
        }
    })
