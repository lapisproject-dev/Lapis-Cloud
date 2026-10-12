package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.122 -- "exactly one day line per (report, day)" is an APPLICATION rule (no unique index,
 * see V97), upheld by `SELECT ... FOR UPDATE` on the report row that `saveDays`/`updateDraft` take
 * before they touch the day lines. Proven here with real parallel requests, on H2 (smoke) and on a
 * real PostgreSQL (the actual proof, including "no deadlock").
 */
abstract class TravelExpenseConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = PollTestData()
        val createdReportIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            if (!db.isPostgres) {
                transaction {
                    TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList createdReportIds }
                    TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList createdReportIds }
                }
                data.cleanUp()
            }
            db.deactivate()
        }

        fun dayLines(reportId: String) =
            transaction {
                TravelExpenseLineTable
                    .selectAll()
                    .where {
                        (TravelExpenseLineTable.reportId eq Uuid.parse(reportId)) and
                            (TravelExpenseLineTable.kind eq TravelExpenseLineKind.DAY_ALLOWANCE)
                    }.map { it[TravelExpenseLineTable.dayDate] }
            }

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.draft(
            member: Uuid,
            from: String,
            to: String,
        ): String =
            post("/test/travelexpense/create?subjectMemberId=$member&purpose=Reise&from=$from&to=$to&fillDays=false") {
                header("X-Member-Id", member.toString())
            }.bodyAsText().split("|")[0].also { createdReportIds += Uuid.parse(it) }

        test("parallel saveDays on one draft never produce a duplicate or missing day line") {
            withApp {
                val member = data.member(label = "concurrency-days", status = MemberStatus.ACTIVE)
                val id = client.draft(member, "2026-01-10", "2026-01-14")
                val statuses =
                    coroutineScope {
                        (1..6)
                            .map { i ->
                                async(Dispatchers.IO) {
                                    client
                                        .post("/test/travelexpense/savedays?id=$id&days=2026-01-10@0$i:00-,2026-01-14@-0$i:30") {
                                            header("X-Member-Id", member.toString())
                                        }.status.value
                                }
                            }.awaitAll()
                    }
                statuses.none { it >= 500 } shouldBe true
                (200 in statuses) shouldBe true
                val days = dayLines(id)
                days.size shouldBe 5
                days.toSet().size shouldBe 5
            }
        }

        test("parallel updateDraft period changes racing saveDays keep exactly one day line per day of the FINAL period") {
            withApp {
                val member = data.member(label = "concurrency-period", status = MemberStatus.ACTIVE)
                val id = client.draft(member, "2026-02-01", "2026-02-05")
                val periods = listOf("2026-02-05", "2026-02-03", "2026-02-04", "2026-02-02", "2026-02-05", "2026-02-03")
                val statuses =
                    coroutineScope {
                        periods
                            .flatMapIndexed { i, to ->
                                listOf(
                                    async(Dispatchers.IO) {
                                        client
                                            .post("/test/travelexpense/update?id=$id&from=2026-02-01&to=$to") {
                                                header("X-Member-Id", member.toString())
                                            }.status.value
                                    },
                                    async(Dispatchers.IO) {
                                        client
                                            .post("/test/travelexpense/savedays?id=$id&days=2026-02-01@0${i + 1}:00-") {
                                                header("X-Member-Id", member.toString())
                                            }.status.value
                                    },
                                )
                            }.awaitAll()
                    }
                // 200 = applied, 400/409 = a legitimately refused interleaving (e.g. a filled day outside the new period).
                statuses.none { it >= 500 } shouldBe true
                val finalTo =
                    transaction {
                        TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq Uuid.parse(id) }.single()[
                            TravelExpenseReportTable.travelTo,
                        ]
                    }
                val expectedDays = finalTo.toString().takeLast(2).toInt() // 2026-02-0N -> N days (1st..Nth)
                val days = dayLines(id)
                days.size shouldBe expectedDays
                days.toSet().size shouldBe expectedDays
            }
        }
    })

class TravelExpenseConcurrencyTest : TravelExpenseConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TravelExpenseConcurrencyPostgresTest : TravelExpenseConcurrencyScenarios(TestDatabase.Postgres())
