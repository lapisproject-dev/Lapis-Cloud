package network.lapis.cloud.server.payment.bankstatement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput
import network.lapis.cloud.shared.domain.BankStatementLineQuery
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val SPARKASSE_HEADER =
    "Auftragskonto;Buchungstag;Valutadatum;Buchungstext;Verwendungszweck;Beguenstigter/Zahlungspflichtiger;" +
        "Kontonummer/IBAN;BIC (SWIFT-Code);Betrag;Waehrung;Kundenreferenz (End-to-End)"

/**
 * Review fix (MEDIUM, "Fehlende Testabdeckung"): this file previously did not exist at all -- the
 * disposition/booking layer (`BankStatementStore`) that manually assigns bank-statement lines to
 * contributions/donations had zero direct test coverage. Focuses on the write paths the review
 * flagged (`assignLineToContribution`'s MAJOR-fix regression above all) plus the basic
 * `assignLineToDonation`/`ignoreLine`/`requireAssignableLine` contracts.
 *
 * Review fix (MEDIUM, Runde-2/3-Fund #9, follow-up): the read helpers `listLines`/`suggestMatches`/
 * `searchAssignmentTargets` -- called out above as a known coverage gap -- are now exercised too
 * (pagination/status-filtering, the R1-reference match a suggested-line lookup surfaces, and the
 * open-contribution name search respectively).
 */
class BankStatementStoreTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdImportIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        fun setAccountMapping(
            bankAccountId: Uuid?,
            incomeAccountId: Uuid?,
            donationIncomeAccountId: Uuid? = null,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = bankAccountId
                    it[contributionIncomeAccountId] = incomeAccountId
                    it[OrganizationSettingsTable.donationIncomeAccountId] = donationIncomeAccountId
                }
            }
        }

        fun createLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = "SS${Uuid.random().toString().take(6)}"
                    it[name] = "Testkonto"
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

        fun createMemberWithContribution(amountDue: BigDecimal): Uuid {
            val memberId = Uuid.random()
            val tierId = Uuid.random()
            val contributionId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "Store-Test Mitglied"
                    it[email] = "store-test-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.TREASURER
                }
                MembershipTierTable.insert {
                    it[id] = tierId
                    it[name] = "Standard"
                    it[description] = "Standard"
                    it[contributionAmount] = amountDue
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
                ContributionTable.insert {
                    it[id] = contributionId
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[ContributionTable.amountDue] = amountDue
                    it[status] = ContributionStatus.OPEN
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[paymentReference] = null
                }
            }
            createdMemberIds += memberId
            createdTierIds += tierId
            createdContributionIds += contributionId
            return contributionId
        }

        afterTest {
            setAccountMapping(bankAccountId = null, incomeAccountId = null, donationIncomeAccountId = null)
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    val journalEntryIds =
                        JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList createdMemberIds }.map {
                            it[JournalEntryTable.id]
                        }
                    if (journalEntryIds.isNotEmpty()) {
                        PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                        JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                    }
                }
                if (createdImportIds.isNotEmpty()) {
                    BankStatementLineTable.deleteWhere { BankStatementLineTable.importId inList createdImportIds }
                    BankStatementImportTable.deleteWhere { BankStatementImportTable.id inList createdImportIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    PaymentTransactionTable.deleteWhere { PaymentTransactionTable.contributionId inList createdContributionIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // A donation's payment_transaction has NO contributionId (see
                    // assignLineToDonation) -- reached only via memberId (the donor)/reconciledBy
                    // (the actor), both FKing to member, so this must run before member deletion
                    // below or that FK blocks it.
                    PaymentTransactionTable.deleteWhere {
                        (PaymentTransactionTable.memberId inList createdMemberIds) or
                            (PaymentTransactionTable.reconciledBy inList createdMemberIds)
                    }
                }
                createdContributionIds.forEach { ContributionTable.deleteWhere { ContributionTable.id eq it } }
                createdTierIds.forEach { MembershipTierTable.deleteWhere { MembershipTierTable.id eq it } }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                createdMemberIds.forEach {
                    AccountTable.deleteWhere { AccountTable.memberId eq it }
                    MemberTable.deleteWhere { MemberTable.id eq it }
                }
            }
            createdMemberIds.clear()
            createdTierIds.clear()
            createdContributionIds.clear()
            createdLedgerAccountIds.clear()
            createdImportIds.clear()
        }

        fun uploaderId(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Kassenwart-Store-Test"
                    it[email] = "kassenwart-store-${Uuid.random()}@example.org"
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

        /** Imports one CSV line that matches NOTHING (unknown counterparty, no reference) -- always ends up UNMATCHED, ready for a manual assignment call. [bookingDate] is overridable (`TT.MM.JJJJ`) so a pagination test can force a deterministic `bookingDate DESC` order between two lines rather than relying on an unspecified tie-break for same-day rows. */
        fun importOneUnmatchedLine(
            amount: String,
            bookingDate: String = "15.03.2026",
        ): Uuid {
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;$bookingDate;$bookingDate;Gutschrift;Unbekannte Zahlung;Unbekannter Absender;;;$amount;EUR;")
                ).joinToString("\r\n")
            val result =
                BankStatementImportService(secretBox = null).import(
                    bytes = csv.toByteArray(),
                    fileName = "store-test-${Uuid.random()}.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)
            return transaction {
                BankStatementLineTable
                    .selectAll()
                    .where { BankStatementLineTable.importId eq Uuid.parse(result.importId) }
                    .single()[BankStatementLineTable.id]
            }
        }

        /** Like [importOneUnmatchedLine], but with a chosen [counterpartyName] -- lets R3 (name match) find a real candidate instead of always landing on UNMATCHED. */
        fun importLineWithCounterparty(
            amount: String,
            counterpartyName: String,
        ): Uuid {
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Beitrag;$counterpartyName;;;$amount;EUR;")
                ).joinToString("\r\n")
            val result =
                BankStatementImportService(secretBox = null).import(
                    bytes = csv.toByteArray(),
                    fileName = "store-test-${Uuid.random()}.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)
            return transaction {
                BankStatementLineTable
                    .selectAll()
                    .where { BankStatementLineTable.importId eq Uuid.parse(result.importId) }
                    .single()[BankStatementLineTable.id]
            }
        }

        test("listLines: filters by status and paginates") {
            // Distinct booking dates -- `listLines` orders by `bookingDate DESC` alone with no
            // secondary tie-break column, so two same-day rows would leave which one offset=0 vs.
            // offset=1 returns unspecified. Distinct dates make the order (and therefore this test)
            // deterministic: 2026-04-01 sorts before 2026-03-15 under DESC.
            val lineA = importOneUnmatchedLine(amount = "11,00", bookingDate = "01.04.2026")
            val lineB = importOneUnmatchedLine(amount = "12,00", bookingDate = "15.03.2026")

            val firstPage =
                BankStatementStore.listLines(
                    BankStatementLineQuery(status = BankStatementLineStatus.UNMATCHED, limit = 1, offset = 0),
                )
            firstPage.rows.size shouldBe 1
            firstPage.totalCount shouldNotBe 0
            firstPage.rows.single().id shouldBe lineA.toString()

            val secondPage =
                BankStatementStore.listLines(
                    BankStatementLineQuery(status = BankStatementLineStatus.UNMATCHED, limit = 1, offset = 1),
                )
            secondPage.rows.size shouldBe 1
            // Different rows -- limit/offset actually page through the result set rather than
            // returning the same row twice.
            firstPage.rows.single().id shouldNotBe secondPage.rows.single().id

            val importIdOfLineA =
                transaction {
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.id eq lineA }
                        .single()[BankStatementLineTable.importId]
                }
            val scopedToLineAsImport = BankStatementStore.listLines(BankStatementLineQuery(importId = importIdOfLineA.toString()))
            scopedToLineAsImport.rows.map { it.id } shouldContain lineA.toString()
            scopedToLineAsImport.rows.map { it.id } shouldNotContain lineB.toString()
        }

        test("suggestMatches: surfaces the same open contribution BankStatementMatcher's R3 (name match) would suggest") {
            val contributionId = createMemberWithContribution(amountDue = BigDecimal("48.00"))
            val lineId = importLineWithCounterparty(amount = "48,00", counterpartyName = "Store-Test Mitglied")

            val candidates = BankStatementStore.suggestMatches(lineId = lineId, secretBox = null)

            candidates.size shouldBe 1
            candidates.single().contributionId shouldBe contributionId.toString()
        }

        test("suggestMatches: a line matching NOTHING returns an empty candidate list") {
            val lineId = importOneUnmatchedLine(amount = "999,00")

            BankStatementStore.suggestMatches(lineId = lineId, secretBox = null) shouldBe emptyList()
        }

        test("searchAssignmentTargets: finds an open contribution by a case-insensitive member-name substring") {
            val contributionId = createMemberWithContribution(amountDue = BigDecimal("48.00"))

            val hits = BankStatementStore.searchAssignmentTargets(term = "store-test", limit = 10)
            hits.map { it.contributionId } shouldContain contributionId.toString()

            // A blank search term is a deliberate short-circuit (see BankStatementStore
            // .searchAssignmentTargets KDoc-less but self-explanatory `if (trimmedTerm.isEmpty())`
            // guard) -- never "match everything".
            BankStatementStore.searchAssignmentTargets(term = "   ", limit = 10) shouldBe emptyList()
        }

        test("assignLineToContribution: happy path posts a journal entry, PAIDs the contribution, POSTS the line") {
            val bankAccountId = createLedgerAccount(LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
            val contributionId = createMemberWithContribution(amountDue = BigDecimal("30.00"))
            val actor = uploaderId()
            val lineId = importOneUnmatchedLine(amount = "30,00")

            val dto =
                BankStatementStore.assignLineToContribution(
                    lineId = lineId,
                    contributionId = contributionId,
                    note = "Manuelle Zuordnung Test",
                    actorMemberId = actor,
                    actorRole = AccountRole.TREASURER,
                )

            dto.status shouldBe BankStatementLineStatus.POSTED
            dto.paymentTransactionId shouldNotBe null

            transaction {
                ContributionTable
                    .selectAll()
                    .where { ContributionTable.id eq contributionId }
                    .single()[ContributionTable.status] shouldBe ContributionStatus.PAID
                JournalEntryTable.selectAll().where { JournalEntryTable.createdBy eq actor }.count() shouldBe 1
            }
        }

        test(
            "assignLineToContribution: unconfigured account mapping throws ConflictException and rolls back -- " +
                "contribution stays OPEN, no orphaned payment_transaction, line stays assignable",
        ) {
            // Review fix (MAJOR) regression -- see BankStatementStore.assignLineToContribution's own
            // "Review fix (MAJOR)" comment. Previously this call would silently return a
            // status = POSTED DTO with no journal entry, permanently trapping the contribution as
            // PAID (requireAssignableLine blocks any further reassignment of an already-POSTED line).
            val contributionId = createMemberWithContribution(amountDue = BigDecimal("30.00"))
            val actor = uploaderId()
            val lineId = importOneUnmatchedLine(amount = "30,00")
            // Deliberately no setAccountMapping() call -- mapping stays unconfigured.

            val exception =
                shouldThrow<ConflictException> {
                    BankStatementStore.assignLineToContribution(
                        lineId = lineId,
                        contributionId = contributionId,
                        note = null,
                        actorMemberId = actor,
                        actorRole = AccountRole.TREASURER,
                    )
                }
            exception.message shouldBe
                "Beitrag konnte nicht gebucht werden (Kontenzuordnung unvollstaendig oder Konto deaktiviert). " +
                "Bitte Kontenzuordnung in den Organisationseinstellungen pruefen, bevor erneut zugeordnet wird."

            transaction {
                ContributionTable
                    .selectAll()
                    .where { ContributionTable.id eq contributionId }
                    .single()[ContributionTable.status] shouldBe ContributionStatus.OPEN
                PaymentTransactionTable
                    .selectAll()
                    .where { PaymentTransactionTable.contributionId eq contributionId }
                    .count() shouldBe 0
                val lineRow = BankStatementLineTable.selectAll().where { BankStatementLineTable.id eq lineId }.single()
                lineRow[BankStatementLineTable.status] shouldBe BankStatementLineStatus.UNMATCHED
            }

            // The line must still be assignable -- the whole point of the fix: a later, correctly
            // configured retry can succeed once an ADMIN fixes the account mapping.
            val bankAccountId = createLedgerAccount(LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
            val dto =
                BankStatementStore.assignLineToContribution(
                    lineId = lineId,
                    contributionId = contributionId,
                    note = null,
                    actorMemberId = actor,
                    actorRole = AccountRole.TREASURER,
                )
            dto.status shouldBe BankStatementLineStatus.POSTED
        }

        test("requireAssignableLine: a POSTED line can never be reassigned") {
            val bankAccountId = createLedgerAccount(LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
            val contributionId = createMemberWithContribution(amountDue = BigDecimal("15.00"))
            val actor = uploaderId()
            val lineId = importOneUnmatchedLine(amount = "15,00")
            BankStatementStore.assignLineToContribution(
                lineId = lineId,
                contributionId = contributionId,
                note = null,
                actorMemberId = actor,
                actorRole = AccountRole.TREASURER,
            )

            val exception =
                shouldThrow<ConflictException> {
                    BankStatementStore.ignoreLine(lineId = lineId, reason = "Versehentlich", actorMemberId = actor)
                }
            exception.message shouldBe "Diese Zeile ist bereits gebucht -- Korrektur nur per Storno in der Buchhaltung."
        }

        test("assignLineToDonation: happy path posts a journal entry and POSTS the line") {
            val bankAccountId = createLedgerAccount(LedgerAccountType.ASSET)
            val donationIncomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, incomeAccountId = null, donationIncomeAccountId = donationIncomeAccountId)
            val actor = uploaderId()
            // A real member as the donor -- donorMemberId (unlike externalDonorId) needs no separate
            // external_donor row, journal_entry.donor_member_id FKs straight to member.
            val donorMemberId = uploaderId()
            val lineId = importOneUnmatchedLine(amount = "20,00")

            val dto =
                BankStatementStore.assignLineToDonation(
                    lineId = lineId,
                    input =
                        BankStatementDonationAssignmentInput(
                            donorMemberId = donorMemberId.toString(),
                            externalDonorId = null,
                            donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                            note = "Spende Test",
                        ),
                    actorMemberId = actor,
                    actorRole = AccountRole.TREASURER,
                )

            dto.status shouldBe BankStatementLineStatus.POSTED
            dto.paymentTransactionId shouldNotBe null
        }

        test(
            "assignLineToContribution racing assignLineToDonation on the SAME line: forUpdate() " +
                "serializes them -- exactly one wins, the loser gets ConflictException, no double-booking",
        ) {
            // Security finding fix (Review MAJOR) regression: before requireAssignableLine's
            // `.forUpdate()`, both paths could read the line as not-POSTED simultaneously and both
            // proceed to book -- each uses a DIFFERENT payment_transaction.provider_event_id
            // fingerprint ("manual-assign:$lineId" vs. "manual-donation:$lineId"), so
            // uq_payment_transaction_provider_event never caught the cross-path collision (one real
            // bank transaction, two journal entries). The row lock now forces the second caller to
            // block until the first commits, then re-read the now-POSTED status and reject.
            val bankAccountId = createLedgerAccount(LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            val donationIncomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            setAccountMapping(
                bankAccountId = bankAccountId,
                incomeAccountId = incomeAccountId,
                donationIncomeAccountId = donationIncomeAccountId,
            )
            val contributionId = createMemberWithContribution(amountDue = BigDecimal("30.00"))
            val actor = uploaderId()
            val donorMemberId = uploaderId()
            val lineId = importOneUnmatchedLine(amount = "30,00")

            val startLatch = CountDownLatch(2)
            val doneLatch = CountDownLatch(2)
            val results = Collections.synchronizedList(mutableListOf<Result<Unit>>())

            val contributionThread =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        results +=
                            runCatching {
                                BankStatementStore.assignLineToContribution(
                                    lineId = lineId,
                                    contributionId = contributionId,
                                    note = "Racer: Beitrag",
                                    actorMemberId = actor,
                                    actorRole = AccountRole.TREASURER,
                                )
                                Unit
                            }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            val donationThread =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        results +=
                            runCatching {
                                BankStatementStore.assignLineToDonation(
                                    lineId = lineId,
                                    input =
                                        BankStatementDonationAssignmentInput(
                                            donorMemberId = donorMemberId.toString(),
                                            externalDonorId = null,
                                            donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                                            note = "Racer: Spende",
                                        ),
                                    actorMemberId = actor,
                                    actorRole = AccountRole.TREASURER,
                                )
                                Unit
                            }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            contributionThread.start()
            donationThread.start()
            check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent assignment attempts did not complete in time" }

            results.count { it.isSuccess } shouldBe 1
            results.count { it.isFailure } shouldBe 1
            (results.single { it.isFailure }.exceptionOrNull() is ConflictException) shouldBe true

            // DB-level confirmation: exactly one payment_transaction for this line across BOTH
            // possible fingerprints, not two -- the actual double-booking this fix closes.
            val paymentTransactionCount =
                transaction {
                    PaymentTransactionTable
                        .selectAll()
                        .where {
                            (PaymentTransactionTable.providerEventId eq "manual-assign:$lineId") or
                                (PaymentTransactionTable.providerEventId eq "manual-donation:$lineId")
                        }.count()
                }
            paymentTransactionCount shouldBe 1L

            transaction {
                BankStatementLineTable
                    .selectAll()
                    .where { BankStatementLineTable.id eq lineId }
                    .single()[BankStatementLineTable.status] shouldBe BankStatementLineStatus.POSTED
            }
        }

        test("ignoreLine: requires a non-blank reason and persists it") {
            val lineId = importOneUnmatchedLine(amount = "5,00")
            val actor = uploaderId()

            shouldThrow<BadRequestException> {
                BankStatementStore.ignoreLine(lineId = lineId, reason = "   ", actorMemberId = actor)
            }

            val dto =
                BankStatementStore.ignoreLine(
                    lineId = lineId,
                    reason = "Ausgangsbuchung, keine Zuordnung noetig",
                    actorMemberId = actor,
                )
            dto.status shouldBe BankStatementLineStatus.IGNORED
            dto.resolutionNote shouldBe "Ausgangsbuchung, keine Zuordnung noetig"
        }
    })
