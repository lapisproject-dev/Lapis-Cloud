package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.325 -- the refund of a travel expense report in LTR is credited in ONE file and changes nothing else:
 *
 *  1. `TRAVEL_REFUND` / `TRAVEL_REFUND_REVERSE` and `LtrLedgerReferenceType.TRAVEL_EXPENSE` are written only in `TravelExpenseLtrPayout.kt`
 *     (the generated objects and the shared enums that define them excepted);
 *  2. `travel_expense_ltr_payout` is read and written only there, the data-subject contributor and the report mapper of the service excepted, and the
 *     only UPDATE of it is the single reversal, guarded by `reversed_at IS NULL`;
 *  3. the credit is never a `MINT`, and nothing in the file mentions a merit, a weighting or karma;
 *  4. the file takes no network path (no HTTP client, no oracle orchestrator).
 */
class TravelExpenseLtrLedgerTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles().filter { "/db/generated/" !in it.path }

        fun codeOf(name: String) = SourceScan.blank(files.single { it.name == name }.readText())

        fun filesNaming(
            regex: Regex,
            allowed: Set<String>,
        ) = files
            .filter { regex.containsMatchIn(SourceScan.blank(it.readText())) }
            .map { it.name }
            .filterNot { it in allowed }

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("the refund ledger types are named only by the payout file") {
            filesNaming(
                Regex("""\bTRAVEL_REFUND(_REVERSE)?\b|LtrLedgerReferenceType\.TRAVEL_EXPENSE\b"""),
                setOf("TravelExpenseLtrPayout.kt", "PublicTransparencyReader.kt"),
            ) shouldBe emptyList()
        }

        test("the payout table is used only by the payout file, the service mapper path and the data-subject contributor") {
            filesNaming(
                Regex("""\bTravelExpenseLtrPayoutTable\b"""),
                setOf("TravelExpenseLtrPayout.kt", "TravelExpensePersonalData.kt"),
            ) shouldBe emptyList()
        }

        test("the only update of the payout table is the guarded reversal") {
            val code = codeOf("TravelExpenseLtrPayout.kt")
            Regex("""TravelExpenseLtrPayoutTable\s*\.\s*(upsert|deleteWhere|deleteAll|batchUpdate)\b""").containsMatchIn(code) shouldBe
                false
            Regex("""TravelExpenseLtrPayoutTable\s*\.\s*update\(""").findAll(code).count() shouldBe 1
            code.contains("reversedAt.isNull()") shouldBe true
        }

        test("the credit is no MINT, carries no merit, and uses no network") {
            val code = codeOf("TravelExpenseLtrPayout.kt")
            code.contains("LtrLedgerEntryType.MINT") shouldBe false
            Regex("""karma|merit|weight""", RegexOption.IGNORE_CASE).containsMatchIn(code) shouldBe false
            Regex("""HttpClient|PriceOracleOrchestrator|URL\(""").containsMatchIn(code) shouldBe false
        }

        test("the facade is the only way the travel refund reaches the rate: no resolver, no store, no snapshot day read") {
            val code = codeOf("TravelExpenseLtrPayout.kt")
            Regex("""PriceOracleReferenceRateResolver|PriceOracleReferenceRateStore|loadDay\(""").containsMatchIn(code) shouldBe false
        }
    })
