package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.324 -- structural guards of the reference-date rate: one resolver, one writer per table, no way to change a confirmed rate.
 */
class ReferenceDateTripwireTest :
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

        test("only the reference rate store (and the member contributor) names or queries price_oracle_reference_rate") {
            filesNaming(
                Regex("""\bPriceOracleReferenceRateTable\b"""),
                setOf("PriceOracleReferenceRateStore.kt", "PriceOraclePersonalData.kt"),
            ).shouldBeEmpty()
        }

        test("the reference rate store has no update and no delete") {
            val code = codeOf("PriceOracleReferenceRateStore.kt")
            Regex("""\.\s*(update|upsert|deleteWhere|deleteAll|batchUpdate)\b""").containsMatchIn(code) shouldBe false
            Regex("""fun\s+(update|delete|remove|edit|change)""", RegexOption.IGNORE_CASE).containsMatchIn(code) shouldBe false
        }

        test("only the snapshot store names the raw source table") {
            filesNaming(Regex("""\bPriceOracleSnapshotSourceTable\b"""), setOf("PriceOracleSnapshotStore.kt")).shouldBeEmpty()
        }

        test("the snapshot store only inserts raw source rows, never updates or deletes them") {
            val code = codeOf("PriceOracleSnapshotStore.kt")
            Regex("""PriceOracleSnapshotSourceTable\s*\.\s*(update|deleteWhere|deleteAll|upsert)\b""").containsMatchIn(code) shouldBe false
        }

        test("only the resolver reads a day of snapshots, and only the service, the facade and the resolver use the resolver") {
            filesNaming(
                Regex("""\bloadDay\s*\("""),
                setOf("PriceOracleSnapshotStore.kt", "PriceOracleReferenceRateResolver.kt"),
            ).shouldBeEmpty()
            filesNaming(
                Regex("""\bPriceOracleReferenceRateResolver\b"""),
                // V1.9.325: the facade is the second legitimate caller (travel expense refund in LTR shares the logic through it).
                setOf("PriceOracleReferenceRateResolver.kt", "PriceOracleService.kt", "ReferenceRateConversion.kt"),
            ).shouldBeEmpty()
        }

        test("only the calendar and the resolver use the LBMA calendar") {
            filesNaming(
                Regex("""\bLbmaGoldCalendar\b"""),
                setOf("LbmaGoldCalendar.kt", "PriceOracleReferenceRateResolver.kt"),
            ).shouldBeEmpty()
        }

        test("the snapshot writer never writes the MANUAL basis: a snapshot is always an oracle status") {
            val code = codeOf("PriceOracleSnapshotStore.kt")
            code.contains("DEFERRED") shouldBe false
        }

        test("no new log line carries a rate, an amount or the evidence text") {
            val service = codeOf("PriceOracleService.kt")
            val referenceDateCode =
                service
                    .substringAfter(
                        "override suspend fun previewReferenceRate",
                    ).substringBefore("private fun computeLtrMinted")
            Regex("""logger\.""").containsMatchIn(referenceDateCode) shouldBe false
        }
    })
