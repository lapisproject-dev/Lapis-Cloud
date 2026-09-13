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
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
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
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- booking correctness for
 * [VolunteerAllowancePostingBridge], exercised end-to-end through
 * [VolunteerAllowanceService.decidePayment]/[VolunteerAllowanceService.retryPosting] (the bridge
 * itself is `internal`). Mirrors [TravelExpensePostingBridgeTest]'s house style. Every payment here
 * has a self-declaration recorded up front and stays well under the annual cap -- the cap-gated
 * path is [VolunteerAllowanceCapTest]'s job, this file's job is the accounting/booking mechanics.
 */
class VolunteerAllowancePostingBridgeTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        fun setMapping(
            expenseAccountId: Uuid?,
            bankAccountId: Uuid?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[volunteerAllowanceAccountId] = expenseAccountId
                    it[paymentBankAccountId] = bankAccountId
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
                val paymentIds =
                    if (createdMemberIds.isEmpty()) {
                        emptyList()
                    } else {
                        VolunteerAllowancePaymentTable
                            .selectAll()
                            .where { VolunteerAllowancePaymentTable.subjectMemberId inList createdMemberIds }
                            .map { it[VolunteerAllowancePaymentTable.id] }
                    }
                val journalEntryIds =
                    VolunteerAllowancePaymentTable
                        .selectAll()
                        .where { VolunteerAllowancePaymentTable.id inList paymentIds }
                        .mapNotNull { it[VolunteerAllowancePaymentTable.postedJournalEntryId] }
                VolunteerAllowancePaymentTable.deleteWhere { VolunteerAllowancePaymentTable.id inList paymentIds }
                if (journalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                VolunteerAllowanceSelfDeclarationTable.deleteWhere {
                    VolunteerAllowanceSelfDeclarationTable.memberId inList createdMemberIds
                }
                AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newLedgerAccount(
            type: LedgerAccountType,
            active: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            val number = "V${id.toString().filter { it.isDigit() }.take(9)}"
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
                    it[email] = "volunteer-bridge-$id@example.org"
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

        suspend fun HttpClient.submittedPaymentOf(subjectId: Uuid): String {
            post("/test/volunteerallowance/declare-self?category=HONORARY&year=2026") { header("X-Member-Id", subjectId.toString()) }
            val draftId =
                post(
                    "/test/volunteerallowance/create?subjectMemberId=$subjectId&category=HONORARY&amount=100.00" +
                        "&description=Vereinshelfer&date=2026-06-01",
                ) { header("X-Member-Id", subjectId.toString()) }.bodyAsText().split("|")[0]
            post("/test/volunteerallowance/submit?id=$draftId") { header("X-Member-Id", subjectId.toString()) }
            return draftId
        }

        test("booking correctness: one DEBIT on the expense account, one CREDIT on the bank account, sums match amount") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.EXECUTED.name
                val journalEntryId = Uuid.parse(decided[12])

                val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.toList() }
                postings.size shouldBe 2
                val debit = postings.single { it[PostingTable.side] == PostingSide.DEBIT }
                val credit = postings.single { it[PostingTable.side] == PostingSide.CREDIT }
                debit[PostingTable.ledgerAccountId] shouldBe expenseAccount
                credit[PostingTable.ledgerAccountId] shouldBe bankAccount
                debit[PostingTable.amount] shouldBe BigDecimal("100.00")
                credit[PostingTable.amount] shouldBe BigDecimal("100.00")
                debit[PostingTable.sphere] shouldBe GemeinnuetzigkeitSphere.IDEELLER_BEREICH
            }
        }

        test("voucherReference is VOLALLOW-<paymentId>") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)
                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                val journalEntryId = Uuid.parse(decided[12])
                val voucherReference =
                    transaction {
                        JournalEntryTable
                            .selectAll()
                            .where {
                                JournalEntryTable.id eq journalEntryId
                            }.single()[JournalEntryTable.voucherReference]
                    }
                voucherReference shouldBe "VOLALLOW-$paymentId"
            }
        }

        test(
            "volunteerAllowanceAccountId not configured -> volunteer_allowance_account_not_configured, approval preserved, retryPosting after config succeeds",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccountId = null, bankAccountId = bankAccount)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[13] shouldBe "volunteer_allowance_account_not_configured"

                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(expenseAccount, bankAccount)
                val retried =
                    client.post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retried[2] shouldBe VolunteerAllowancePaymentStatus.EXECUTED.name
            }
        }

        test("paymentBankAccountId not configured -> payment_bank_account_not_configured") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(expenseAccountId = expenseAccount, bankAccountId = null)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)
                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[13] shouldBe "payment_bank_account_not_configured"
            }
        }

        test(
            "volunteerAllowanceAccountId of the wrong type -> volunteer_allowance_account_not_expense_type, no journal entry, stays APPROVED",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val wrongTypeAccount = newLedgerAccount(LedgerAccountType.INCOME)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(wrongTypeAccount, bankAccount)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[13] shouldBe "volunteer_allowance_account_not_expense_type"
                decided[12] shouldBe "-" // postedJournalEntryId

                val correctAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(correctAccount, bankAccount)
                val retried =
                    client.post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                retried[2] shouldBe VolunteerAllowancePaymentStatus.EXECUTED.name
            }
        }

        test("an inactive ledger account -> ledger_account_inactive, no journal entry") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val inactiveExpenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE, active = false)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(inactiveExpenseAccount, bankAccount)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[13] shouldBe "ledger_account_inactive"
            }
        }

        test("degrading codes leave NO JOURNAL_ENTRY audit entry; the happy path leaves exactly one") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccountId = null, bankAccountId = bankAccount)
                val degradedSubject = newMember()
                val degradedPaymentId = client.submittedPaymentOf(degradedSubject)
                // Before/after, not an absolute count -- the shared test database accumulates
                // JOURNAL_ENTRY audit rows from every other suite in the same run (e.g.
                // TravelExpensePostingBridgeTest), so an absolute "== 0" assertion would be
                // meaningless/flaky. The degraded attempt below must not INCREASE this count.
                val journalEntryAuditCountBefore =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY }
                            .count()
                    }
                client.post("/test/volunteerallowance/decide?id=$degradedPaymentId&approve=true&note=Genehmigt") {
                    header("X-Member-Id", BOARD_ID)
                }
                val journalEntryAuditCountAfterDegraded =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY }
                            .count()
                    }

                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                setMapping(expenseAccount, bankAccount)
                val happySubject = newMember()
                val happyPaymentId = client.submittedPaymentOf(happySubject)
                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$happyPaymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                val journalEntryId = Uuid.parse(decided[12])
                val happyAuditCount =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq journalEntryId)
                            }.count()
                    }
                happyAuditCount shouldBe 1L
                // The degraded attempt above must not have created ANY new JOURNAL_ENTRY audit row.
                journalEntryAuditCountAfterDegraded shouldBe journalEntryAuditCountBefore
            }
        }

        test("idempotency: uq_vap_posted_journal_entry -- a payment is posted at most once") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val expenseAccount = newLedgerAccount(LedgerAccountType.EXPENSE)
                val bankAccount = newLedgerAccount(LedgerAccountType.ASSET)
                setMapping(expenseAccount, bankAccount)
                val subject = newMember()
                val paymentId = client.submittedPaymentOf(subject)
                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.EXECUTED.name

                // retryPosting on an already-EXECUTED payment is rejected by the state guard before
                // ever reaching the bridge -- the DB-level uq_vap_posted_journal_entry is the second,
                // structural line of defense (see VolunteerAllowanceSchemaDriftTest).
                client
                    .post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", ADMIN_ID) }
                    .let { it.status.value } shouldBe 409
            }
        }
    })
