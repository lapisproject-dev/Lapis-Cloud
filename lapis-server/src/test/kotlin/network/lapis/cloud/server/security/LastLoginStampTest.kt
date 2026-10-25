package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LoginMethod
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.207 -- `account.last_login_at/_method` is stamped by successful logins only: the password route through the real
 * wired `module()`, the session store directly for the other two methods (their routes call it with exactly that argument, pinned by
 * [LoginMethodStampTripwireTest]); failures, `resolve` and an unstamped `createSession` never write it.
 */
class LastLoginStampTest :
    FunSpec({
        val created = mutableListOf<Uuid>()
        val password = "Stamp-Test-Passwort-2026!x"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                SessionTable.deleteWhere { SessionTable.memberId inList created }
                AccountTable.deleteWhere { AccountTable.memberId inList created }
                MemberTable.deleteWhere { MemberTable.id inList created }
            }
        }

        fun newAccount(status: MemberStatus = MemberStatus.ACTIVE): Pair<Uuid, String> {
            val id = Uuid.random()
            val email = "stamp-$id@example.org"
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Stempel Test"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash(password)
                }
            }
            created += id
            return id to email
        }

        fun stamp(id: Uuid) =
            transaction {
                AccountTable.selectAll().where { AccountTable.memberId eq id }.single().let {
                    it[AccountTable.lastLoginAt] to it[AccountTable.lastLoginMethod]
                }
            }

        test("a fresh account has no stamp; a successful password login stamps time and PASSWORD") {
            val (id, email) = newAccount()
            stamp(id) shouldBe (null to null)
            testApplication {
                application { module() }
                client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }.status shouldBe
                    HttpStatusCode.OK
            }
            val (at, method) = stamp(id)
            at shouldNotBe null
            method shouldBe LoginMethod.PASSWORD
        }

        test("a wrong password stamps nothing") {
            val (id, email) = newAccount()
            testApplication {
                application { module() }
                client.post("/api/auth/login") { setBody("""{"email":"$email","password":"wrong-password-123"}""") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
            stamp(id) shouldBe (null to null)
        }

        test("a blocked (non-active) member cannot log in and gets no stamp") {
            val (id, email) = newAccount(status = MemberStatus.DECEASED)
            testApplication {
                application { module() }
                client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }.status shouldNotBe
                    HttpStatusCode.OK
            }
            stamp(id) shouldBe (null to null)
        }

        test("a second login overwrites time and method") {
            val (id, _) = newAccount()
            SessionStore.createSession(memberId = id, loginMethod = LoginMethod.PASSWORD)
            val first = stamp(id)
            Thread.sleep(5)
            SessionStore.createSession(memberId = id, loginMethod = LoginMethod.KEYCLOAK)
            val second = stamp(id)
            second.second shouldBe LoginMethod.KEYCLOAK
            (second.first!! >= first.first!!) shouldBe true
        }

        test("OIDC_FEDERATION is stored, and resolve / an unstamped createSession never write") {
            val (id, _) = newAccount()
            val unstamped = SessionStore.createSession(id)
            stamp(id) shouldBe (null to null)
            SessionStore.resolve(unstamped.rawToken) shouldNotBe null
            stamp(id) shouldBe (null to null)
            SessionStore.createSession(memberId = id, loginMethod = LoginMethod.OIDC_FEDERATION)
            stamp(id).second shouldBe LoginMethod.OIDC_FEDERATION
            val before = stamp(id)
            SessionStore.resolve(unstamped.rawToken)
            stamp(id) shouldBe before
        }
    })
