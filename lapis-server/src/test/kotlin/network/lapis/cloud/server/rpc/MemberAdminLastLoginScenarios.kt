package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LoginMethod
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.207 -- the roster column "Letzte Anmeldung" on H2 AND PostgreSQL: ADMIN sees `lastLoginAt/lastLoginMethod` (list and the
 * reloaded single row after a mutation is covered by the same mapper), every other role gets `null` from the server, the sort by last
 * login is ADMIN-only (anyone else falls back to NAME_ASC), ascending puts never-signed-in first, descending last, ties break by id, and
 * pagination across a page boundary has neither duplicates nor gaps. A member without an account yields null without a crash.
 */
abstract class MemberAdminLastLoginScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val prefix = "LL${Uuid.random().toString().take(6)}"
        val actorTag = Uuid.random().toString().take(6)
        val created = mutableListOf<Uuid>()
        val actors = mutableMapOf<AccountRole, Uuid>()

        fun newMember(
            name: String,
            role: AccountRole?,
            lastLoginAt: LocalDateTime? = null,
            method: LoginMethod? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = name
                    it[email] = "$name-$id@example.org".replace(' ', '-')
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                if (role != null) {
                    AccountTable.insert {
                        it[AccountTable.id] = Uuid.random()
                        it[memberId] = id
                        it[AccountTable.role] = role
                        it[AccountTable.lastLoginAt] = lastLoginAt
                        it[lastLoginMethod] = method
                    }
                }
            }
            created += id
            return id
        }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            AccountRole.entries.forEach { actors[it] = newMember("Actor-${it.name}-$actorTag", it) }
        }
        installLaneGuards(db = db)
        afterSpec {
            transaction {
                AccountTable.deleteWhere { AccountTable.memberId inList created }
                MemberTable.deleteWhere { MemberTable.id inList created }
            }
            db.deactivate()
        }

        val routes: io.ktor.server.application.Application.() -> Unit = {
            install(StatusPages) {
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            }
            routing {
                get("/test/roster") {
                    val sort = MemberAdminSort.valueOf(call.request.queryParameters["sort"] ?: "NAME_ASC")
                    val limit = call.request.queryParameters["limit"]?.toInt() ?: 50
                    val offset = call.request.queryParameters["offset"]?.toInt() ?: 0
                    val service =
                        MemberService(
                            call = call,
                            passwordResetMailer = FakePasswordResetMailer(),
                            adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                            smtpConfigState = SmtpConfigState.NotConfigured,
                            adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                            adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                            adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                            memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                            memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                            regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
                        )
                    val page =
                        runBlocking {
                            service.listMembersForAdministration(
                                MemberAdminQuery(search = prefix.lowercase(), sort = sort, limit = limit, offset = offset),
                            )
                        }
                    call.respondText(
                        page.rows.joinToString(";") {
                            "${it.displayName.substringAfter("$prefix-")}=${it.lastLoginAt}/${it.lastLoginMethod}"
                        },
                    )
                }
            }
        }

        val t1 = LocalDateTime(2026, 10, 1, 8, 0, 0)
        val t2 = LocalDateTime(2026, 10, 2, 9, 30, 0)
        val t3 = LocalDateTime(2026, 10, 3, 12, 0, 0)

        fun seedRoster() {
            newMember("$prefix-a-never", AccountRole.MEMBER)
            newMember("$prefix-b-old", AccountRole.MEMBER, t1, LoginMethod.PASSWORD)
            newMember("$prefix-c-new", AccountRole.MEMBER, t3, LoginMethod.OIDC_FEDERATION)
            newMember("$prefix-d-mid", AccountRole.MEMBER, t2, LoginMethod.KEYCLOAK)
            newMember("$prefix-e-noaccount", null)
            newMember("$prefix-f-never", AccountRole.MEMBER)
        }

        fun names(body: String) = if (body.isEmpty()) emptyList() else body.split(";").map { it.substringBefore("=") }

        test("ADMIN sees time and method; a member without account and a never-signed-in member yield null/null") {
            seedRoster()
            testApplication {
                application(routes)
                val body = client.get("/test/roster") { header("X-Member-Id", actors.getValue(AccountRole.ADMIN).toString()) }.bodyAsText()
                val rows = body.split(";").associate { it.substringBefore("=") to it.substringAfter("=") }
                rows["b-old"] shouldBe "$t1/PASSWORD"
                rows["c-new"] shouldBe "$t3/OIDC_FEDERATION"
                rows["d-mid"] shouldBe "$t2/KEYCLOAK"
                rows["a-never"] shouldBe "null/null"
                rows["e-noaccount"] shouldBe "null/null"
            }
        }

        test("BOARD and TREASURER get null/null for everybody even though the database holds values") {
            testApplication {
                application(routes)
                for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER)) {
                    val body = client.get("/test/roster") { header("X-Member-Id", actors.getValue(role).toString()) }.bodyAsText()
                    body.split(";").all { it.substringAfter("=") == "null/null" } shouldBe true
                }
            }
        }

        test("a non-admin asking for the last-login sort gets exactly the NAME_ASC order (no oracle, no error)") {
            testApplication {
                application(routes)
                for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER)) {
                    val id = actors.getValue(role).toString()
                    val byName = names(client.get("/test/roster?sort=NAME_ASC") { header("X-Member-Id", id) }.bodyAsText())
                    for (sort in listOf("LAST_LOGIN_ASC", "LAST_LOGIN_DESC")) {
                        names(client.get("/test/roster?sort=$sort") { header("X-Member-Id", id) }.bodyAsText()) shouldBe byName
                    }
                }
            }
        }

        test("ADMIN: ascending puts never-signed-in first (ties by id), descending puts them last") {
            testApplication {
                application(routes)
                val admin = actors.getValue(AccountRole.ADMIN).toString()
                val asc = names(client.get("/test/roster?sort=LAST_LOGIN_ASC") { header("X-Member-Id", admin) }.bodyAsText())
                asc.drop(3) shouldBe listOf("b-old", "d-mid", "c-new")
                asc.take(3).toSet() shouldBe setOf("a-never", "e-noaccount", "f-never")
                val desc = names(client.get("/test/roster?sort=LAST_LOGIN_DESC") { header("X-Member-Id", admin) }.bodyAsText())
                desc.take(3) shouldBe listOf("c-new", "d-mid", "b-old")
                desc.drop(3).toSet() shouldBe setOf("a-never", "e-noaccount", "f-never")
                // the tie order among the NULL rows is the same (id) in both directions
                desc.drop(3) shouldBe asc.take(3)
            }
        }

        test("pagination across a page boundary has no duplicates and no gaps") {
            testApplication {
                application(routes)
                val admin = actors.getValue(AccountRole.ADMIN).toString()
                for (sort in listOf("LAST_LOGIN_ASC", "LAST_LOGIN_DESC")) {
                    val all = names(client.get("/test/roster?sort=$sort&limit=50") { header("X-Member-Id", admin) }.bodyAsText())
                    val paged =
                        (0 until all.size step 2).flatMap { off ->
                            names(client.get("/test/roster?sort=$sort&limit=2&offset=$off") { header("X-Member-Id", admin) }.bodyAsText())
                        }
                    paged shouldBe all
                    all.toSet().size shouldBe all.size
                }
            }
        }

        test("a MEMBER without roster access is still refused") {
            testApplication {
                application(routes)
                val status = client.get("/test/roster") { header("X-Member-Id", actors.getValue(AccountRole.MEMBER).toString()) }.status
                status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a later update of the stamp is reflected (last write wins)") {
            val id = newMember("$prefix-g-twice", AccountRole.MEMBER, t1, LoginMethod.PASSWORD)
            transaction {
                AccountTable.update({ AccountTable.memberId eq id }) {
                    it[lastLoginAt] = t3
                    it[lastLoginMethod] = LoginMethod.KEYCLOAK
                }
            }
            testApplication {
                application(routes)
                val body = client.get("/test/roster") { header("X-Member-Id", actors.getValue(AccountRole.ADMIN).toString()) }.bodyAsText()
                body.split(";").first { it.startsWith("g-twice=") } shouldBe "g-twice=$t3/KEYCLOAK"
            }
        }
    })
