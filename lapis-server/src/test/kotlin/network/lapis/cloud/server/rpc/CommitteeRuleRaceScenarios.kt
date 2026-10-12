package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val RACE_ROUNDS = 6

/**
 * Welle V1.9.112 -- the rule switch under real concurrency (H2 and PostgreSQL run the same assertions; the Postgres lane also fails on a
 * deadlock). Two races: (1) committee A is switched to rule mode while committee B stores a rule that references A -- exactly one of them
 * may win (otherwise a rule would reference a committee without seats and silently address nobody); (2) a manual seat is added while the
 * same committee is switched to rule mode -- afterwards a rule committee never holds an open seat row. Callers authenticate through real
 * sessions, so the lane needs no trusted-header shortcut.
 */
abstract class CommitteeRuleRaceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            transaction { SessionTable.deleteWhere { SessionTable.memberId inList fx.memberIds } }
            rc.cleanUp()
            fx.cleanUp()
            db.deactivate()
        }

        fun Application.installRoutes() {
            install(StatusPages) {
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing {
                post("/t/set/{id}") {
                    GovernanceService(call = call).setCommitteeMembershipRule(
                        committeeId = call.parameters["id"]!!,
                        mode = CommitteeMembershipMode.RULE,
                        ruleJson = call.receiveText(),
                    )
                    call.respondText("ok")
                }
                post("/t/add/{id}/{memberId}") {
                    GovernanceService(call = call).addCommitteeMember(
                        committeeId = call.parameters["id"]!!,
                        input =
                            CommitteeMembershipInput(
                                memberId = call.parameters["memberId"]!!,
                                role = CommitteeRole.MEMBER,
                                since = LocalDate(2026, 1, 1),
                            ),
                    )
                    call.respondText("ok")
                }
            }
        }

        suspend fun HttpClient.send(
            token: String,
            path: String,
            body: String = "",
        ): HttpResponse =
            post(path) {
                header("Authorization", "Bearer $token")
                setBody(body)
            }

        test("the switch of A races a rule of B that references A: exactly one wins, never both") {
            val board = fx.member(role = AccountRole.BOARD)
            val token = SessionStore.createSession(board).rawToken
            testApplication {
                application { installRoutes() }
                repeat(RACE_ROUNDS) {
                    val base = fx.committee()
                    fx.seat(committee = base, member = fx.member())
                    val a = fx.committee()
                    val b = fx.committee()
                    val statuses =
                        coroutineScope {
                            listOf(
                                async { client.send(token, "/t/set/$a", rc.seatedIn(base)).status },
                                async { client.send(token, "/t/set/$b", rc.seatedIn(a)).status },
                            ).awaitAll()
                        }
                    statuses.count { it == HttpStatusCode.OK } shouldBe 1
                    statuses.filter { it != HttpStatusCode.OK }.forEach {
                        listOf(HttpStatusCode.BadRequest, HttpStatusCode.Conflict) shouldContain
                            it
                    }
                    val modeA =
                        transaction { CommitteeTable.selectAll().where { CommitteeTable.id eq a }.single()[CommitteeTable.membershipMode] }
                    val modeB =
                        transaction { CommitteeTable.selectAll().where { CommitteeTable.id eq b }.single()[CommitteeTable.membershipMode] }
                    // the forbidden end state: B stores a rule on A while A is rule-based
                    (modeA == CommitteeMembershipMode.RULE && modeB == CommitteeMembershipMode.RULE) shouldBe false
                }
            }
        }

        test("a manual seat is added while the committee is switched to rule mode: a rule committee never keeps an open seat") {
            val board = fx.member(role = AccountRole.BOARD)
            val token = SessionStore.createSession(board).rawToken
            testApplication {
                application { installRoutes() }
                repeat(RACE_ROUNDS) {
                    val base = fx.committee()
                    fx.seat(committee = base, member = fx.member())
                    val committee = fx.committee()
                    fx.seat(committee = committee, member = fx.member())
                    val newcomer = fx.member()
                    val statuses =
                        coroutineScope {
                            listOf(
                                async { client.send(token, "/t/set/$committee", rc.seatedIn(base)).status },
                                async { client.send(token, "/t/add/$committee/$newcomer").status },
                            ).awaitAll()
                        }
                    statuses[0] shouldBe HttpStatusCode.OK
                    listOf(HttpStatusCode.OK, HttpStatusCode.Conflict) shouldContain statuses[1]
                    val open =
                        transaction {
                            CommitteeMembershipTable
                                .selectAll()
                                .where { (CommitteeMembershipTable.committeeId eq committee) and CommitteeMembershipTable.until.isNull() }
                                .count()
                        }
                    open shouldBe 0
                }
            }
        }
    })

class CommitteeRuleRaceTest : CommitteeRuleRaceScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CommitteeRuleRacePostgresTest : CommitteeRuleRaceScenarios(TestDatabase.Postgres())
