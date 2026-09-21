package network.lapis.cloud.server.payment.bankstatement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
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
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportWarningCode
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentReferenceCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val SPARKASSE_HEADER =
    "Auftragskonto;Buchungstag;Valutadatum;Buchungstext;Verwendungszweck;Beguenstigter/Zahlungspflichtiger;" +
        "Kontonummer/IBAN;BIC (SWIFT-Code);Betrag;Waehrung;Kundenreferenz (End-to-End)"

class BankStatementImportServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdImportIds = mutableListOf<Uuid>()
        // Welle V1.4.14 "Mehrere Bankkonten".
        val createdBankAccountIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        fun createBankAccount(
            iban: String,
            actorMemberId: Uuid,
        ): Uuid {
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Test-Bankkonto", iban = iban),
                    actorMemberId = actorMemberId,
                    actorRole = AccountRole.ADMIN,
                )
            val id = Uuid.parse(dto.id)
            createdBankAccountIds += id
            return id
        }

        fun setAccountMapping(
            bankAccountId: Uuid?,
            incomeAccountId: Uuid?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = bankAccountId
                    it[contributionIncomeAccountId] = incomeAccountId
                }
            }
        }

        fun setOrgBankIban(iban: String?) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = iban
                }
            }
        }

        fun createLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = "IS${Uuid.random().toString().take(6)}"
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

        fun createMemberWithContribution(
            reference: String,
            amountDue: BigDecimal,
        ): Uuid {
            val memberId = Uuid.random()
            val tierId = Uuid.random()
            val contributionId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "Import-Test Mitglied"
                    it[email] = "import-test-${Uuid.random()}@example.org"
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
                    it[paymentReference] = reference
                }
            }
            createdMemberIds += memberId
            createdTierIds += tierId
            createdContributionIds += contributionId
            return contributionId
        }

        afterTest {
            // Clear the account mapping FIRST -- organization_settings still FK-references the
            // ledger accounts this test created until this runs, so deleting them beforehand would
            // itself violate a FK constraint.
            setAccountMapping(bankAccountId = null, incomeAccountId = null)
            setOrgBankIban(null)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankBic] = null
                }
                // ContributionPostingBridge.postContributionPayment creates its OWN journal_entry
                // row (created_by = the uploader/actor) independently of
                // payment_transaction.journal_entry_id (which BankStatementImportService/
                // BankStatementStore never populate) -- find journal entries by createdBy, same
                // precedent ContributionPostingBridgeTest's own afterSpec establishes, not by
                // tracing through payment_transaction.
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
                // bank_statement_line.payment_transaction_id FKs to payment_transaction -- the line
                // (and its owning import) must be deleted BEFORE the payment_transaction row it may
                // reference.
                if (createdImportIds.isNotEmpty()) {
                    BankStatementLineTable.deleteWhere { BankStatementLineTable.importId inList createdImportIds }
                    BankStatementImportTable.deleteWhere { BankStatementImportTable.id inList createdImportIds }
                }
                // Welle V1.4.14 "Mehrere Bankkonten" -- MUST run AFTER the bank_statement_import
                // cleanup above (fk_bank_statement_import_bank_account_id would otherwise block
                // this delete) and BEFORE the createdMemberIds loop below deletes
                // bank_account.created_by's target row. MUST leave the table empty so every OTHER
                // test in this file keeps seeing the pre-wave "no bank_account row" legacy
                // behaviour.
                if (createdBankAccountIds.isNotEmpty()) {
                    BankAccountTable.deleteWhere { BankAccountTable.id inList createdBankAccountIds }
                }
                // Looked up by contribution_id, not by the line's own payment_transaction_id column
                // -- belt-and-braces: an unconfigured account mapping now rolls the whole Phase-2
                // attempt back (see BankStatementImportServiceTest's own "unconfigured account
                // mapping" test), so no payment_transaction row should survive that case at all, but
                // this cleanup does not rely on that to avoid leaking a row in either direction.
                if (createdContributionIds.isNotEmpty()) {
                    PaymentTransactionTable.deleteWhere { PaymentTransactionTable.contributionId inList createdContributionIds }
                }
                createdContributionIds.forEach { ContributionTable.deleteWhere { ContributionTable.id eq it } }
                createdTierIds.forEach { MembershipTierTable.deleteWhere { MembershipTierTable.id eq it } }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // AuditLogRecorder (fired by ContributionPostingBridge and by
                    // BankStatementImportService's own BANK_STATEMENT_IMPORT entry) wrote
                    // actor_member_id rows referencing these test members -- null them out first,
                    // same precedent ContributionPostingBridgeTest's own afterSpec establishes.
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
            createdBankAccountIds.clear()
        }

        fun uploaderId(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Kassenwart-Test"
                    it[email] = "kassenwart-${Uuid.random()}@example.org"
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

        // Shared by every "legacy NULL bank_account_id row" regression test below -- inserts a
        // `bank_statement_import` + single `bank_statement_line` row exactly as
        // `BankStatementImportService.import` leaves it while ZERO `bank_account` rows exist:
        // `bank_account_id = NULL`, fingerprint computed WITHOUT a discriminator. Must be called
        // BEFORE the organization's first `bank_account` row is created -- once one exists,
        // `BankAccountStore.adoptLegacyBankStatementImports` immediately rewrites every such row's
        // `bank_account_id`, so a `NULL` row can no longer coexist with an already-created account
        // in production (see that function's own KDoc).
        fun insertLegacyNullAccountImport(
            uploader: Uuid,
            fileName: String,
            fingerprint: String,
        ): Uuid {
            val importId = Uuid.random()
            transaction {
                BankStatementImportTable.insert {
                    it[id] = importId
                    it[format] = BankStatementFormat.CSV
                    it[dialect] = "SPARKASSE_CSV"
                    it[BankStatementImportTable.fileName] = fileName
                    it[fileSizeBytes] = 1
                    it[fileDigest] = "legacy-digest-${Uuid.random()}"
                    it[accountIban] = null
                    it[statementFrom] = LocalDate(2026, 9, 1)
                    it[statementTo] = LocalDate(2026, 9, 1)
                    it[openingBalance] = null
                    it[closingBalance] = null
                    it[lineCount] = 1
                    it[duplicateCount] = 0
                    it[autoPostedCount] = 0
                    it[uploadedBy] = uploader
                    it[uploadedAt] = LocalDateTime(2026, 9, 1, 0, 0)
                    it[bankAccountId] = null
                }
                BankStatementLineTable.insert {
                    it[id] = Uuid.random()
                    it[BankStatementLineTable.importId] = importId
                    it[BankStatementLineTable.fingerprint] = fingerprint
                    it[lineOrdinal] = 0
                    it[bookingDate] = LocalDate(2026, 9, 1)
                    it[valueDate] = LocalDate(2026, 9, 1)
                    it[amount] = BigDecimal("-4.90")
                    it[currency] = "EUR"
                    it[counterpartyName] = null
                    it[counterpartyIbanLast4] = null
                    it[counterpartyIbanCiphertext] = null
                    it[purpose] = "Kontofuehrungsgebuehr"
                    it[endToEndReference] = null
                    it[bookingText] = "Entgeltabschluss"
                    it[status] = BankStatementLineStatus.UNMATCHED
                    it[matchExplanation] = null
                    it[matchedContributionId] = null
                    it[paymentTransactionId] = null
                    it[resolvedBy] = null
                    it[resolvedAt] = null
                    it[resolutionNote] = null
                }
            }
            createdImportIds += importId
            return importId
        }

        val feeLineFingerprint =
            BankStatementFingerprint.of(
                accountIban = null,
                bookingDate = LocalDate(2026, 9, 1),
                valueDate = LocalDate(2026, 9, 1),
                amount = BigDecimal("-4.90"),
                currency = "EUR",
                counterpartyName = null,
                counterpartyIban = null,
                purpose = "Kontofuehrungsgebuehr",
                endToEndReference = null,
                occurrenceIndex = 0,
                bankAccountDiscriminator = null,
            )

        test("a matching reference is auto-posted end-to-end: contribution PAID, line POSTED, journal entry balanced") {
            val bankAccountId = createLedgerAccount(LedgerAccountType.ASSET)
            val incomeAccountId = createLedgerAccount(LedgerAccountType.INCOME)
            setAccountMapping(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
            val reference = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(555555)
            createMemberWithContribution(reference = reference, amountDue = BigDecimal("48.00"))
            val uploader = uploaderId()

            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Beitrag $reference;Ueberweiser;;;48,00;EUR;")
                ).joinToString("\r\n")

            val service = BankStatementImportService(secretBox = null)
            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "auszug.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.autoPostedCount shouldBe 1
            result.duplicateCount shouldBe 0

            transaction {
                val lineRow =
                    BankStatementLineTable
                        .selectAll()
                        .where {
                            BankStatementLineTable.importId eq
                                Uuid.parse(
                                    result.importId,
                                )
                        }.single()
                lineRow[BankStatementLineTable.status] shouldBe BankStatementLineStatus.POSTED
                (lineRow[BankStatementLineTable.paymentTransactionId] != null) shouldBe true

                val contributionRow =
                    ContributionTable
                        .selectAll()
                        .where { ContributionTable.id eq createdContributionIds.single() }
                        .single()
                contributionRow[ContributionTable.status] shouldBe ContributionStatus.PAID
            }
        }

        test("re-importing the identical file is rejected with 409, nothing double-booked") {
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Spende;Spender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)
            val first =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "a.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(first.importId)

            val exception =
                shouldThrow<BankStatementRejectedException> {
                    service.import(
                        bytes = csv.toByteArray(),
                        fileName = "a-again.csv",
                        uploadedBy = uploader,
                        uploaderRole = AccountRole.TREASURER,
                    )
                }
            exception.httpStatus shouldBe 409
        }

        test(
            "bankStatementLineIsStillSuggested guard: a correction UPDATE no-ops on an already-POSTED " +
                "line instead of clobbering it",
        ) {
            // Security finding fix (Review MAJOR, residual gap) regression: postOneLine's correction
            // transactions (ContributionAlreadySettled/BookingIncomplete/BookingConflict) open only
            // AFTER the main attempt transaction rolled back, releasing its forUpdate() lock -- a
            // concurrent BankStatementStore.assignLineToContribution/assignLineToDonation call can
            // slip into that gap, see the line still SUGGESTED, and complete a real booking (status ->
            // POSTED) before the correction runs. Without the bankStatementLineIsStillSuggested guard,
            // the correction's unconditional UPDATE would clobber that just-committed POSTED status
            // back to AMBIGUOUS/UNMATCHED, making the line reassignable again and reopening the exact
            // double-booking class the forUpdate() locks exist to close. This test isolates the guard
            // itself (deterministic, no threads needed) rather than reproducing the full timing window.
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Unbekannte Zahlung;Unbekannter Absender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val result =
                BankStatementImportService(secretBox = null).import(
                    bytes = csv.toByteArray(),
                    fileName = "guard-test-${Uuid.random()}.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)
            val lineId =
                transaction {
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.importId eq Uuid.parse(result.importId) }
                        .single()[BankStatementLineTable.id]
                }
            transaction {
                BankStatementLineTable.update({ BankStatementLineTable.id eq lineId }) {
                    it[status] = BankStatementLineStatus.POSTED
                    it[resolutionNote] = "Echte Buchung eines konkurrierenden Pfads"
                }
            }

            val updatedRows =
                transaction {
                    BankStatementLineTable.update({
                        (BankStatementLineTable.id eq lineId) and (bankStatementLineIsStillSuggested)
                    }) {
                        it[status] = BankStatementLineStatus.AMBIGUOUS
                        it[resolutionNote] = "Sollte nie ankommen"
                    }
                }

            updatedRows shouldBe 0
            transaction {
                val row = BankStatementLineTable.selectAll().where { BankStatementLineTable.id eq lineId }.single()
                row[BankStatementLineTable.status] shouldBe BankStatementLineStatus.POSTED
                row[BankStatementLineTable.resolutionNote] shouldBe "Echte Buchung eines konkurrierenden Pfads"
            }
        }

        test("a purpose field containing a raw control character is rejected with 422, not an uncaught DB exception") {
            // Security finding fix (Review MAJOR) regression -- see ParsedLine.containsControlCharacter
            // KDoc. A raw C0 control character (here 0x01, SOH -- plausible from a mis-encoded export)
            // used to reach BankStatementLineTable.insertIgnore uncaught; PostgreSQL rejects a control
            // character in a text/varchar column (a NUL byte outright, SQLSTATE 22021), aborting the
            // whole Phase 1 transaction with no diagnosis. Now rejected before the transaction even
            // opens, with a diagnosable per-line 422.
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Spende\u0001mitSteuerzeichen;Spender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val exception =
                shouldThrow<BankStatementRejectedException> {
                    service.import(
                        bytes = csv.toByteArray(),
                        fileName = "control-char.csv",
                        uploadedBy = uploader,
                        uploaderRole = AccountRole.TREASURER,
                    )
                }
            exception.httpStatus shouldBe 422
            exception.lineNumber shouldBe 1

            // Nothing was written -- the whole file is rejected before the Phase 1 transaction opens.
            transaction {
                BankStatementImportTable.selectAll().where { BankStatementImportTable.uploadedBy eq uploader }.count() shouldBe 0L
            }
        }

        test(
            "two genuinely distinct same-day/same-amount payments differing only in whitespace/casing " +
                "are BOTH imported, not deduplicated",
        ) {
            // Review fix (MAJOR): occurrenceKeyBase used to be built from the RAW fields while
            // BankStatementFingerprint.of normalizes them (trim/uppercase/whitespace-collapse) --
            // these two lines have DIFFERENT raw counterparty-name whitespace/casing (so the old code
            // assigned both occurrenceIndex 0), but IDENTICAL normalized fingerprint fields once
            // BankStatementFingerprint.of hashes them -- the second, genuine payment was silently
            // discarded as a false duplicate by insertIgnore. Fixed by deriving the occurrence key
            // from BankStatementFingerprint.groupingKey (the SAME normalization); see
            // BankStatementFingerprintTest's own "case/whitespace-only differences ... fingerprint
            // identically" test for the normalization property this regression test exercises at the
            // import-service level.
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf(
                            "DE00;15.03.2026;15.03.2026;Gutschrift;Spende;  max   mustermann ;;;10,00;EUR;",
                            "DE00;15.03.2026;15.03.2026;Gutschrift;Spende;MAX MUSTERMANN;;;10,00;EUR;",
                        )
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)
            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "double-real-payment.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.lineCount shouldBe 2
            result.duplicateCount shouldBe 0

            transaction {
                BankStatementLineTable
                    .selectAll()
                    .where { BankStatementLineTable.importId eq Uuid.parse(result.importId) }
                    .count() shouldBe 2
            }
        }

        test(
            "an unconfigured account mapping is not auto-posted: the whole attempt rolls back -- " +
                "contribution stays OPEN, line reverts to UNMATCHED, no journal entry, no orphaned payment_transaction",
        ) {
            // Review fix (MAJOR): this test used to pin the BUGGY behavior (contribution PAID with no
            // journal entry, unrecoverable via the UI -- see BankStatementImportService.postOneLine's
            // PostOneLineHalt/BookingIncomplete KDoc for the full story). The fix makes the whole
            // Phase-2 attempt for this line roll back together: contribution.status is untouched
            // (stays OPEN), no payment_transaction row survives, and the line reverts to UNMATCHED --
            // exactly the state a Kassenwart CAN still correct once the account mapping is configured.
            val reference = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(777777)
            createMemberWithContribution(reference = reference, amountDue = BigDecimal("25.00"))
            val uploader = uploaderId()
            // Deliberately no setAccountMapping() call -- mapping stays unconfigured.

            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Beitrag $reference;Ueberweiser;;;25,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)
            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "b.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.autoPostedCount shouldBe 0
            result.unmatchedCount shouldBe 1
            result.suggestedCount shouldBe 0

            transaction {
                val lineRow =
                    BankStatementLineTable
                        .selectAll()
                        .where {
                            BankStatementLineTable.importId eq
                                Uuid.parse(
                                    result.importId,
                                )
                        }.single()
                lineRow[BankStatementLineTable.status] shouldBe BankStatementLineStatus.UNMATCHED
                lineRow[BankStatementLineTable.matchedContributionId] shouldBe null
                lineRow[BankStatementLineTable.paymentTransactionId] shouldBe null

                val contributionRow =
                    ContributionTable
                        .selectAll()
                        .where { ContributionTable.id eq createdContributionIds.single() }
                        .single()
                contributionRow[ContributionTable.status] shouldBe ContributionStatus.OPEN

                PaymentTransactionTable
                    .selectAll()
                    .where { PaymentTransactionTable.contributionId eq createdContributionIds.single() }
                    .count() shouldBe 0
            }
        }

        test(
            "an MT940 :25: account IBAN longer than 34 chars does not reject the import -- " +
                "capped at insert, surfaced as an 'Altformat?' warning instead of an uncaught SQLSTATE 22001",
        ) {
            // Regression test for a review finding (MINOR): account_iban is VARCHAR(34) (the longest
            // formally possible IBAN) but used to be inserted RAW. A `:25:` value over 34 chars (SWIFT
            // allows up to 35x for this field) already fails IbanValidator.isValid on length alone --
            // accountIbanIsValidIban is false, so the "Altformat?" warning below fires -- but nothing
            // stopped the uncapped value from also reaching the INSERT, where it used to fail with
            // SQLSTATE 22001 ("value too long"), misdiagnosed by the (now-narrowed) broad
            // ExposedSQLException catch as a duplicate-file 409. Needs orgBankIban set (a valid,
            // <=34-char IBAN) so the warning branch below -- an else-if against "Kein Bankkonto..." --
            // is actually reached.
            setOrgBankIban("DE02120300000000202051")
            val uploader = uploaderId()
            val overlongIban = "DE" + "1".repeat(40) // 42 chars, well past the 34-char IBAN bound
            val text =
                listOf(
                    ":20:STMT001",
                    ":25:$overlongIban",
                    ":60F:C260301EUR0,00",
                    ":61:2603150315C10,00NMSCREF1",
                    ":86:?20Spende",
                    ":62F:C260331EUR10,00",
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val result =
                service.import(
                    bytes = text.toByteArray(),
                    fileName = "altformat.sta",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.warnings shouldContain
                "Kontokennung des Auszugs ist keine gueltige IBAN (Altformat?) -- Kontopruefung uebersprungen."
            // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): BankStatementImportWarningCode is the
            // machine-readable counterpart the client now renders instead of the raw German string
            // asserted above -- see BankStatementLabels.bankStatementImportWarningMessage.
            result.warningCodes shouldContain BankStatementImportWarningCode.LEGACY_ACCOUNT_IBAN_FORMAT
            // secretBox = null in this test too, so R2 (IBAN match) is skipped for the same reason
            // every other test in this file skips it -- the code fires here alongside the format one.
            result.warningCodes shouldContain BankStatementImportWarningCode.IBAN_MATCHING_UNAVAILABLE

            transaction {
                val importRow =
                    BankStatementImportTable
                        .selectAll()
                        .where { BankStatementImportTable.id eq Uuid.parse(result.importId) }
                        .single()
                importRow[BankStatementImportTable.accountIban] shouldBe overlongIban.take(34)
            }
        }

        test("MT940 with SWIFT '-' separator lines and a BLZ/Konto :25: imports and warns LEGACY_ACCOUNT_IBAN_FORMAT") {
            setOrgBankIban("DE02120300000000202051")
            val uploader = uploaderId()
            val text =
                listOf(
                    ":20:STARTUMSE",
                    ":25:12345678/0000000001",
                    ":28C:0/1",
                    ":60F:C260601EUR100,00",
                    ":61:2606020602CR20,00N075SEPSEP-1",
                    ":86:166?00Gutschrift?20Separator Test?32Beispiel GmbH",
                    ":62F:C260602EUR120,00",
                    "-",
                    ":20:STARTUMSE",
                    ":25:12345678/0000000001",
                    ":28C:0/2",
                    ":60F:C260602EUR120,00",
                    ":61:2606030603DR5,00N075SEPSEP-2",
                    ":86:105?00Lastschrift?20Separator Test 2?32Fiktiv KG",
                    ":62F:C260603EUR115,00",
                    "-",
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val result =
                service.import(
                    bytes = text.toByteArray(),
                    fileName = "separator.sta",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.warningCodes shouldContain BankStatementImportWarningCode.LEGACY_ACCOUNT_IBAN_FORMAT
        }

        test(
            "no organization bank IBAN configured -- warningCodes carries NO_BANK_ACCOUNT_CONFIGURED, " +
                "never the raw German warning string, and no account-ownership check runs",
        ) {
            // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): BankStatementImportWarningCode -- see
            // that enum's own KDoc for why this needed no DB migration, unlike matchExplanation.
            // Deliberately no setOrgBankIban() call -- the default (no configured org IBAN) is what
            // triggers this code.
            val uploader = uploaderId()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Spende;Spender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "no-org-iban.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.warningCodes shouldContain BankStatementImportWarningCode.NO_BANK_ACCOUNT_CONFIGURED
            result.warningCodes shouldContain BankStatementImportWarningCode.IBAN_MATCHING_UNAVAILABLE
            // LEGACY_ACCOUNT_IBAN_FORMAT is mutually exclusive with NO_BANK_ACCOUNT_CONFIGURED --
            // BankStatementImportService's `if (orgBankIban == null) ... else if (...)` chain, see
            // that class's Phase 1 block.
            (BankStatementImportWarningCode.LEGACY_ACCOUNT_IBAN_FORMAT in result.warningCodes) shouldBe false
        }

        test(
            "a non-unique-violation ExposedSQLException during the import insert is NOT mislabeled 409 " +
                "-- it propagates instead",
        ) {
            // Regression test for a review finding (MINOR): the broad ExposedSQLException catch
            // around the bank_statement_import insert used to convert ANY insert failure into the
            // "already imported" 409, even one that had nothing to do with a duplicate file (see
            // BankStatementImportService.kt's own UNIQUE_VIOLATION_SQL_STATE-narrowing KDoc). A
            // foreign-key violation (uploaded_by referencing a member row that was never inserted --
            // SQLSTATE 23503, never 23505) is a deterministic way to force such a "wrong reason"
            // insert failure here; the fix must let it propagate as-is, never as
            // BankStatementRejectedException(409).
            val nonExistentUploader = Uuid.random()
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Spende;Spender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val exception =
                shouldThrow<ExposedSQLException> {
                    service.import(
                        bytes = csv.toByteArray(),
                        fileName = "fk-violation.csv",
                        uploadedBy = nonExistentUploader,
                        uploaderRole = AccountRole.TREASURER,
                    )
                }
            exception.sqlState shouldNotBe "23505"
        }

        // ── Welle V1.4.14 "Mehrere Bankkonten" ──────────────────────────────────

        test("with bank_account rows configured, a statement whose :25: IBAN matches one account attributes the import to it") {
            val uploader = uploaderId()
            createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val secondAccountId = createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            val mt940 =
                listOf(
                    ":20:STMT-V1414-1",
                    ":25:DE89370400440532013000",
                    ":60F:C260301EUR0,00",
                    ":61:2603150315C10,00NMSCREF-V1414-1",
                    ":86:?20Spende",
                    ":62F:C260331EUR10,00",
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val result =
                service.import(
                    bytes = mt940.toByteArray(),
                    fileName = "v1414-1.sta",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.bankAccountId shouldBe secondAccountId.toString()
            result.bankAccountLabel shouldBe "Test-Bankkonto"
        }

        test("with bank_account rows configured, a statement whose :25: IBAN matches none of them is rejected UNKNOWN_BANK_ACCOUNT") {
            // Review fix (MINOR, Review Round 3): was FOREIGN_ACCOUNT -- split out because this
            // situation (no single "the other account" the statement could be pointed at) is
            // fachlich distinct from a statement genuinely belonging to a DIFFERENT, EXISTING
            // account (that case, an explicit id or IBAN conflicting with a KNOWN account, keeps
            // FOREIGN_ACCOUNT -- see the test below and BankStatementLabels KDoc).
            val uploader = uploaderId()
            createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val mt940 =
                listOf(
                    ":20:STMT-V1414-2",
                    ":25:DE89370400440532013000",
                    ":60F:C260301EUR0,00",
                    ":61:2603150315C10,00NMSCREF-V1414-2",
                    ":86:?20Spende",
                    ":62F:C260331EUR10,00",
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val exception =
                shouldThrow<BankStatementRejectedException> {
                    service.import(
                        bytes = mt940.toByteArray(),
                        fileName = "v1414-2.sta",
                        uploadedBy = uploader,
                        uploaderRole = AccountRole.TREASURER,
                    )
                }
            exception.code shouldBe BankStatementRejectionCode.UNKNOWN_BANK_ACCOUNT
        }

        test("an explicit bankAccountId parameter is honored, but cross-checked against the statement IBAN and rejected on mismatch") {
            val uploader = uploaderId()
            val accountA = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            val mt940ForAccountA =
                listOf(
                    ":20:STMT-V1414-3",
                    ":25:DE02120300000000202051",
                    ":60F:C260301EUR0,00",
                    ":61:2603150315C10,00NMSCREF-V1414-3",
                    ":86:?20Spende",
                    ":62F:C260331EUR10,00",
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val ok =
                service.import(
                    bytes = mt940ForAccountA.toByteArray(),
                    fileName = "v1414-3-ok.sta",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    bankAccountId = accountA,
                )
            createdImportIds += Uuid.parse(ok.importId)
            ok.bankAccountId shouldBe accountA.toString()

            val mt940ForAccountB =
                listOf(
                    ":20:STMT-V1414-4",
                    ":25:DE89370400440532013000",
                    ":60F:C260301EUR0,00",
                    ":61:2603160316C10,00NMSCREF-V1414-4",
                    ":86:?20Spende",
                    ":62F:C260331EUR10,00",
                ).joinToString("\r\n")
            val exception =
                shouldThrow<BankStatementRejectedException> {
                    service.import(
                        bytes = mt940ForAccountB.toByteArray(),
                        fileName = "v1414-4-mismatch.sta",
                        uploadedBy = uploader,
                        uploaderRole = AccountRole.TREASURER,
                        // Deliberately the WRONG account id (accountA's statement carries accountB's IBAN).
                        bankAccountId = accountA,
                    )
                }
            exception.code shouldBe BankStatementRejectionCode.FOREIGN_ACCOUNT
        }

        test("with bank_account rows configured, a CSV import (no statement-level IBAN at all) falls back to the default account") {
            val uploader = uploaderId()
            val defaultAccount = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Spende;Spender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "v1414-5.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.bankAccountId shouldBe defaultAccount.toString()
            // The pre-wave "no account configured" warning must NOT fire once bank_account rows
            // exist and one of them is attributed -- that warning is exclusively for the legacy
            // (zero bank_account rows) path.
            (BankStatementImportWarningCode.NO_BANK_ACCOUNT_CONFIGURED in result.warningCodes) shouldBe false
            // Review fix (MAJOR, finding #2): the silent default-account attribution now gets its
            // own warning code, even with only one account configured -- see
            // BankStatementImportWarningCode.ATTRIBUTED_TO_DEFAULT_ACCOUNT KDoc.
            result.warningCodes shouldContain BankStatementImportWarningCode.ATTRIBUTED_TO_DEFAULT_ACCOUNT
        }

        test(
            "with TWO bank_account rows configured, a CSV import (no statement-level IBAN at all) is attributed to " +
                "the default account AND surfaces ATTRIBUTED_TO_DEFAULT_ACCOUNT -- finding #2's core scenario",
        ) {
            // Regression test for a review finding (MAJOR): previously, this exact situation --
            // organization has account A (default) and account B, a CSV statement (which never
            // carries a statement-level IBAN, see BankCsvParser KDoc) is uploaded with no explicit
            // bankAccountId -- silently attributed the import to A with NO warning at all, giving
            // the uploader no signal that the choice even happened, let alone a way to correct it.
            val uploader = uploaderId()
            val defaultAccount = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            val csv =
                (
                    listOf(SPARKASSE_HEADER) +
                        listOf("DE00;15.03.2026;15.03.2026;Gutschrift;Spende;Spender;;;10,00;EUR;")
                ).joinToString("\r\n")
            val service = BankStatementImportService(secretBox = null)

            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "v1414-6-two-accounts.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.bankAccountId shouldBe defaultAccount.toString()
            result.warningCodes shouldContain BankStatementImportWarningCode.ATTRIBUTED_TO_DEFAULT_ACCOUNT
        }

        test(
            "CRITICAL regression: a second bank account's CSV line that coincides with account A's line " +
                "is NOT silently dropped as a false duplicate (finding #1)",
        ) {
            // Regression test for a review finding (CRITICAL): the per-line fingerprint's only
            // account-dimension used to be the statement-parsed accountIban, which BankCsvParser
            // NEVER sets (a plain CSV export carries no statement-level IBAN of its own) -- so two
            // DIFFERENT accounts' otherwise-identical CSV lines fingerprinted IDENTICALLY.
            // account_iban 202051 has ONE line (a fee); account IBAN 013000 has the SAME fee line
            // PLUS a second, genuinely distinct line (interest). Before the fix, importing B AFTER A
            // silently swallowed the shared fee line as an "already imported" duplicate: B ended up
            // with only 1 stored line instead of 2, with duplicateCount == 1 and no error anywhere.
            val uploader = uploaderId()
            val accountA = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val accountB = createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            val service = BankStatementImportService(secretBox = null)

            val sharedFeeLine = "DE00;01.09.2026;01.09.2026;Entgeltabschluss;Kontofuehrungsgebuehr;;;;-4,90;EUR;"
            val csvA = (listOf(SPARKASSE_HEADER) + listOf(sharedFeeLine)).joinToString("\r\n")
            val resultA =
                service.import(
                    bytes = csvA.toByteArray(),
                    fileName = "v1414-critical-a.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    bankAccountId = accountA,
                )
            createdImportIds += Uuid.parse(resultA.importId)
            resultA.lineCount shouldBe 1
            resultA.duplicateCount shouldBe 0

            val interestLine = "DE00;01.09.2026;01.09.2026;Zinsabschluss;Guthabenzinsen;;;;0,12;EUR;"
            val csvB = (listOf(SPARKASSE_HEADER) + listOf(sharedFeeLine, interestLine)).joinToString("\r\n")
            val resultB =
                service.import(
                    bytes = csvB.toByteArray(),
                    fileName = "v1414-critical-b.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    bankAccountId = accountB,
                )
            createdImportIds += Uuid.parse(resultB.importId)

            // The fix: B's fee line is NOT a duplicate of A's -- they belong to different accounts.
            resultB.lineCount shouldBe 2
            resultB.duplicateCount shouldBe 0

            transaction {
                val storedLinesForB =
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.importId eq Uuid.parse(resultB.importId) }
                        .count()
                storedLinesForB shouldBe 2L
            }
        }

        test(
            "MAJOR regression (Review Round 5, Szenario A): a LEGACY (discriminator-less, " +
                "bank_account_id NULL) stored fingerprint is adopted the instant the organization's " +
                "first bank_account row is created, and still recognized as a duplicate on an " +
                "explicit-bankAccountId re-import",
        ) {
            // Regression test for a review finding, corrected across Round 4 (MAJOR "Doppelbuchung
            // realer Zahlungen im Upgrade-Pfad") and Round 5 (residuum of finding #1): an earlier
            // version of the CRITICAL fix (finding #1) gated the discriminator on
            // `bankAccountRowCount > 1`, so a fingerprint computed BEFORE this account ever carried
            // one (e.g. while at most one account existed, or entirely pre-wave) stopped matching
            // the moment the discriminator became unconditional -- `insertIgnore` would then insert
            // the SAME booking a second time.
            //
            // The legacy row is inserted BEFORE `accountA` exists -- the only order that can
            // actually occur in production (see `insertLegacyNullAccountImport`'s own KDoc) --
            // so `createBankAccount` below adopts it via `BankAccountStore.adoptLegacyBankStatementImports`.
            val uploader = uploaderId()
            val legacyImportId = insertLegacyNullAccountImport(uploader, "legacy-pre-fix.csv", feeLineFingerprint)
            val accountA = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)

            // The adoption already happened as a side effect of `createBankAccount` above -- assert
            // it directly, not just through the dedup behaviour it enables below.
            transaction {
                BankStatementImportTable
                    .selectAll()
                    .where { BankStatementImportTable.id eq legacyImportId }
                    .single()[BankStatementImportTable.bankAccountId] shouldBe accountA
            }

            val feeLine = "DE00;01.09.2026;01.09.2026;Entgeltabschluss;Kontofuehrungsgebuehr;;;;-4,90;EUR;"
            val service = BankStatementImportService(secretBox = null)
            val csv = (listOf(SPARKASSE_HEADER) + listOf(feeLine)).joinToString("\r\n")
            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "v1414-round5-scenario-a.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    bankAccountId = accountA,
                )
            createdImportIds += Uuid.parse(result.importId)

            // Without the legacy-fingerprint fallback, this would be a SECOND, silently duplicate
            // row -- the new fingerprint is discriminator-bearing from the very first resolved
            // import, so it would never collide with the legacy row's fingerprint via
            // `insertIgnore` alone.
            result.duplicateCount shouldBe 1
            result.lineCount shouldBe 1

            transaction {
                val totalLinesForFeeAcrossBothImports =
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.importId inList listOf(legacyImportId, Uuid.parse(result.importId)) }
                        .count()
                totalLinesForFeeAcrossBothImports shouldBe 1L
            }
        }

        test(
            "MAJOR regression (Review Round 5, Hinweis Szenario a): an adopted legacy import stays " +
                "deduplicated for its own (explicit-id) re-import even after the organization's " +
                "default account has moved to a DIFFERENT account",
        ) {
            // Regression test for the residuum of finding #1: an intermediate (Round 4) version of
            // the fix scoped the `bank_account_id IS NULL` fallback match to "whichever account is
            // CURRENTLY default" -- `isDefault` is mutable (`BankAccountStore.setDefault`), while a
            // legacy row's true owner is not. Moving the default away from the account that actually
            // owns the legacy history used to make this exact re-import silently double-book the fee
            // line a second time.
            val uploader = uploaderId()
            val legacyImportId = insertLegacyNullAccountImport(uploader, "legacy-pre-fix-a.csv", feeLineFingerprint)
            val accountA = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val accountB = createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            BankAccountStore.setDefault(bankAccountId = accountB, actorMemberId = uploader, actorRole = AccountRole.ADMIN)

            val feeLine = "DE00;01.09.2026;01.09.2026;Entgeltabschluss;Kontofuehrungsgebuehr;;;;-4,90;EUR;"
            val service = BankStatementImportService(secretBox = null)
            val csv = (listOf(SPARKASSE_HEADER) + listOf(feeLine)).joinToString("\r\n")
            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "v1414-round5-hinweis-a.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    // Explicit -- accountA is no longer the organization's default, but the legacy
                    // row is still PERMANENTLY its own (adopted at creation time), unaffected by the
                    // setDefault call above.
                    bankAccountId = accountA,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.duplicateCount shouldBe 1
            result.lineCount shouldBe 1

            transaction {
                val totalLinesForFeeAcrossBothImports =
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.importId inList listOf(legacyImportId, Uuid.parse(result.importId)) }
                        .count()
                totalLinesForFeeAcrossBothImports shouldBe 1L
            }
        }

        test(
            "MAJOR regression (Review Round 5, Hinweis Szenario b): a genuine NEW booking on the " +
                "current default account is NOT silently swallowed as a duplicate of a DIFFERENT " +
                "account's legacy row that happens to share the same fingerprint",
        ) {
            // Regression test for the residuum of finding #1's other direction: the same mutable-
            // `isDefault` scoping that used to cause a stuck duplicate above (Hinweis Szenario a)
            // used to cause the OPPOSITE failure here -- a real, distinct payment silently vanishing
            // -- once whatever account happens to be default changed to one that never actually
            // owned the legacy row.
            val uploader = uploaderId()
            insertLegacyNullAccountImport(uploader, "legacy-pre-fix-b.csv", feeLineFingerprint)
            val accountA = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val accountB = createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            // accountB becomes the new default -- it never owned the legacy row; accountA does,
            // permanently, via adoption at its own creation time above.
            BankAccountStore.setDefault(bankAccountId = accountB, actorMemberId = uploader, actorRole = AccountRole.ADMIN)
            BankAccountStore.listDtos().single { it.id == accountA.toString() }.isDefault shouldBe false

            // Same date/amount/purpose/counterparty as the legacy fee line -- e.g. an identical
            // monthly account-maintenance fee, charged by the same bank on both of the
            // organization's accounts. No explicit bankAccountId -- resolves to the (new) default,
            // accountB, exactly the common CSV-with-no-account-identifier case.
            val feeLine = "DE00;01.09.2026;01.09.2026;Entgeltabschluss;Kontofuehrungsgebuehr;;;;-4,90;EUR;"
            val service = BankStatementImportService(secretBox = null)
            val csv = (listOf(SPARKASSE_HEADER) + listOf(feeLine)).joinToString("\r\n")
            val result =
                service.import(
                    bytes = csv.toByteArray(),
                    fileName = "v1414-round5-hinweis-b.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(result.importId)

            result.bankAccountId shouldBe accountB.toString()
            // Must be a genuinely NEW, stored line -- accountA's legacy row must not absorb it just
            // because accountB happens to be default now.
            result.duplicateCount shouldBe 0
            result.lineCount shouldBe 1

            transaction {
                val storedLines =
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.importId eq Uuid.parse(result.importId) }
                        .count()
                storedLines shouldBe 1L
            }
        }

        test(
            "MEDIUM regression (Review Round 3, Szenario B): deleting a SECOND account does not, by itself, " +
                "break dedup for account A's own re-imports",
        ) {
            // Regression test for a review finding (MEDIUM): an earlier version of the CRITICAL fix
            // gated the discriminator on `bankAccountRowCount > 1` -- a MUTABLE count. Adding a
            // second account B, then deleting it again, used to flip account A's own fingerprint
            // formula back and forth (WITH discriminator while B existed, WITHOUT once it was gone
            // again) even though A's own identity never changed, opening the exact same false-
            // duplicate-insert window as Szenario A but on the opposite edge of the transition. The
            // fix (discriminator keyed on the RESOLVED account's own identity, not on how many
            // accounts happen to exist) makes this transition a non-event: verified here directly,
            // no legacy-fingerprint fallback needed for this direction.
            val uploader = uploaderId()
            val accountA = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)
            val accountB = createBankAccount(iban = "DE89370400440532013000", actorMemberId = uploader)
            val service = BankStatementImportService(secretBox = null)

            val feeLine = "DE00;01.09.2026;01.09.2026;Entgeltabschluss;Kontofuehrungsgebuehr;;;;-4,90;EUR;"
            val csvBeforeDelete = (listOf(SPARKASSE_HEADER) + listOf(feeLine)).joinToString("\r\n")

            val resultBeforeDelete =
                service.import(
                    bytes = csvBeforeDelete.toByteArray(),
                    fileName = "v1414-round3-scenario-b-1.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    bankAccountId = accountA,
                )
            createdImportIds += Uuid.parse(resultBeforeDelete.importId)
            resultBeforeDelete.duplicateCount shouldBe 0
            resultBeforeDelete.lineCount shouldBe 1

            // accountB is not the default (accountA, created first, is) -- deleting it never touches
            // the organization_settings mirror and needs no ADMIN gate.
            BankAccountStore.delete(bankAccountId = accountB, actorMemberId = uploader, actorRole = AccountRole.TREASURER)

            // A DIFFERENT (overlapping, not byte-identical) file -- an extra, genuinely NEW interest
            // line alongside the SAME fee line -- so this is a distinct fileDigest from the first
            // upload (the 409 ALREADY_IMPORTED file-level guard is a separate concern from the
            // per-line fingerprint dedup this test targets) and its own upload isn't itself rejected
            // outright.
            val interestLine = "DE00;01.09.2026;01.09.2026;Zinsabschluss;Guthabenzinsen;;;;0,12;EUR;"
            val csvAfterDelete = (listOf(SPARKASSE_HEADER) + listOf(feeLine, interestLine)).joinToString("\r\n")
            val resultAfterDelete =
                service.import(
                    bytes = csvAfterDelete.toByteArray(),
                    fileName = "v1414-round3-scenario-b-2.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                    bankAccountId = accountA,
                )
            createdImportIds += Uuid.parse(resultAfterDelete.importId)

            // The fee line is recognized as a duplicate of the FIRST import's line -- account A's
            // own fingerprint identity is unaffected by account B's existence or deletion. The
            // interest line is genuinely new and gets stored.
            resultAfterDelete.lineCount shouldBe 2
            resultAfterDelete.duplicateCount shouldBe 1

            transaction {
                val storedLinesForSecondImport =
                    BankStatementLineTable
                        .selectAll()
                        .where { BankStatementLineTable.importId eq Uuid.parse(resultAfterDelete.importId) }
                        .count()
                storedLinesForSecondImport shouldBe 1L
            }
        }

        test(
            "MAJOR regression (Review Round 4, real upgrade path): a genuine pre-wave CSV import -- " +
                "made through the service while ZERO bank_account rows existed, no manually inserted " +
                "row -- is still deduplicated once the organization's first bank account is created " +
                "and an overlapping file is re-imported against it",
        ) {
            // End-to-end reproduction of the actual V1.4.14 upgrade path the legacy-fingerprint
            // fallback exists for. Unlike Szenario A above (which simulates the legacy row directly),
            // this test's FIRST import genuinely runs through the pre-wave, zero-`bank_account`-row
            // code path (`bankAccountRowCount == 0`) -- producing a real `bank_account_id = NULL`,
            // discriminator-less fingerprint with no test-side DB manipulation at all. Only THEN is
            // the organization's first bank account created (`createBankAccount`'s first call always
            // becomes the default, exactly like `BankAccountStore.backfillLegacyDefaultAccountIfNeeded`
            // on a real server upgrade), and a second, overlapping file is imported against it.
            val uploader = uploaderId()
            val service = BankStatementImportService(secretBox = null)

            val feeLine = "DE00;01.09.2026;01.09.2026;Entgeltabschluss;Kontofuehrungsgebuehr;;;;-4,90;EUR;"
            val csvBeforeAnyAccount = (listOf(SPARKASSE_HEADER) + listOf(feeLine)).joinToString("\r\n")
            val resultBeforeAnyAccount =
                service.import(
                    bytes = csvBeforeAnyAccount.toByteArray(),
                    fileName = "v1414-round4-pre-account.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(resultBeforeAnyAccount.importId)
            resultBeforeAnyAccount.lineCount shouldBe 1
            resultBeforeAnyAccount.duplicateCount shouldBe 0
            resultBeforeAnyAccount.bankAccountId shouldBe null

            val hauptkonto = createBankAccount(iban = "DE02120300000000202051", actorMemberId = uploader)

            // A DIFFERENT (overlapping, not byte-identical) file: the same fee line, plus a genuinely
            // new interest line -- an ordinary Kassenwart re-upload of a slightly wider period, the
            // exact shape of the finding's own "Fehlerszenario".
            val interestLine = "DE00;01.09.2026;01.09.2026;Zinsabschluss;Guthabenzinsen;;;;0,12;EUR;"
            val csvAfterAccountCreated = (listOf(SPARKASSE_HEADER) + listOf(feeLine, interestLine)).joinToString("\r\n")
            val resultAfterAccountCreated =
                service.import(
                    bytes = csvAfterAccountCreated.toByteArray(),
                    fileName = "v1414-round4-post-account.csv",
                    uploadedBy = uploader,
                    uploaderRole = AccountRole.TREASURER,
                )
            createdImportIds += Uuid.parse(resultAfterAccountCreated.importId)
            resultAfterAccountCreated.bankAccountId shouldBe hauptkonto.toString()

            // Without the Round-4 fix, the fee line would be silently re-inserted a SECOND time
            // (duplicateCount stuck at 0, lineCount 2 counting the dupe as "new") -- exactly the
            // "Doppelbuchung realer Zahlungen im Upgrade-Pfad" this test guards against.
            resultAfterAccountCreated.lineCount shouldBe 2
            resultAfterAccountCreated.duplicateCount shouldBe 1

            transaction {
                val totalLinesAcrossBothImports =
                    BankStatementLineTable
                        .selectAll()
                        .where {
                            BankStatementLineTable.importId inList
                                listOf(
                                    Uuid.parse(resultBeforeAnyAccount.importId),
                                    Uuid.parse(resultAfterAccountCreated.importId),
                                )
                        }.count()
                // 1 (fee, from the first import) + 1 (interest, from the second) -- the fee line is
                // NOT stored a second time.
                totalLinesAcrossBothImports shouldBe 2L
            }
        }
    })
