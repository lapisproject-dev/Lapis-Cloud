package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.StandardAssociationChart
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.206. `createStandardChart` and `getFinanceSetupStatus` over a real `testApplication`. The database
 * is shared with other specs, so [beforeSpec] first removes every standard-number account that nothing
 * references (a failing delete just means "stays existing") and the assertions are RELATIVE to what exists.
 */
class StandardChartServiceTest :
    FunSpec({
        val chartNumbers = StandardAssociationChart.accounts.map { it.accountNumber }
        val createdMemberIds = mutableListOf<Uuid>()
        val createdAccountNumbers = mutableSetOf<String>()

        fun existingNumbers(): Set<String> =
            transaction { LedgerAccountTable.selectAll().map { it[LedgerAccountTable.accountNumber] }.toSet() }

        fun chartAuditCount(): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                    .count { StandardAssociationChart.VERSION in (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    .toLong()
            }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Chart Testmitglied"
                    it[email] = "chart-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        beforeSpec {
            DatabaseConfig.connect()
            for (number in chartNumbers) {
                runCatching { transaction { LedgerAccountTable.deleteWhere { LedgerAccountTable.accountNumber eq number } } }
            }
        }

        afterSpec {
            transaction {
                // Only what this spec created (the numbers absent before the first call).
                if (createdAccountNumbers.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.accountNumber inList createdAccountNumbers.toList() }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun io.ktor.server.application.Application.chartRoutes() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            }
            routing {
                post("/test/chart") {
                    val r = AccountingService(call).createStandardChart()
                    call.respondText("${r.created.joinToString(",")}|${r.skipped.joinToString(",")}")
                }
                get("/test/setup-status") {
                    val s = AccountingService(call).getFinanceSetupStatus()
                    call.respondText("${s.activeAccountCount}|${s.mappingComplete}|${s.steps.size}")
                }
            }
        }

        fun parse(body: String): Pair<List<String>, List<String>> {
            val (c, s) = body.split("|")
            return c.split(",").filter { it.isNotEmpty() } to s.split(",").filter { it.isNotEmpty() }
        }

        test("first call creates exactly the missing standard accounts and writes ONE aggregated audit entry") {
            testApplication {
                application { chartRoutes() }
                val before = existingNumbers()
                val auditsBefore = chartAuditCount()
                val treasurer = createMember(AccountRole.TREASURER)

                val (created, skipped) =
                    parse(client.post("/test/chart") { header("X-Member-Id", treasurer.toString()) }.bodyAsText())

                created.toSet() shouldBe chartNumbers.filter { it !in before }.toSet()
                skipped.toSet() shouldBe chartNumbers.filter { it in before }.toSet()
                (created + skipped).size shouldBe chartNumbers.size
                createdAccountNumbers += created
                // On a shared database the whole chart may already exist (demo seed referenced by postings); then no entry is written.
                chartAuditCount() shouldBe auditsBefore + (if (created.isEmpty()) 0 else 1)
                val chartRows =
                    transaction {
                        StandardAssociationChart.accounts.associate { a ->
                            a.accountNumber to
                                LedgerAccountTable
                                    .selectAll()
                                    .where { LedgerAccountTable.accountNumber eq a.accountNumber }
                                    .single()
                        }
                    }
                chartRows.getValue("16000")[LedgerAccountTable.isCashRegister] shouldBe true
                chartRows.getValue("21000")[LedgerAccountTable.reserveType] shouldBe
                    network.lapis.cloud.shared.domain.ReserveType.PROJEKTRUECKLAGE
                chartRows.getValue("21500")[LedgerAccountTable.reserveType] shouldBe
                    network.lapis.cloud.shared.domain.ReserveType.FREIE_RUECKLAGE
                // The hash chain itself is verified by the audit specs; other specs of the shared database may leave tampered rows behind.
            }
        }

        test("second call is a no-op: nothing created, no extra audit entry, row count unchanged") {
            testApplication {
                application { chartRoutes() }
                val admin = createMember(AccountRole.ADMIN)
                val auditsBefore = chartAuditCount()
                val rowsBefore = existingNumbers().size

                val (created, skipped) = parse(client.post("/test/chart") { header("X-Member-Id", admin.toString()) }.bodyAsText())

                created.shouldBeEmpty()
                skipped.size shouldBe chartNumbers.size
                chartAuditCount() shouldBe auditsBefore
                existingNumbers().size shouldBe rowsBefore
            }
        }

        test("an existing INACTIVE account with another name is skipped and left untouched") {
            testApplication {
                application { chartRoutes() }
                val admin = createMember(AccountRole.ADMIN)
                transaction {
                    LedgerAccountTable.update({ LedgerAccountTable.accountNumber eq "18000" }) {
                        it[name] = "Eigenes Girokonto"
                        it[active] = false
                    }
                }
                val (created, skipped) = parse(client.post("/test/chart") { header("X-Member-Id", admin.toString()) }.bodyAsText())

                created.shouldBeEmpty()
                ("18000" in skipped) shouldBe true
                val row = transaction { LedgerAccountTable.selectAll().where { LedgerAccountTable.accountNumber eq "18000" }.single() }
                row[LedgerAccountTable.name] shouldBe "Eigenes Girokonto"
                row[LedgerAccountTable.active] shouldBe false
                transaction {
                    LedgerAccountTable.update({ LedgerAccountTable.accountNumber eq "18000" }) {
                        it[name] = StandardAssociationChart.accounts.first { a -> a.accountNumber == "18000" }.name
                        it[active] = true
                    }
                }
            }
        }

        test("roles: TREASURER and ADMIN ok for both calls, BOARD may only read, MEMBER and anonymous are refused") {
            testApplication {
                application { chartRoutes() }
                val board = createMember(AccountRole.BOARD)
                val member = createMember(AccountRole.MEMBER)
                val treasurer = createMember(AccountRole.TREASURER)

                client.post("/test/chart") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/test/chart") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/test/chart").status shouldBe HttpStatusCode.Unauthorized
                client.get("/test/setup-status") { header("X-Member-Id", treasurer.toString()) }.status shouldBe HttpStatusCode.OK
                val boardRead = client.get("/test/setup-status") { header("X-Member-Id", board.toString()) }
                boardRead.status shouldBe HttpStatusCode.OK
                boardRead.bodyAsText().split("|")[2] shouldBe "6"
                client
                    .get("/test/setup-status") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("two parallel calls together create each missing account exactly once") {
            testApplication {
                application { chartRoutes() }
                val admin = createMember(AccountRole.ADMIN)
                // Make the chart "missing" again for the numbers this spec created.
                transaction {
                    if (createdAccountNumbers.isNotEmpty()) {
                        LedgerAccountTable.deleteWhere { LedgerAccountTable.accountNumber inList createdAccountNumbers.toList() }
                    }
                }
                val before = existingNumbers()
                val missing = chartNumbers.filter { it !in before }

                val results =
                    coroutineScope {
                        (1..2).map { async { client.post("/test/chart") { header("X-Member-Id", admin.toString()) } } }.awaitAll()
                    }

                results.forEach { it.status shouldBe HttpStatusCode.OK }
                val createdTotal = results.flatMap { parse(it.bodyAsText()).first }
                createdTotal.sorted() shouldBe missing.sorted()
                existingNumbers().containsAll(chartNumbers) shouldBe true
            }
        }
    })
