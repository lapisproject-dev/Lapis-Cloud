package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
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
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- **the core tests of this wave**: the
 * annual §3 Nr. 26/26a EStG cap enforced end-to-end through [VolunteerAllowanceService.decidePayment]/
 * [VolunteerAllowanceService.retryPosting], including the board cap-acknowledgment gate
 * ([VolunteerAllowanceCapDisclaimer]). A self-declaration is recorded for every subject/category/year
 * combination up front (via `declareSelf`) so the tests exercise the CAP gate specifically, not
 * [VolunteerAllowanceDeclarationTest]'s own "declaration missing" gate.
 */
class VolunteerAllowanceCapTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                val paymentIds =
                    VolunteerAllowancePaymentTable
                        .selectAll()
                        .where {
                            (VolunteerAllowancePaymentTable.subjectMemberId inList createdMemberIds) or
                                (VolunteerAllowancePaymentTable.requestedBy inList createdMemberIds)
                        }.map { it[VolunteerAllowancePaymentTable.id] }
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
                VolunteerAllowanceSelfDeclarationTable.deleteWhere {
                    VolunteerAllowanceSelfDeclarationTable.memberId inList createdMemberIds
                }
                AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Cap Testmitglied"
                    it[email] = "volunteer-cap-$id@example.org"
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

        suspend fun HttpClient.declareSelf(
            memberId: Uuid,
            category: String,
            year: Int,
        ) {
            post("/test/volunteerallowance/declare-self?category=$category&year=$year") { header("X-Member-Id", memberId.toString()) }
        }

        suspend fun HttpClient.submittedPaymentOf(
            subjectId: Uuid,
            category: String,
            amount: String,
            date: String,
        ): String =
            post(
                "/test/volunteerallowance/create?subjectMemberId=$subjectId&category=$category&amount=$amount" +
                    "&description=Vereinshelfer&date=$date",
            ) { header("X-Member-Id", subjectId.toString()) }
                .bodyAsText()
                .split("|")[0]
                .also { id -> post("/test/volunteerallowance/submit?id=$id") { header("X-Member-Id", subjectId.toString()) } }

        // (a) well under the cap
        test(
            "(a) a payment well under the cap -> EXECUTED-attempt (no posting bridge configured, lands on APPROVED with executionError), exceeding=0, no cap_* columns",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "100.00", "2026-06-01")

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[15] shouldBe "100.00" // freeAmountSnapshot
                decided[16] shouldBe "0.00" // exceedingAmountSnapshot
                decided[17] shouldBe "-" // capDisclaimerVersion
                decided[18] shouldBe "-" // capAcknowledgedAt
            }
        }

        // (b) exactly exhausted
        test("(b) the cap exactly exhausted -> CAP_EXHAUSTED, no acknowledgment required") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "960.00", "2026-06-01")

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[15] shouldBe "960.00" // freeAmountSnapshot -- the full amount is still tax-free
                decided[16] shouldBe "0.00" // exceedingAmountSnapshot
            }
        }

        // (c) overshoot without acknowledgment
        test("(c) overshooting the cap WITHOUT capAcknowledgment -> ConflictException, stays REQUESTED, no journal_entry") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "1000.00", "2026-06-01")

                client
                    .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict

                val row =
                    transaction {
                        VolunteerAllowancePaymentTable
                            .selectAll()
                            .where {
                                VolunteerAllowancePaymentTable.id eq
                                    Uuid.parse(paymentId)
                            }.single()
                    }
                row[VolunteerAllowancePaymentTable.status] shouldBe VolunteerAllowancePaymentStatus.REQUESTED
                row[VolunteerAllowancePaymentTable.postedJournalEntryId] shouldBe null
            }
        }

        // (d) overshoot WITH valid acknowledgment
        test("(d) overshooting the cap WITH a valid capAcknowledgment -> the decision proceeds, snapshots + cap_* columns correct") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "1000.00", "2026-06-01")

                val disclaimer =
                    client.get("/test/volunteerallowance/cap-disclaimer") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                val version = disclaimer[0]
                val sha256 = disclaimer[1]

                val decided =
                    client
                        .post(
                            "/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt&capVersion=$version&capSha256=$sha256",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[14] shouldBe "0.00" // priorTotalSnapshot
                decided[15] shouldBe "960.00" // freeAmountSnapshot
                decided[16] shouldBe "40.00" // exceedingAmountSnapshot
                decided[17] shouldBe version
                decided[18] shouldNotBe "-" // capAcknowledgedAt set

                val row =
                    transaction {
                        VolunteerAllowancePaymentTable
                            .selectAll()
                            .where {
                                VolunteerAllowancePaymentTable.id eq
                                    Uuid.parse(paymentId)
                            }.single()
                    }
                row[VolunteerAllowancePaymentTable.capDisclaimerSha256] shouldBe sha256
            }
        }

        // (e) tampered sha
        test("(e) a capAcknowledgment with a manipulated sha256 -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "1000.00", "2026-06-01")

                val disclaimer =
                    client.get("/test/volunteerallowance/cap-disclaimer") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                val version = disclaimer[0]
                val tamperedSha = "0" + disclaimer[1].drop(1)

                client
                    .post(
                        "/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt&capVersion=$version&capSha256=$tamperedSha",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        // (f) acknowledgment sent although WITHIN_CAP
        test("(f) a capAcknowledgment sent even though the payment does NOT exceed the cap -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "100.00", "2026-06-01")

                val disclaimer =
                    client.get("/test/volunteerallowance/cap-disclaimer") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                val version = disclaimer[0]
                val sha256 = disclaimer[1]

                client
                    .post(
                        "/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt&capVersion=$version&capSha256=$sha256",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        // (g) both categories independently
        test("(g) both categories, same person, same year -- independently capped, no cross-influence") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                client.declareSelf(subject, "INSTRUCTOR", 2026)

                val honoraryPaymentId = client.submittedPaymentOf(subject, "HONORARY", "960.00", "2026-06-01")
                val honoraryDecided =
                    client
                        .post("/test/volunteerallowance/decide?id=$honoraryPaymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                honoraryDecided[16] shouldBe "0.00" // exceedingAmountSnapshot

                val instructorPaymentId = client.submittedPaymentOf(subject, "INSTRUCTOR", "3300.00", "2026-06-01")
                val instructorDecided =
                    client
                        .post("/test/volunteerallowance/decide?id=$instructorPaymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                instructorDecided[16] shouldBe "0.00" // exceedingAmountSnapshot -- INSTRUCTOR unaffected by HONORARY consumption
            }
        }

        // (h) calendar-year boundary
        test("(h) a calendar-year boundary: cap exhausted on 31 Dec does not carry into 1 Jan of the following year") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "INSTRUCTOR", 2025)
                client.declareSelf(subject, "INSTRUCTOR", 2026)

                // Both dates well within VolunteerAllowanceRules.MAX_BACKDATE_DAYS (400) of "today"
                // regardless of exactly when this test runs -- see the module-wide "no far-future
                // literal dates" convention every other VolunteerAllowance* test file already follows.
                val decPaymentId = client.submittedPaymentOf(subject, "INSTRUCTOR", "3300.00", "2025-12-31")
                val decDecided =
                    client
                        .post("/test/volunteerallowance/decide?id=$decPaymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decDecided[16] shouldBe "0.00" // exceedingAmountSnapshot

                val janPaymentId = client.submittedPaymentOf(subject, "INSTRUCTOR", "3300.00", "2026-01-01")
                val janDecided =
                    client
                        .post("/test/volunteerallowance/decide?id=$janPaymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                janDecided[14] shouldBe "0.00" // priorTotalSnapshot -- the new calendar year starts fresh
                janDecided[16] shouldBe "0.00" // exceedingAmountSnapshot
            }
        }

        // (i) total changed since decision
        test(
            "(i) the recorded EXECUTED payment used for the prior-total is removed between decision and retry -> allowance_total_changed_since_decision",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)

                // First payment: forced directly to EXECUTED (bypassing decidePayment/the posting
                // bridge entirely) so it counts toward the second payment's prior-total
                // recomputation -- a fake JournalEntry row is required to satisfy
                // chk_vap_posted_entry_state (EXECUTED <=> posted_journal_entry_id IS NOT NULL).
                val firstPaymentId = client.submittedPaymentOf(subject, "HONORARY", "500.00", "2026-06-01")
                val fakeJournalEntryId = Uuid.random()
                transaction {
                    JournalEntryTable.insert {
                        it[id] = fakeJournalEntryId
                        it[entryDate] = LocalDate(2026, 6, 1)
                        it[description] = "Test-Fixture fuer VolunteerAllowanceCapTest (i)"
                        it[voucherReference] = "TEST-FIXTURE-$fakeJournalEntryId"
                        it[createdBy] = Uuid.parse(BOARD_ID)
                        it[status] = JournalEntryStatus.POSTED
                        it[postedAt] = DbClock.nowLocalDateTime()
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[donorMemberId] = null
                        it[externalDonorId] = null
                        it[donorCategory] = null
                    }
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq Uuid.parse(firstPaymentId) }) {
                        it[status] = VolunteerAllowancePaymentStatus.EXECUTED
                        it[executionError] = null
                        it[postedJournalEntryId] = fakeJournalEntryId
                        it[priorTotalSnapshot] = BigDecimal("0.00")
                        it[freeAmountSnapshot] = BigDecimal("500.00")
                        it[exceedingAmountSnapshot] = BigDecimal("0.00")
                        it[decidedAt] = DbClock.nowLocalDateTime()
                        it[decidedBy] = Uuid.parse(BOARD_ID)
                        it[decisionNote] = "Genehmigt"
                    }
                }

                val secondPaymentId = client.submittedPaymentOf(subject, "HONORARY", "400.00", "2026-06-02")
                val secondDecided =
                    client
                        .post("/test/volunteerallowance/decide?id=$secondPaymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                secondDecided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                secondDecided[14] shouldBe "500.00" // priorTotalSnapshot picked up the (forced) EXECUTED first payment

                // Now retroactively change the first payment's amount, changing the true prior total
                // that VolunteerAllowanceExecution recomputes on retry.
                transaction {
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq Uuid.parse(firstPaymentId) }) {
                        it[amount] = BigDecimal("100.00")
                    }
                }

                val retried =
                    client
                        .post(
                            "/test/volunteerallowance/retry?id=$secondPaymentId",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split(
                            "|",
                        )
                retried[13] shouldBe "allowance_total_changed_since_decision"
            }
        }

        // (j) snapshot no longer internally consistent -- the general INCONSISTENT backstop this
        // review round added: freeAmountSnapshot + exceedingAmountSnapshot must still equal amount,
        // re-checked on every retry (review INFORMATIONAL finding -- Round-1 only guarded the
        // freeAmountSnapshot==null-while-exceeding>0 shape, not this general mismatch).
        test(
            "(j) amount changes after decision while the free/exceeding snapshots stay put -> payment_no_longer_consistent, not a thrown exception",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val subject = newMember()
                client.declareSelf(subject, "HONORARY", 2026)
                val paymentId = client.submittedPaymentOf(subject, "HONORARY", "500.00", "2026-06-01")

                val decided =
                    client
                        .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                decided[15] shouldBe "500.00" // freeAmountSnapshot
                decided[16] shouldBe "0.00" // exceedingAmountSnapshot

                // Only `amount` changes -- freeAmountSnapshot/exceedingAmountSnapshot (frozen at
                // decision time, Duarte-Ruling) do not, so 500.00 + 0.00 != 999.00 on retry.
                transaction {
                    VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq Uuid.parse(paymentId) }) {
                        it[amount] = BigDecimal("999.00")
                    }
                }

                val retried =
                    client
                        .post("/test/volunteerallowance/retry?id=$paymentId") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                retried[2] shouldBe VolunteerAllowancePaymentStatus.APPROVED.name
                retried[13] shouldBe "payment_no_longer_consistent"
            }
        }

        // (k) TOCTOU-Race-Fix (Security-Fund, Welle V1.4.12): two REQUESTED payments of the SAME
        // subject/category/year, decided CONCURRENTLY by the board. Without lockAllowanceYearRows'
        // region lock (VolunteerAllowanceAggregation.kt), decidePayment's OLD `.forUpdate()` on only
        // its OWN payment row never serialized these two transactions under READ COMMITTED -- both
        // would independently compute priorPostedAllowanceTotalThisYear = 0 (neither's EXECUTED
        // transition is visible to the other while both are still in flight), both would get
        // WITHIN_CAP (600 < 960), and 1200 EUR would post with exceedingAmountSnapshot = 0 in BOTH
        // rows -- exactly the scenario this Security-Fund describes. Mirrors
        // AccountingServiceTest's own `coroutineScope { ... async { client.post(...) } ... }`
        // concurrency-test idiom for `CashRegisterGuard.requireNonNegativeCashBalances`.
        test(
            "(k) two concurrent decidePayment calls for two DIFFERENT payments of the same subject/category/year " +
                "-- only ONE may post within the cap, the other must recompute against the FIRST's committed total",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val expenseAccountId = Uuid.random()
                val bankAccountId = Uuid.random()
                transaction {
                    LedgerAccountTable.insert {
                        it[id] = expenseAccountId
                        it[accountNumber] = "V${expenseAccountId.toString().filter { c -> c.isDigit() }.take(9)}"
                        it[name] = "Testkonto Ehrenamt (k)"
                        it[accountClass] = 0
                        it[type] = LedgerAccountType.EXPENSE
                        it[active] = true
                        it[reserveType] = null
                        it[isCashRegister] = false
                    }
                    LedgerAccountTable.insert {
                        it[id] = bankAccountId
                        it[accountNumber] = "V${bankAccountId.toString().filter { c -> c.isDigit() }.take(9)}"
                        it[name] = "Testkonto Bank (k)"
                        it[accountClass] = 0
                        it[type] = LedgerAccountType.ASSET
                        it[active] = true
                        it[reserveType] = null
                        it[isCashRegister] = false
                    }
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[volunteerAllowanceAccountId] = expenseAccountId
                        it[paymentBankAccountId] = bankAccountId
                    }
                }
                try {
                    val subject = newMember()
                    client.declareSelf(subject, "HONORARY", 2026)
                    val firstPaymentId = client.submittedPaymentOf(subject, "HONORARY", "600.00", "2026-06-01")
                    val secondPaymentId = client.submittedPaymentOf(subject, "HONORARY", "600.00", "2026-06-02")

                    val results =
                        coroutineScope {
                            listOf(firstPaymentId, secondPaymentId)
                                .map { paymentId ->
                                    async {
                                        client
                                            .post("/test/volunteerallowance/decide?id=$paymentId&approve=true&note=Genehmigt") {
                                                header("X-Member-Id", ADMIN_ID)
                                            }.status
                                    }
                                }.map { it.await() }
                        }

                    // Exactly one of the two decisions may proceed WITHOUT a cap acknowledgment
                    // (600 < 960 alone); the other, once it recomputes under the region lock and
                    // sees the first payment's now-EXECUTED 600.00, projects to 1200 > 960 and is
                    // rejected for lacking the (unsent) capAcknowledgment.
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    results.count { it == HttpStatusCode.Conflict } shouldBe 1

                    val finalStatuses =
                        transaction {
                            listOf(firstPaymentId, secondPaymentId).map { id ->
                                VolunteerAllowancePaymentTable
                                    .selectAll()
                                    .where { VolunteerAllowancePaymentTable.id eq Uuid.parse(id) }
                                    .single()
                                    .let {
                                        it[VolunteerAllowancePaymentTable.status] to
                                            it[VolunteerAllowancePaymentTable.exceedingAmountSnapshot]
                                    }
                            }
                        }
                    // Exactly one EXECUTED (the winner, posted for real via the configured ledger
                    // accounts) with exceedingAmountSnapshot == 0.00; the loser stays REQUESTED
                    // (the ConflictException throw rolls back before any column is written) --
                    // NEVER both EXECUTED, which is the actual harm this Security-Fund describes.
                    finalStatuses.count { it.first == VolunteerAllowancePaymentStatus.EXECUTED } shouldBe 1
                    finalStatuses.count { it.first == VolunteerAllowancePaymentStatus.REQUESTED } shouldBe 1
                    finalStatuses.single { it.first == VolunteerAllowancePaymentStatus.EXECUTED }.second shouldBe BigDecimal("0.00")
                } finally {
                    transaction {
                        val journalEntryIds =
                            VolunteerAllowancePaymentTable
                                .selectAll()
                                .where {
                                    VolunteerAllowancePaymentTable.subjectMemberId inList createdMemberIds
                                }.mapNotNull { it[VolunteerAllowancePaymentTable.postedJournalEntryId] }
                        if (journalEntryIds.isNotEmpty()) {
                            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                        }
                        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                            it[volunteerAllowanceAccountId] = null
                            it[paymentBankAccountId] = null
                        }
                        PostingTable.deleteWhere { PostingTable.ledgerAccountId inList listOf(expenseAccountId, bankAccountId) }
                        LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList listOf(expenseAccountId, bankAccountId) }
                    }
                }
            }
        }
    })
