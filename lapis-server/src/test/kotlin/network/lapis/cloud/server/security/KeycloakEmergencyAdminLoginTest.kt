package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.routes.registerAuthRoutes
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_EMAIL = "amara.admin@example.org"
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern" -- the vault spec's decision 3
 * ("Notfall-Login fuer Admins bleibt bestehen") pinned as a regression test on `/api/auth/login`.
 * The most important test in this file is the LAST one: `/api/auth/login` never touches Keycloak
 * discovery/metadata at all (see `AuthRoutes.kt`'s `keycloakBlocksLogin` check -- it only reads
 * [KeycloakConfig] fields, no network call), so a completely unreachable Keycloak issuer must not
 * affect admin login in the slightest.
 */
class KeycloakEmergencyAdminLoginTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpTestData(createdMemberIds) }

        fun createMemberWithPassword(
            email: String,
            password: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Keycloak-Emergency-Login Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                    it[passwordHash] = PasswordHasher.hash(password)
                }
            }
            createdMemberIds += id
            return id
        }

        /** A fully-operational (isOperational=true) Keycloak config -- never reads real process env vars. */
        fun keycloakConfig(
            emergencyAdminLoginEnabled: Boolean = true,
            issuerUrl: String = "https://unreachable-keycloak.invalid.example",
        ): KeycloakConfig {
            val values =
                mapOf(
                    KeycloakConfig.ENV_ENABLED to "true",
                    KeycloakConfig.ENV_ISSUER_URL to issuerUrl,
                    KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
                    KeycloakConfig.ENV_CLIENT_SECRET to "test-client-secret",
                    KeycloakConfig.ENV_EMERGENCY_ADMIN_LOGIN_ENABLED to emergencyAdminLoginEnabled.toString(),
                )
            return KeycloakConfig.load(env = { name -> values[name] })
        }

        test("Keycloak mode + ADMIN + correct password -> 200 with a session") {
            testApplication {
                val email = "keycloak-emergency-admin-${Uuid.random()}@example.org"
                val password = "a-genuinely-strong-password-1"
                createMemberWithPassword(email = email, password = password, role = AccountRole.ADMIN)

                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = keycloakConfig(),
                        )
                    }
                }

                val response = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("Keycloak mode + MEMBER + correct password -> 401, byte-identical to a wrong password (enumeration hardening)") {
            testApplication {
                val email = "keycloak-emergency-member-${Uuid.random()}@example.org"
                val password = "a-genuinely-strong-password-1"
                createMemberWithPassword(email = email, password = password, role = AccountRole.MEMBER)

                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = keycloakConfig(),
                        )
                    }
                }

                val correctPasswordResponse = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }
                val wrongPasswordResponse =
                    client.post("/api/auth/login") { setBody("""{"email":"$email","password":"definitely-wrong"}""") }

                correctPasswordResponse.status shouldBe HttpStatusCode.Unauthorized
                correctPasswordResponse.status shouldBe wrongPasswordResponse.status
                correctPasswordResponse.bodyAsText() shouldBe wrongPasswordResponse.bodyAsText()
            }
        }

        test("Keycloak mode + emergencyAdminLoginEnabled=false -> even ADMIN gets 401") {
            testApplication {
                val email = "keycloak-emergency-disabled-admin-${Uuid.random()}@example.org"
                val password = "a-genuinely-strong-password-1"
                createMemberWithPassword(email = email, password = password, role = AccountRole.ADMIN)

                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = keycloakConfig(emergencyAdminLoginEnabled = false),
                        )
                    }
                }

                val response = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "Keycloak mode with the issuer UNREACHABLE -- ADMIN login is completely unaffected (the decision-3 regression test)",
        ) {
            testApplication {
                // Deliberately a syntactically valid but entirely non-resolvable/non-existent host
                // -- AuthRoutes' login handler must never attempt to reach it (see class KDoc):
                // the emergency admin path is a pure config check, no KeycloakOidcMetadata/discovery
                // call anywhere on this request path.
                val unreachableConfig = keycloakConfig(issuerUrl = "https://this-host-does-not-exist.invalid.example")

                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = unreachableConfig,
                        )
                    }
                }

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().contains(ADMIN_ID) shouldBe true
            }
        }

        // ── /api/auth/password-reset gating ─────────────────────────────────────────

        test("Keycloak mode: password-reset/request returns 404, no feature-existence leak") {
            testApplication {
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = keycloakConfig(),
                        )
                    }
                }

                val response = client.post("/api/auth/password-reset/request") { setBody("""{"email":"$ADMIN_EMAIL"}""") }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("Keycloak mode: password-reset/confirm returns 404") {
            testApplication {
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = keycloakConfig(),
                        )
                    }
                }

                val response =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"whatever","newPassword":"a-brand-new-strong-password-2"}""")
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("Keycloak DISABLED (default config): login/password-reset behave exactly as before (regression guard)") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoOpPasswordResetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            keycloakConfig = KeycloakConfig.load(env = { null }),
                        )
                    }
                }

                val loginResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                loginResponse.status shouldBe HttpStatusCode.OK

                val resetResponse = client.post("/api/auth/password-reset/request") { setBody("""{"email":"$ADMIN_EMAIL"}""") }
                resetResponse.status shouldBe HttpStatusCode.OK
            }
        }
    })

private object NoOpPasswordResetMailer : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus = DeliveryStatus.SENT
}

private fun cleanUpTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}
