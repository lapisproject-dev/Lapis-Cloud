package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ConflictException
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

/**
 * Exercises [ContributionPostingBridge] directly (a pure helper, not an RPC service -- see its own
 * KDoc) -- vault plan "Lapis Cloud V1.2 -- Zahlungsverkehr" § 8.8 "Buchhaltungs-Korrektheit". Same
 * "own freshly created fixtures, never DevSeedData's shared demo fixtures" house style
 * [AccountingServiceTest] establishes. [afterSpec] hard-deletes every row this file created and
 * resets the three account-mapping `OrganizationSettings` fields to `null`.
 */
class ContributionPostingBridgeTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        fun setAccountMapping(
            bankAccountId: Uuid?,
            feeAccountId: Uuid?,
            incomeAccountId: Uuid?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = bankAccountId
                    it[paymentFeeAccountId] = feeAccountId
                    it[contributionIncomeAccountId] = incomeAccountId
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            setAccountMapping(bankAccountId = null, feeAccountId = null, incomeAccountId = null)
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                val journalEntryIds =
                    if (createdMemberIds.isNotEmpty()) {
                        JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList createdMemberIds }.map {
                            it[JournalEntryTable.id]
                        }
                    } else {
                        emptyList()
                    }
                if (journalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Posting-Bridge Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.TREASURER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
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

        fun sumPostings(
            journalEntryId: Uuid,
            side: PostingSide,
        ): BigDecimal =
            transaction {
                PostingTable
                    .selectAll()
                    .where { (PostingTable.journalEntryId eq journalEntryId) and (PostingTable.side eq side) }
                    .fold(BigDecimal.ZERO) { acc, row -> acc + row[PostingTable.amount] }
            }

        test("books a balanced entry (soll = haben) with sphere IDEELLER_BEREICH on every line") {
            val treasurerId = createTestMember("bridge-balanced-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "T1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "T2${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, incomeAccountId = incomeAccountId)

            val journalEntryId =
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("42.50"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.MANUAL,
                        providerFee = null,
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            journalEntryId shouldNotBe null

            val soll = sumPostings(journalEntryId = journalEntryId!!, side = PostingSide.DEBIT)
            val haben = sumPostings(journalEntryId = journalEntryId, side = PostingSide.CREDIT)
            soll.compareTo(haben) shouldBe 0
            soll.compareTo(BigDecimal("42.50")) shouldBe 0

            val spheres =
                transaction {
                    PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.map { it[PostingTable.sphere] }
                }
            spheres.all { it == GemeinnuetzigkeitSphere.IDEELLER_BEREICH } shouldBe true

            // Successful booking also writes exactly one JOURNAL_ENTRY audit entry.
            val auditCount =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq journalEntryId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY)
                        }.count()
                }
            auditCount shouldBe 1L
        }

        test("splits brutto/netto when a provider fee is charged -- fee line only appears then") {
            val treasurerId = createTestMember("bridge-fee-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "T3${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val feeAccountId = createLedgerAccount(number = "T4${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)
            val incomeAccountId = createLedgerAccount(number = "T5${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = feeAccountId, incomeAccountId = incomeAccountId)

            val journalEntryId =
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("100.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.GATEWAY,
                        providerFee = BigDecimal("2.90"),
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = "TEST-VOUCHER",
                    )
                }!!

            val postings =
                transaction {
                    PostingTable
                        .selectAll()
                        .where { PostingTable.journalEntryId eq journalEntryId }
                        .associate { it[PostingTable.ledgerAccountId] to (it[PostingTable.side] to it[PostingTable.amount]) }
                }
            postings[bankAccountId] shouldBe (PostingSide.DEBIT to BigDecimal("97.10"))
            postings[feeAccountId] shouldBe (PostingSide.DEBIT to BigDecimal("2.90"))
            postings[incomeAccountId] shouldBe (PostingSide.CREDIT to BigDecimal("100.00"))
        }

        test("no provider fee reported -- no fee line, full amount goes to the bank account") {
            val treasurerId = createTestMember("bridge-nofee-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "T6${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "T7${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, incomeAccountId = incomeAccountId)

            val journalEntryId =
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("60.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.MANUAL,
                        providerFee = null,
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }!!

            val postings =
                transaction {
                    PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.count()
                }
            postings shouldBe 2L
        }

        test("deactivated ledger account degrades to null, same as unconfigured -- no journal entry, no audit entry, no throw") {
            // Review Round 1 (2026-08-19, MAJOR-3): a configured-but-deactivated account must be
            // treated exactly like an unconfigured one, NOT like AccountingService.postJournalEntry's
            // own requireActiveLedgerAccounts (which throws ConflictException) -- a status-only
            // contribution marking must still succeed even if the ledger posting cannot happen.
            val treasurerId = createTestMember("bridge-inactive-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "T8${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "T9${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            transaction { LedgerAccountTable.update({ LedgerAccountTable.id eq bankAccountId }) { it[active] = false } }
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, incomeAccountId = incomeAccountId)

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }
            val auditCountBefore = transaction { AuditLogEntryTable.selectAll().count() }

            val result =
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("15.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.MANUAL,
                        providerFee = null,
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }

            result shouldBe null
            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditCountBefore
        }

        test("journal_entry.created_at and the audit log's occurredAt are real 'now', not the backdated paidAt") {
            // Review Round 1 (2026-08-19, MAJOR-4): paidAt is a caller-supplied, unvalidated
            // business date (correctly used for entryDate/postedAt) -- it must NEVER leak into
            // journal_entry.created_at (the real Erfassungszeitpunkt) or the immutable audit log's
            // occurredAt, or a treasurer could backdate what the GoBD audit trail claims to be the
            // moment the system recorded this action.
            val treasurerId = createTestMember("bridge-backdated-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "TA${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "TB${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, incomeAccountId = incomeAccountId)

            val backdatedPaidAt = LocalDateTime(2000, 1, 1, 0, 0)
            val beforeCall = DbClock.nowLocalDateTime()

            val journalEntryId =
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("20.00"),
                        paidAt = backdatedPaidAt,
                        source = ContributionPaymentMethod.MANUAL,
                        providerFee = null,
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }!!

            val afterCall = DbClock.nowLocalDateTime()

            val entryCreatedAt =
                transaction {
                    JournalEntryTable.selectAll().where { JournalEntryTable.id eq journalEntryId }.single()[JournalEntryTable.createdAt]
                }
            (entryCreatedAt >= beforeCall && entryCreatedAt <= afterCall) shouldBe true

            val auditOccurredAt =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq journalEntryId) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY)
                        }.single()[AuditLogEntryTable.occurredAt]
                }
            (auditOccurredAt >= beforeCall && auditOccurredAt <= afterCall) shouldBe true
        }

        test("missing account configuration degrades to null -- no journal entry, no audit entry, no throw") {
            val treasurerId = createTestMember("bridge-unconfigured-${Uuid.random()}@example.org")
            setAccountMapping(bankAccountId = null, feeAccountId = null, incomeAccountId = null)

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }
            val auditCountBefore = transaction { AuditLogEntryTable.selectAll().count() }

            val result =
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("30.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.MANUAL,
                        providerFee = null,
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }

            result shouldBe null
            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditCountBefore
        }

        test("100 Beitraege a 33,33 EUR summieren exakt -- kein Cent verloren (plan section 8.8)") {
            val treasurerId = createTestMember("bridge-rounding-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "TC${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "TD${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, incomeAccountId = incomeAccountId)

            val journalEntryIds =
                (1..100).map {
                    transaction {
                        ContributionPostingBridge.postContributionPayment(
                            contributionId = Uuid.random(),
                            paidAmount = BigDecimal("33.33"),
                            paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                            source = ContributionPaymentMethod.MANUAL,
                            providerFee = null,
                            actorMemberId = treasurerId,
                            actorRole = AccountRole.TREASURER,
                            voucherReference = null,
                        )
                    }!!
                }

            val totalBooked =
                transaction {
                    PostingTable
                        .selectAll()
                        .where { (PostingTable.journalEntryId inList journalEntryIds) and (PostingTable.ledgerAccountId eq bankAccountId) }
                        .fold(BigDecimal.ZERO) { acc, row -> acc + row[PostingTable.amount] }
                }
            totalBooked.compareTo(BigDecimal("3333.00")) shouldBe 0
        }

        test(
            "paidAmount/providerFee with more than 2 fractional digits throws ConflictException, no journal " +
                "entry is created (Review Round 2, SHOULD-1)",
        ) {
            // No require(...) in postContributionPayment guards input SCALE (only sign/ordering), so
            // a scale-3 paidAmount/providerFee pair -- e.g. from a caller doing its own fee-percentage
            // arithmetic without rounding -- is a real reachable input, not a contrived one. Before the
            // requireBalanced defense-in-depth added this round, this would have inserted a
            // DECIMAL(15,2)-truncated, permanently unbalanced POSTED journal entry into the general
            // ledger instead of failing loudly.
            val treasurerId = createTestMember("bridge-scale-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "TE${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val feeAccountId = createLedgerAccount(number = "TF${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)
            val incomeAccountId = createLedgerAccount(number = "TG${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = feeAccountId, incomeAccountId = incomeAccountId)

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }

            shouldThrow<ConflictException> {
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("10.004"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.GATEWAY,
                        providerFee = BigDecimal("0.005"),
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            }

            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
        }

        test(
            "cash-register account mapped as contributionIncomeAccountId is rejected with ConflictException, " +
                "no journal entry is created (Security Round 1, 2026-08-19, MAJOR-1)",
        ) {
            // OrganizationSettingsService.updateOrganizationSettings (Security Round 1, SHOULD-1) now
            // refuses to save a cash-register account as any of the three mapping fields in the first
            // place -- but this test bypasses that RPC-level guard on purpose (direct table write, same
            // house style every other test in this file uses via setAccountMapping) to prove the
            // BRIDGE's own runtime CashRegisterGuard defense-in-depth actually fires too, exactly as
            // AccountingService.postJournalEntry's own guard preamble would for the same posting set.
            val treasurerId = createTestMember("bridge-cashregister-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "TH${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val cashIncomeAccountId =
                transaction {
                    val id = Uuid.random()
                    LedgerAccountTable.insert {
                        it[LedgerAccountTable.id] = id
                        it[accountNumber] = "TI${Uuid.random().toString().take(6)}"
                        it[name] = "Testkasse (missbraeuchlich als Ertragskonto zugeordnet)"
                        it[accountClass] = 0
                        it[type] = LedgerAccountType.ASSET
                        it[active] = true
                        it[reserveType] = null
                        it[isCashRegister] = true
                    }
                    id
                }
            createdLedgerAccountIds += cashIncomeAccountId
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, incomeAccountId = cashIncomeAccountId)

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }

            shouldThrow<ConflictException> {
                transaction {
                    ContributionPostingBridge.postContributionPayment(
                        contributionId = Uuid.random(),
                        paidAmount = BigDecimal("25.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        source = ContributionPaymentMethod.MANUAL,
                        providerFee = null,
                        actorMemberId = treasurerId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            }

            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
        }
    })
