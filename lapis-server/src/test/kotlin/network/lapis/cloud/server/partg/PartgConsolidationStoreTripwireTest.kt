package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.290 (R5) -- `PartgConsolidationStore` is the only reader and writer of the four consolidation tables. Outside of it only the generated
 * objects and the data subject contributor `PartgReportPersonalData` may name them; only the writer, the snapshot reader, the annex writer (for
 * `detachDonor` under its own lock), the service, the chapter service and the pure consumers of its value classes may use the store.
 */
class PartgConsolidationStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val tables = """(ConsolidationSheet|IntraTransfer|DonorCombination|DonorCombinationRow)"""
        val table = Regex("""\bPartg${tables}Table\b""")
        val query =
            Regex("""\bPartg${tables}Table\s*\.\s*(selectAll|select|insert|update|upsert|batchInsert|deleteWhere|deleteAll|slice)\b""")
        val allowedNaming = setOf("PartgConsolidationStore.kt", "PartgReportPersonalData.kt")

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("the four tables are named only by the store, the contributor and the generated objects") {
            files
                .filter { "/db/generated/" !in it.path }
                .filter { table.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filterNot { it in allowedNaming }
                .shouldBeEmpty()
        }

        test("every query on them lives in the store or the contributor") {
            files
                .filter { query.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filterNot { it in allowedNaming }
                .shouldBeEmpty()
        }

        test("only the store, the writer, the annex writer, the snapshot reader, the service and the pure consumers use the store") {
            files
                .filter { Regex("""\bPartgConsolidationStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(
                    setOf(
                        "PartgConsolidationStore.kt",
                        "PartgConsolidationWriter.kt",
                        "PartgAnnexWriter.kt",
                        "PartgReportSnapshotReader.kt",
                        "PartyReportService.kt",
                        "RegionalChapterService.kt",
                        // pure consumers of the store's value classes (no query)
                        "PartgConsolidation.kt",
                        "PartgDonorConsolidation.kt",
                        "PartgConsolidationView.kt",
                    ),
                ).shouldBeEmpty()
        }

        test("the annex writer calls the store only to detach a donor") {
            val code = SourceScan.blank(files.single { it.name == "PartgAnnexWriter.kt" }.readText())
            Regex("""PartgConsolidationStore\s*\.\s*(\w+)""").findAll(code).map { it.groupValues[1] }.toSet() shouldBe setOf("detachDonor")
        }
    })
