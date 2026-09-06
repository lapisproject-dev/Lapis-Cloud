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
import network.lapis.cloud.shared.domain.BankStatementLineStatus
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

        beforeSpec { DatabaseConfig.connect() }

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

            transaction {
                val importRow =
                    BankStatementImportTable
                        .selectAll()
                        .where { BankStatementImportTable.id eq Uuid.parse(result.importId) }
                        .single()
                importRow[BankStatementImportTable.accountIban] shouldBe overlongIban.take(34)
            }
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
    })
