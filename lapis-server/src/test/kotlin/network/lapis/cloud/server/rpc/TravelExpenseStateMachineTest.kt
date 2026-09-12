package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Welle V1.4.11 -- illegal state transitions and booking idempotency for [TravelExpenseService].
 * Mirrors [ContributionReliefRequestTest]'s house style.
 */
class TravelExpenseStateMachineTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        fun setMapping(
            expenseAccountId: Uuid?,
            bankAccountId: Uuid?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelExpenseAccountId] = expenseAccountId
                    it[paymentBankAccountId] = bankAccountId
                    it[travelMileageRatePerKm] = BigDecimal("0.3000")
                    it[travelPerDiemRate] = BigDecimal("14.00")
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            setMapping(expenseAccountId = null, bankAccountId = null)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelMileageRatePerKm] = null
                    it[travelPerDiemRate] = null
                }
                val reportIds =
                    if (createdMemberIds.isEmpty()) {
                        emptyList()
                    } else {
                        TravelExpenseReportTable
                            .selectAll()
                            .where { TravelExpenseReportTable.subjectMemberId inList createdMemberIds }
                            .map { it[TravelExpenseReportTable.id] }
                    }
                val journalEntryIds =
                    TravelExpenseReportTable
                        .selectAll()
                        .where { TravelExpenseReportTable.id inList reportIds }
                        .mapNotNull { it[TravelExpenseReportTable.postedJournalEntryId] }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                if (journalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            val number = "T${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "State-Machine Testmitglied"
                    it[email] = "travel-sm-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
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

        suspend fun HttpClient.submittedReportOf(subjectId: Uuid): String {
            val draftId =
                post("/test/travelexpense/create?subjectMemberId=$subjectId&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                    header("X-Member-Id", subjectId.toString())
                }.bodyAsText().split("|")[0]
            post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Fahrt&kilometers=100") {
                header("X-Member-Id", subjectId.toString())
            }
            post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", subjectId.toString()) }
            return draftId
        }

        fun journalEntryCountFor(reportId: String): Long =
            transaction {
                JournalEntryTable.selectAll().where { JournalEntryTable.description like "%$reportId%" }.count()
            }

        test("double decideReport(true) -> second call Conflict, exactly one journal_entry") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)

                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict

                journalEntryCountFor(reportId) shouldBe 1L
            }
        }

        test(
            "double retryPosting after a successful booking -> Conflict, uq_ter_posted_journal_entry backstop, exactly one journal_entry",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                client.post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }

                client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                journalEntryCountFor(reportId) shouldBe 1L
            }
        }

        test("decideReport on a WITHDRAWN report -> Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                client.post("/test/travelexpense/withdraw?id=$reportId") { header("X-Member-Id", subject.toString()) }

                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("decideReport(true) on a DRAFT -> Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$subject&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                            header("X-Member-Id", subject.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client
                    .post("/test/travelexpense/decide?id=$draftId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("addLine/removeLine on a REQUESTED report -> Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                client
                    .post("/test/travelexpense/addline?reportId=$reportId&kind=MILEAGE&description=Weitere+Fahrt&kilometers=5") {
                        header("X-Member-Id", subject.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("approve=true without a note -> BadRequest; approve=false without a note -> BadRequest") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                client.post("/test/travelexpense/decide?id=$reportId&approve=true") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.BadRequest
                client.post("/test/travelexpense/decide?id=$reportId&approve=false") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("decision note over MAX_DECISION_NOTE_LENGTH characters -> BadRequest (review MINOR fix)") {
            // Mirrors the "purpose over 200 characters is rejected" guard for the report itself --
            // without this check an over-long note previously passed the blank check and only
            // failed later at the DB column limit (VARCHAR(1000)), surfacing as a raw
            // ExposedSQLException/500 instead of a clean BadRequestException/400.
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                val longNote = "x".repeat(1001)
                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=true&note=$longNote") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("decideReport(false) from APPROVED-with-executionError -> REJECTED, executionError cleared (chk_ter_execution_error_state)") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                // Deliberately unconfigured expense account -> Failed("travel_expense_account_not_configured").
                setMapping(expenseAccountId = null, bankAccountId = newLedgerAccount(LedgerAccountType.ASSET))
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[2] shouldBe TravelExpenseReportStatus.APPROVED.name

                val rejected =
                    client
                        .post(
                            "/test/travelexpense/decide?id=$reportId&approve=false&note=Nicht+buchbar",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                rejected[2] shouldBe TravelExpenseReportStatus.REJECTED.name
                rejected[14] shouldBe "-" // executionError cleared
            }
        }

        test("two consecutive failed retryPosting attempts on the same report are distinguishable in the audit trail") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setMapping(expenseAccountId = null, bankAccountId = newLedgerAccount(LedgerAccountType.ASSET))
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)
                client.post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }

                val retry1 =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                val retry2 =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retry1[14] shouldBe "travel_expense_account_not_configured"
                retry2[14] shouldBe "travel_expense_account_not_configured"

                val auditCount =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.TRAVEL_EXPENSE_REPORT) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(reportId))
                            }.count()
                    }
                // CREATE + submitReport UPDATE + decideReport UPDATE + 2x retryPosting UPDATE = 5.
                auditCount shouldBe 5L
            }
        }

        test("withdrawReport from DRAFT: submittedAt stays null, never appears in listReports") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$subject&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                            header("X-Member-Id", subject.toString())
                        }.bodyAsText()
                        .split("|")[0]
                val withdrawn =
                    client
                        .post("/test/travelexpense/withdraw?id=$draftId") { header("X-Member-Id", subject.toString()) }
                        .bodyAsText()
                        .split("|")
                withdrawn[2] shouldBe TravelExpenseReportStatus.WITHDRAWN.name
                withdrawn[9] shouldBe "-" // submittedAt

                val list = client.get("/test/travelexpense/list") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                list.contains(draftId) shouldBe false
            }
        }
    })
