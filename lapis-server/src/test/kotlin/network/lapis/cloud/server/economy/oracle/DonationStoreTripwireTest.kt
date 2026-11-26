package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.328 -- structural guards of the donation entity: one writer, insert only, a fixed set of readers, and the reason of a manual reference date
 * never reaches a ledger note, a log line or an audit text.
 */
class DonationStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun codeOf(name: String) = SourceScan.blank(files.single { it.name == name }.readText())

        fun filesNaming(
            regex: Regex,
            allowed: Set<String>,
        ) = files
            .filter { "/db/generated/" !in it.path }
            .filter { regex.containsMatchIn(SourceScan.blank(it.readText())) }
            .map { it.name }
            .filterNot { it in allowed }

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("only the donation store (and the member contributor, which exports and counts) names the donation table") {
            filesNaming(Regex("""\bDonationTable\b"""), setOf("DonationStore.kt", "PriceOraclePersonalData.kt")).shouldBeEmpty()
        }

        test("only the donation store inserts a donation row") {
            filesNaming(Regex("""\bDonationTable\s*\.\s*(insert|batchInsert|upsert)\b"""), setOf("DonationStore.kt")).shouldBeEmpty()
        }

        test("nothing updates or deletes a donation row; the store has no update and no delete") {
            filesNaming(Regex("""\bDonationTable\s*\.\s*(update|upsert|deleteWhere|deleteAll|batchUpdate)\b"""), emptySet()).shouldBeEmpty()
            val code = codeOf("DonationStore.kt")
            Regex("""\.\s*(update|upsert|deleteWhere|deleteAll|batchUpdate)\b""").containsMatchIn(code) shouldBe false
            Regex("""fun\s+(update|delete|remove|edit|change)""", RegexOption.IGNORE_CASE).containsMatchIn(code) shouldBe false
        }

        test("only the price oracle service, the payment link and the two payment entry points use the donation store") {
            filesNaming(
                Regex("""\bDonationStore\b"""),
                setOf(
                    "DonationStore.kt",
                    "PriceOracleService.kt",
                    "DonationPaymentLink.kt",
                    "BankStatementStore.kt",
                    "PspWebhookIngestion.kt",
                ),
            ).shouldBeEmpty()
        }

        test(
            "V1.9.329: every place that inserts a payment_transaction is known, and each one that can write a donation creates its donation",
        ) {
            val inserting =
                files
                    .filter { "/db/generated/" !in it.path }
                    .filter { Regex("""\bPaymentTransactionTable\s*\.\s*insert\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            // A new file that inserts a payment must be classified here (and, if it can write intent DONATION, call DonationStore.ensureForPayment).
            inserting shouldBe setOf("BankStatementStore.kt", "BankStatementImportService.kt", "PspWebhookIngestion.kt")
            listOf("BankStatementStore.kt", "PspWebhookIngestion.kt").forEach {
                Regex("""\bDonationStore\s*\.\s*ensureForPayment\b""").containsMatchIn(codeOf(it)) shouldBe true
            }
            // The statement import writes contribution payments only.
            codeOf("BankStatementImportService.kt").contains("PaymentIntent.DONATION") shouldBe false
        }

        test("the ledger note and the log lines of the reference-date conversion never carry the reason text") {
            val service = codeOf("PriceOracleService.kt")
            val note = service.substringAfterLast("it[note] =", "").substringBefore("it[createdBy]")
            note.isNotBlank() shouldBe true
            note.contains("overrideReason", ignoreCase = true) shouldBe false
            note.contains("referenceDateOverrideReason") shouldBe false
            Regex("""logger\.[^\n]*[Rr]eason""").containsMatchIn(service) shouldBe false
        }

        test("the IBAN scan of a justification lives in one place") {
            filesNaming(Regex("""\bIBAN_CANDIDATE\b"""), setOf("IbanText.kt")).shouldBeEmpty()
        }
    })
