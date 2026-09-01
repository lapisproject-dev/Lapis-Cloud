package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
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
import network.lapis.cloud.shared.domain.DonorCategory
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
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- exercises [DonationPostingBridge]
 * directly (a pure helper, not an RPC service, same treatment as [ContributionPostingBridgeTest],
 * whose fixture-management style this file mirrors exactly).
 */
class DonationPostingBridgeTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        fun setAccountMapping(
            bankAccountId: Uuid?,
            feeAccountId: Uuid?,
            donationIncomeAccountId: Uuid?,
            isPoliticalParty: Boolean = false,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = bankAccountId
                    it[paymentFeeAccountId] = feeAccountId
                    it[OrganizationSettingsTable.donationIncomeAccountId] = donationIncomeAccountId
                    it[OrganizationSettingsTable.isPoliticalParty] = isPoliticalParty
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            setAccountMapping(bankAccountId = null, feeAccountId = null, donationIncomeAccountId = null, isPoliticalParty = false)
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                val journalEntryIds =
                    if (createdMemberIds.isNotEmpty()) {
                        JournalEntryTable.selectAll().where { JournalEntryTable.donorMemberId inList createdMemberIds }.map {
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
                    it[displayName] = "DonationPostingBridge Testmitglied"
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

        test("balanced three-legged posting with a fee") {
            val donorId = createTestMember("donation-fee-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "D1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val feeAccountId = createLedgerAccount(number = "D2${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)
            val incomeAccountId = createLedgerAccount(number = "D3${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = feeAccountId, donationIncomeAccountId = incomeAccountId)

            val journalEntryId =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("100.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = BigDecimal("2.90"),
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            journalEntryId shouldNotBe null

            val postings =
                transaction {
                    PostingTable
                        .selectAll()
                        .where { PostingTable.journalEntryId eq journalEntryId!! }
                        .associate { it[PostingTable.ledgerAccountId] to (it[PostingTable.side] to it[PostingTable.amount]) }
                }
            postings[bankAccountId] shouldBe (PostingSide.DEBIT to BigDecimal("97.10"))
            postings[feeAccountId] shouldBe (PostingSide.DEBIT to BigDecimal("2.90"))
            postings[incomeAccountId] shouldBe (PostingSide.CREDIT to BigDecimal("100.00"))
        }

        test("two-legged posting without a fee") {
            val donorId = createTestMember("donation-nofee-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "D4${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "D5${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, donationIncomeAccountId = incomeAccountId)

            val journalEntryId =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("50.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }!!

            val postingCount = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.count() }
            postingCount shouldBe 2L
        }

        test("unconfigured donationIncomeAccountId -> null + WARN + no audit_log_entry") {
            val donorId = createTestMember("donation-unconfigured-${Uuid.random()}@example.org")
            setAccountMapping(bankAccountId = null, feeAccountId = null, donationIncomeAccountId = null)

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }
            val auditCountBefore = transaction { AuditLogEntryTable.selectAll().count() }

            val result =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }

            result shouldBe null
            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditCountBefore
        }

        test("inactive mapped account -> null") {
            val donorId = createTestMember("donation-inactive-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "D6${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "D7${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            transaction { LedgerAccountTable.update({ LedgerAccountTable.id eq incomeAccountId }) { it[active] = false } }
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, donationIncomeAccountId = incomeAccountId)

            val result =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }

            result shouldBe null
        }

        test("unbalanced input (scale > 2) throws ConflictException") {
            val donorId = createTestMember("donation-unbalanced-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "D8${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val feeAccountId = createLedgerAccount(number = "D9${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)
            val incomeAccountId = createLedgerAccount(number = "DA${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = feeAccountId, donationIncomeAccountId = incomeAccountId)

            shouldThrow<ConflictException> {
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.004"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = BigDecimal("0.005"),
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            }
        }

        test("non-party org ignores donorCategory entirely -- ALLOWED even for a structurally-prohibited category") {
            val donorId = createTestMember("donation-nonparty-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "DB${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "DC${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(
                bankAccountId = bankAccountId,
                feeAccountId = null,
                donationIncomeAccountId = incomeAccountId,
                isPoliticalParty = false,
            )

            val journalEntryId =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = DonorCategory.PUBLIC_LAW_CORPORATION,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            journalEntryId shouldNotBe null
        }

        test("party org with a PROHIBITED verdict -> null, no journal entry, no PARTY_DONATION_VERDICT audit entry") {
            val donorId = createTestMember("donation-prohibited-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "DD${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "DE${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(
                bankAccountId = bankAccountId,
                feeAccountId = null,
                donationIncomeAccountId = incomeAccountId,
                isPoliticalParty = true,
            )

            val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }

            val result =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = DonorCategory.PUBLIC_LAW_CORPORATION,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }

            result shouldBe null
            transaction { JournalEntryTable.selectAll().count() } shouldBe journalEntryCountBefore
        }

        test("party org, no donorCategory -> null (donorCategory mandatory once is_political_party)") {
            val donorId = createTestMember("donation-nocategory-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "DF${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "DG${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(
                bankAccountId = bankAccountId,
                feeAccountId = null,
                donationIncomeAccountId = incomeAccountId,
                isPoliticalParty = true,
            )

            val result =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }

            result shouldBe null
        }

        test(
            "party org with an ALLOWED verdict posts a JOURNAL_ENTRY and a PARTY_DONATION_VERDICT audit entry, journal_entry.donorMemberId/donorCategory set",
        ) {
            val donorId = createTestMember("donation-allowed-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "DH${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "DI${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(
                bankAccountId = bankAccountId,
                feeAccountId = null,
                donationIncomeAccountId = incomeAccountId,
                isPoliticalParty = true,
            )

            val journalEntryId =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2026, 4, 1, 10, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }
            journalEntryId shouldNotBe null

            val row = transaction { JournalEntryTable.selectAll().where { JournalEntryTable.id eq journalEntryId!! }.single() }
            row[JournalEntryTable.donorMemberId] shouldBe donorId
            row[JournalEntryTable.donorCategory] shouldBe DonorCategory.GERMAN_NATURAL_PERSON

            val verdictAuditCount =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityId eq journalEntryId!!) and
                                (AuditLogEntryTable.entityType eq AuditEntityType.PARTY_DONATION_VERDICT)
                        }.count()
                }
            verdictAuditCount shouldBe 1L
        }

        test("created_at is system-now, not the caller-supplied paidAt") {
            val donorId = createTestMember("donation-backdated-${Uuid.random()}@example.org")
            val bankAccountId = createLedgerAccount(number = "DJ${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(number = "DK${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, feeAccountId = null, donationIncomeAccountId = incomeAccountId)

            val journalEntryId =
                transaction {
                    DonationPostingBridge.postDonationPayment(
                        paymentTransactionId = Uuid.random(),
                        paidAmount = BigDecimal("10.00"),
                        paidAt = LocalDateTime(2000, 1, 1, 0, 0),
                        providerFee = null,
                        donorMemberId = donorId,
                        donorCategory = null,
                        actorMemberId = donorId,
                        actorRole = AccountRole.TREASURER,
                        voucherReference = null,
                    )
                }!!

            val createdAt =
                transaction {
                    JournalEntryTable.selectAll().where { JournalEntryTable.id eq journalEntryId }.single()[JournalEntryTable.createdAt]
                }
            (createdAt.year >= 2026) shouldBe true
        }
    })
