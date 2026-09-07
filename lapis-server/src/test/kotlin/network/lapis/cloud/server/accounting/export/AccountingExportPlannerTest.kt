package network.lapis.cloud.server.accounting.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountingExportBlockerKind
import network.lapis.cloud.shared.domain.AccountingExportDirection
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure tests of [AccountingExportPlanner] -- no DB access anywhere in this file, same "pure logic
 * extracted to a sibling file" idiom as `DatevBuchungsstapelWriterTest`.
 */
class AccountingExportPlannerTest :
    FunSpec({
        val bankAccountId = Uuid.random()
        val incomeAccountId = Uuid.random()
        val expenseAccountId = Uuid.random()
        val incomeAccount2Id = Uuid.random()
        val expenseAccount2Id = Uuid.random()
        val equityAccountId = Uuid.random()

        fun posting(
            side: PostingSide,
            amount: String,
            ledgerAccountId: Uuid,
            accountNumber: String,
            accountType: LedgerAccountType,
        ) = JournalExportPosting(
            side = side,
            amount = BigDecimal(amount),
            accountNumber = accountNumber,
            ledgerAccountId = ledgerAccountId,
            accountType = accountType,
        )

        fun entry(
            id: Uuid = Uuid.random(),
            date: LocalDate = LocalDate(2026, 1, 15),
            description: String = "Testbuchung",
            voucherReference: String? = "REF-1",
            postings: List<JournalExportPosting>,
        ) = JournalExportEntry(
            id = id,
            entryDate = date,
            description = description,
            voucherReference = voucherReference,
            postings = postings,
        )

        fun request(entries: List<JournalExportEntry>) =
            JournalExportRequest(
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 1, 31),
                beraterNummer = null,
                mandantNummer = null,
                organizationName = "Verein Beispiel e.V.",
                exportedBy = "Max Mustermann",
                generatedAt = LocalDateTime(2026, 2, 1, 10, 0),
                entries = entries,
            )

        val incomeMapping = MappedCategory(externalCategoryId = "cat-income", externalCategoryName = "Einnahmen")
        val expenseMapping = MappedCategory(externalCategoryId = "cat-expense", externalCategoryName = "Ausgaben")
        val fullMapping = mapOf(incomeAccountId to incomeMapping, expenseAccountId to expenseMapping)

        test("1:1 INCOME/CREDIT resolves to salesinvoice with the mapped category") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "119.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "119.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.shouldBeEmpty()
            plan.vouchers shouldHaveSize 1
            val v = plan.vouchers.single()
            v.direction shouldBe AccountingExportDirection.INCOME
            v.grossAmount shouldBe BigDecimal("119.00")
            v.externalCategoryId shouldBe "cat-income"
            v.ledgerAccountId shouldBe incomeAccountId
        }

        test("1:1 EXPENSE/DEBIT resolves to purchaseinvoice with the mapped category") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "50.00", expenseAccountId, "6000", LedgerAccountType.EXPENSE),
                            posting(PostingSide.CREDIT, "50.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.shouldBeEmpty()
            plan.vouchers shouldHaveSize 1
            val v = plan.vouchers.single()
            v.direction shouldBe AccountingExportDirection.EXPENSE
            v.grossAmount shouldBe BigDecimal("50.00")
            v.externalCategoryId shouldBe "cat-expense"
        }

        test(
            "n:1 (multiple debit accounts, one credit INCOME account) becomes exactly ONE voucher with the summed amount -- deliberately unlike DATEV's n rows",
        ) {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "70.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.DEBIT, "30.00", Uuid.random(), "1210", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "100.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.shouldBeEmpty()
            plan.vouchers shouldHaveSize 1
            plan.vouchers.single().grossAmount shouldBe BigDecimal("100.00")
        }

        test("1:n (one debit EXPENSE account, multiple credit accounts) becomes exactly ONE voucher with the summed amount") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "100.00", expenseAccountId, "6000", LedgerAccountType.EXPENSE),
                            posting(PostingSide.CREDIT, "70.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "30.00", Uuid.random(), "1210", LedgerAccountType.ASSET),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.shouldBeEmpty()
            plan.vouchers shouldHaveSize 1
            plan.vouchers.single().grossAmount shouldBe BigDecimal("100.00")
        }

        test("a real n:m entry (more than one account on BOTH sides) is rejected as UNMAPPABLE_MANY_TO_MANY_ENTRY, no partial voucher") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "50.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.DEBIT, "50.00", Uuid.random(), "1210", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "50.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                            posting(PostingSide.CREDIT, "50.00", incomeAccount2Id, "4010", LedgerAccountType.INCOME),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.map { it.kind } shouldBe listOf(AccountingExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY)
            plan.vouchers.shouldBeEmpty()
        }

        test("INCOME on CREDIT and EXPENSE on DEBIT in the same entry is UNDETERMINABLE_VOUCHER_TYPE") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "10.00", expenseAccountId, "6000", LedgerAccountType.EXPENSE),
                            posting(PostingSide.CREDIT, "10.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.map { it.kind } shouldBe listOf(AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE)
        }

        test("a pure Umbuchung between two balance-sheet accounts is UNDETERMINABLE_VOUCHER_TYPE") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "10.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "10.00", equityAccountId, "2000", LedgerAccountType.EQUITY),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.map { it.kind } shouldBe listOf(AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE)
        }

        test(
            "an account with no category mapping produces UNMAPPED_ACCOUNT and exportable == false, but the voucher is still listed with a null category",
        ) {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "10.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "10.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = emptyMap(),
                )
            plan.exportable shouldBe false
            plan.blockers.map { it.kind } shouldBe listOf(AccountingExportBlockerKind.UNMAPPED_ACCOUNT)
            plan.unmappedAccounts shouldHaveSize 1
            plan.unmappedAccounts.single().ledgerAccountId shouldBe incomeAccountId
            plan.vouchers shouldHaveSize 1
            plan.vouchers.single().externalCategoryId shouldBe null
        }

        test("all blockers across multiple entries are collected, never short-circuited on the first one") {
            val unmappable =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "10.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.DEBIT, "10.00", Uuid.random(), "1210", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "10.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                            posting(PostingSide.CREDIT, "10.00", incomeAccount2Id, "4010", LedgerAccountType.INCOME),
                        ),
                )
            val undeterminable =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "5.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "5.00", equityAccountId, "2000", LedgerAccountType.EQUITY),
                        ),
                )
            val unmapped =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "1.00", expenseAccount2Id, "6010", LedgerAccountType.EXPENSE),
                            posting(PostingSide.CREDIT, "1.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(unmappable, undeterminable, unmapped)),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.map { it.kind }.toSet() shouldBe
                setOf(
                    AccountingExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY,
                    AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE,
                    AccountingExportBlockerKind.UNMAPPED_ACCOUNT,
                )
        }

        test("an already-exported entry is included with alreadyExported == true, still counted in toSendCount == 0 for it") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "10.00", bankAccountId, "1200", LedgerAccountType.ASSET),
                            posting(PostingSide.CREDIT, "10.00", incomeAccountId, "4000", LedgerAccountType.INCOME),
                        ),
                )
            val plan =
                AccountingExportPlanner.plan(
                    request = request(listOf(e)),
                    alreadyExportedJournalEntryIds = setOf(e.id),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.exportable shouldBe true
            plan.alreadyExportedCount shouldBe 1
            plan.toSendCount shouldBe 0
            plan.vouchers.single().alreadyExported shouldBe true
        }

        test("voucherNumber is deterministic (same input twice yields the identical reference), 23 characters, only [A-Za-z0-9-]") {
            val date = LocalDate(2026, 1, 31)
            val id = Uuid.random()
            val first = AccountingExportPlanner.voucherNumber(entryDate = date, journalEntryId = id)
            val second = AccountingExportPlanner.voucherNumber(entryDate = date, journalEntryId = id)
            first shouldBe second
            first.length shouldBe 23
            // toHexString() yields lowercase hex digits -- the reference is not all-uppercase.
            first.all { it.isLetterOrDigit() || it == '-' } shouldBe true
            first shouldBe "LAPIS-20260131-${id.toHexString().take(8)}"
        }

        test("voucherNumber always uses ASCII digits (Locale.ROOT), regardless of the JVM default locale") {
            // Regression guard: this string becomes both accounting_export_item.voucher_number
            // AND (via SevDeskVoucherMapper) the sevDesk voucher's `description` field -- a JVM
            // default locale with non-ASCII decimal digits (e.g. Arabic) would silently corrupt
            // the reference a treasurer is told to search for after an UNKNOWN item.
            val previousDefault = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
                val date = LocalDate(2026, 1, 31)
                val id = Uuid.random()
                val voucherNumber = AccountingExportPlanner.voucherNumber(entryDate = date, journalEntryId = id)
                voucherNumber shouldBe "LAPIS-20260131-${id.toHexString().take(8)}"
                voucherNumber.all { it.code < 128 } shouldBe true
            } finally {
                java.util.Locale.setDefault(previousDefault)
            }
        }

        test("an empty period produces EMPTY_PERIOD") {
            val plan =
                AccountingExportPlanner.plan(
                    request = request(emptyList()),
                    alreadyExportedJournalEntryIds = emptySet(),
                    categoryByLedgerAccount = fullMapping,
                )
            plan.blockers.map { it.kind } shouldBe listOf(AccountingExportBlockerKind.EMPTY_PERIOD)
            plan.exportable shouldBe false
        }
    })
