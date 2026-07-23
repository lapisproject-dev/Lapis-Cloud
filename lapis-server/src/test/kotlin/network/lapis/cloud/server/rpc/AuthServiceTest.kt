package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.InvalidPasswordException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val INITIAL_PASSWORD = "initial-strong-password-1"

/**
 * Exercises [AuthService] end to end against real [SessionStore] sessions (not the trusted-header
 * fallback -- authenticated via `Authorization: Bearer <rawToken>`, see
 * [network.lapis.cloud.server.security.extractSessionToken]). Mirrors [PeerTransferServiceTest]'s
 * house style: fresh test members created per test, [afterSpec] hard-deletes everything created.
 */
class AuthServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { cleanUpAuthServiceTestData(createdMemberIds) }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Auth-Service Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash(INITIAL_PASSWORD)
                }
            }
            createdMemberIds += id
            return id
        }

        fun storedPasswordHashOf(memberId: Uuid): String? =
            transaction {
                AccountTable.selectAll().where { AccountTable.memberId eq memberId }.single()[AccountTable.passwordHash]
            }

        test("changePassword: happy path replaces the hash, keeps the CALLER's session, revokes every OTHER session") {
            testApplication {
                application {
                    install(StatusPages) { installAuthServiceExceptionHandlers() }
                    routing { registerAuthServiceTestRoutes() }
                }

                val member = createTestMember("auth-service-happy@example.org")
                val ownSession = SessionStore.createSession(member)
                val otherDeviceSession = SessionStore.createSession(member)

                val response =
                    client.post("/test/change-password") {
                        header("Authorization", "Bearer ${ownSession.rawToken}")
                        header("X-Current-Password", INITIAL_PASSWORD)
                        header("X-New-Password", "a-brand-new-strong-password-2")
                    }
                response.status shouldBe HttpStatusCode.OK

                PasswordHasher.verify("a-brand-new-strong-password-2", storedPasswordHashOf(member)) shouldBe true
                PasswordHasher.verify(INITIAL_PASSWORD, storedPasswordHashOf(member)) shouldBe false

                SessionStore.resolve(ownSession.rawToken).shouldNotBeNull()
                SessionStore.resolve(otherDeviceSession.rawToken).shouldBeNull()
            }
        }

        test("changePassword: wrong currentPassword is rejected, password and every session stay unchanged") {
            testApplication {
                application {
                    install(StatusPages) { installAuthServiceExceptionHandlers() }
                    routing { registerAuthServiceTestRoutes() }
                }

                val member = createTestMember("auth-service-wrong-current@example.org")
                val session = SessionStore.createSession(member)
                val otherSession = SessionStore.createSession(member)
                val originalHash = storedPasswordHashOf(member)

                val response =
                    client.post("/test/change-password") {
                        header("Authorization", "Bearer ${session.rawToken}")
                        header("X-Current-Password", "definitely-the-wrong-password")
                        header("X-New-Password", "a-brand-new-strong-password-2")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized

                storedPasswordHashOf(member) shouldBe originalHash
                SessionStore.resolve(session.rawToken).shouldNotBeNull()
                SessionStore.resolve(otherSession.rawToken).shouldNotBeNull()
            }
        }

        test("changePassword: a weak newPassword is rejected by PasswordPolicy, old password still works") {
            testApplication {
                application {
                    install(StatusPages) { installAuthServiceExceptionHandlers() }
                    routing { registerAuthServiceTestRoutes() }
                }

                val member = createTestMember("auth-service-weak-new@example.org")
                val session = SessionStore.createSession(member)

                val response =
                    client.post("/test/change-password") {
                        header("Authorization", "Bearer ${session.rawToken}")
                        header("X-Current-Password", INITIAL_PASSWORD)
                        header("X-New-Password", "short")
                    }
                response.status shouldBe HttpStatusCode.BadRequest

                PasswordHasher.verify(INITIAL_PASSWORD, storedPasswordHashOf(member)) shouldBe true
            }
        }

        test("changePassword: unauthenticated (no session token at all) is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installAuthServiceExceptionHandlers() }
                    routing { registerAuthServiceTestRoutes() }
                }

                val response =
                    client.post("/test/change-password") {
                        header("X-Current-Password", INITIAL_PASSWORD)
                        header("X-New-Password", "a-brand-new-strong-password-2")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("getSessionInfo: reflects the resolved session's member/role, and a real, non-placeholder expiry") {
            testApplication {
                application {
                    install(StatusPages) { installAuthServiceExceptionHandlers() }
                    routing { registerAuthServiceTestRoutes() }
                }

                val member = createTestMember("auth-service-whoami@example.org")
                val issued = SessionStore.createSession(member)

                val response = client.get("/test/session-info") { header("Authorization", "Bearer ${issued.rawToken}") }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldBe "$member:MEMBER:${issued.expiresAt}"
            }
        }
    })

private fun cleanUpAuthServiceTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installAuthServiceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<InvalidPasswordException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<WeakPasswordException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

private fun Route.registerAuthServiceTestRoutes() {
    post("/test/change-password") {
        val currentPassword = call.request.headers["X-Current-Password"] ?: ""
        val newPassword = call.request.headers["X-New-Password"] ?: ""
        AuthService(call).changePassword(currentPassword, newPassword)
        call.respondText("OK")
    }
    get("/test/session-info") {
        val info = AuthService(call).getSessionInfo()
        call.respondText("${info.memberId}:${info.role}:${info.expiresAt}")
    }
}
