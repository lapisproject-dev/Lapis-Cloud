package network.lapis.cloud.server.rpc

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
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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
 * Welle V1.4.11 -- booking correctness for [TravelExpensePostingBridge], exercised end-to-end
 * through [TravelExpenseService.decideReport]/[TravelExpenseService.retryPosting] (the bridge
 * itself is `internal`). Mirrors [ContributionPostingBridgeTest]'s house style.
 */
class TravelExpensePostingBridgeTest :
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
                    it[travelAdvanceAccountId] = null
                    it[travelDonationWaiverAccountId] = null
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
                // travel_expense_report.posted_journal_entry_id FKs -> journal_entry -- the report
                // row (and its lines) must be gone BEFORE journal_entry/posting are deleted.
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

        fun newLedgerAccount(
            type: LedgerAccountType,
            active: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            // Random, collision-free account number (VARCHAR(10)) -- avoids any accidental clash
            // with a DevSeedData-seeded SKR42 number like "40000".
            val number = "T${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[LedgerAccountTable.active] = active
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
                    it[displayName] = "Bridge Testmitglied"
                    it[email] = "travel-bridge-$id@example.org"
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

        test("booking correctness: one DEBIT posting on the expense account, one CREDIT on the bank account, sums match total_amount") {
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

                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                val journalEntryId = Uuid.parse(decided[13])

                val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.toList() }
                postings.size shouldBe 2
                val debit = postings.single { it[PostingTable.side] == PostingSide.DEBIT }
                val credit = postings.single { it[PostingTable.side] == PostingSide.CREDIT }
                debit[PostingTable.ledgerAccountId] shouldBe expenseAccount
                credit[PostingTable.ledgerAccountId] shouldBe bankAccount
                debit[PostingTable.amount] shouldBe BigDecimal("30.00")
                credit[PostingTable.amount] shouldBe BigDecimal("30.00")
                debit[PostingTable.sphere] shouldBe GemeinnuetzigkeitSphere.IDEELLER_BEREICH

                transaction {
                    JournalEntryTable.selectAll().where { JournalEntryTable.id eq journalEntryId }.single()[JournalEntryTable.status]
                } shouldBe JournalEntryStatus.POSTED
            }
        }

        test("travelExpenseAccountId of the wrong type -> travel_expense_account_not_expense_type, no journal entry, stays APPROVED") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val wrongTypeAccount = newLedgerAccount(LedgerAccountType.INCOME)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(wrongTypeAccount, bankAccount)
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)

                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                decided[14] shouldBe "travel_expense_account_not_expense_type"
                decided[13] shouldBe "-" // postedJournalEntryId

                // Configure a correct account and retry -> EXECUTED.
                val correctAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(correctAccount, bankAccount)
                val retried =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retried[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
            }
        }

        test(
            "travelExpenseAccountId not configured -> travel_expense_account_not_configured, approval preserved, retryPosting after config succeeds",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccountId = null, bankAccountId = bankAccount)
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)

                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                decided[14] shouldBe "travel_expense_account_not_configured"

                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(expenseAccount, bankAccount)
                val retried =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retried[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
            }
        }

        test("an inactive ledger account -> ledger_account_inactive, no journal entry") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val inactiveExpenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE, active = false)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(inactiveExpenseAccount, bankAccount)
                val subject = newMember()
                val reportId = client.submittedReportOf(subject)

                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                decided[14] shouldBe "ledger_account_inactive"
            }
        }

        test("sphere of every posting is IDEELLER_BEREICH") {
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
                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                val journalEntryId = Uuid.parse(decided[13])
                val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.toList() }
                postings.forEach { it[PostingTable.sphere] shouldBe GemeinnuetzigkeitSphere.IDEELLER_BEREICH }
            }
        }

        test("exactly one JOURNAL_ENTRY audit entry per successful posting") {
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
                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                val journalEntryId = decided[13]
                val auditCount =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(journalEntryId))
                            }.count()
                    }
                auditCount shouldBe 1L
            }
        }

        fun setExtraMapping(
            advanceAccountId: Uuid?,
            waiverAccountId: Uuid?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelAdvanceAccountId] = advanceAccountId
                    it[travelDonationWaiverAccountId] = waiverAccountId
                }
            }
        }

        suspend fun HttpClient.submittedReportWith(
            subjectId: Uuid,
            advanceCents: Long,
            payout: String,
        ): String {
            val draftId =
                post(
                    "/test/travelexpense/create?subjectMemberId=$subjectId&purpose=Reise&from=2026-01-05&to=2026-01-05" +
                        "&advance=$advanceCents&payout=$payout",
                ) { header("X-Member-Id", subjectId.toString()) }.bodyAsText().split("|")[0]
            post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Fahrt&kilometers=100") {
                header("X-Member-Id", subjectId.toString())
            }
            post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", subjectId.toString()) }
            return draftId
        }

        suspend fun HttpClient.decide(reportId: String): List<String> =
            post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                .bodyAsText()
                .split("|")

        fun creditsOf(journalEntryId: String): Map<Uuid, BigDecimal> =
            transaction {
                PostingTable
                    .selectAll()
                    .where { (PostingTable.journalEntryId eq Uuid.parse(journalEntryId)) and (PostingTable.side eq PostingSide.CREDIT) }
                    .associate { it[PostingTable.ledgerAccountId] to it[PostingTable.amount] }
            }

        test("V1.9.122 advance: credit side splits into bank (total - advance) and advance account; debits still sum to the total") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                val advanceAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                setExtraMapping(advanceAccount, null)
                val subject = newMember()
                val reportId = client.submittedReportWith(subject, advanceCents = 1000, payout = "PAYOUT")
                val decided = client.decide(reportId)
                decided[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                val credits = creditsOf(decided[13])
                credits shouldBe mapOf(bankAccount to BigDecimal("20.00"), advanceAccount to BigDecimal("10.00"))
                val debitSum =
                    transaction {
                        PostingTable
                            .selectAll()
                            .where { (PostingTable.journalEntryId eq Uuid.parse(decided[13])) and (PostingTable.side eq PostingSide.DEBIT) }
                            .sumOf { it[PostingTable.amount] }
                    }
                debitSum.compareTo(BigDecimal("30.00")) shouldBe 0
                setExtraMapping(null, null)
            }
        }

        test("V1.9.122 advance without a configured advance account -> travel_advance_account_not_configured, retry after config books") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                setExtraMapping(null, null)
                val subject = newMember()
                val reportId = client.submittedReportWith(subject, advanceCents = 1000, payout = "PAYOUT")
                val decided = client.decide(reportId)
                decided[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                decided[14] shouldBe "travel_advance_account_not_configured"
                decided[13] shouldBe "-"

                setExtraMapping(newLedgerAccount(LedgerAccountType.ASSET), null)
                val retried =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retried[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                setExtraMapping(null, null)
            }
        }

        test("V1.9.122 advance == total: payout is 0, NO bank posting, the bank account is not even required") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val advanceAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, null)
                setExtraMapping(advanceAccount, null)
                val subject = newMember()
                val reportId = client.submittedReportWith(subject, advanceCents = 3000, payout = "PAYOUT")
                val decided = client.decide(reportId)
                decided[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                creditsOf(decided[13]) shouldBe mapOf(advanceAccount to BigDecimal("30.00"))
                setExtraMapping(null, null)
            }
        }

        test("V1.9.122 DONATION_WAIVER: credit goes to the income account, never to the bank (no money flows)") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                val waiverAccount = newLedgerAccount(LedgerAccountType.INCOME)
                setMapping(expenseAccount, bankAccount)
                setExtraMapping(null, waiverAccount)
                val subject = newMember()
                val reportId = client.submittedReportWith(subject, advanceCents = 0, payout = "DONATION_WAIVER")
                val decided = client.decide(reportId)
                decided[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                creditsOf(decided[13]) shouldBe mapOf(waiverAccount to BigDecimal("30.00"))
                setExtraMapping(null, null)
            }
        }

        test("V1.9.122 DONATION_WAIVER without / with a wrong-typed waiver account -> degrading codes, then retry books") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                setExtraMapping(null, null)
                val subject = newMember()
                val reportId = client.submittedReportWith(subject, advanceCents = 0, payout = "DONATION_WAIVER")
                val first = client.decide(reportId)
                first[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                first[14] shouldBe "donation_waiver_account_not_configured"

                setExtraMapping(null, newLedgerAccount(LedgerAccountType.EXPENSE))
                val second =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                second[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                second[14] shouldBe "donation_waiver_account_not_income_type"

                setExtraMapping(null, newLedgerAccount(LedgerAccountType.INCOME))
                val third =
                    client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                third[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                setExtraMapping(null, null)
            }
        }

        test("V1.9.122 DONATION_WAIVER with an advance: advance account + waiver account, still balanced") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val advanceAccount = newLedgerAccount(LedgerAccountType.ASSET)
                val waiverAccount = newLedgerAccount(LedgerAccountType.INCOME)
                setMapping(expenseAccount, null)
                setExtraMapping(advanceAccount, waiverAccount)
                val subject = newMember()
                val reportId = client.submittedReportWith(subject, advanceCents = 1000, payout = "DONATION_WAIVER")
                val decided = client.decide(reportId)
                decided[2] shouldBe TravelExpenseReportStatus.EXECUTED.name
                creditsOf(decided[13]) shouldBe mapOf(advanceAccount to BigDecimal("10.00"), waiverAccount to BigDecimal("20.00"))
                setExtraMapping(null, null)
            }
        }
    })
