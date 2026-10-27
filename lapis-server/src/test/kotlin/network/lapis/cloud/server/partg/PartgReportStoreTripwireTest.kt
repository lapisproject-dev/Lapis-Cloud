package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.225 -- `PartgReportStore` is the only reader and writer of the three report tables. Outside of it only the generated objects and the data
 * subject contributor `PartgReportPersonalData` (who changed what) may name them.
 */
class PartgReportStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val table = Regex("""\bPartgReport(Position|Sheet|Value)Table\b""")
        val query =
            Regex(
                """\bPartgReport(Position|Sheet|Value)Table\s*\.\s*(selectAll|select|insert|update|upsert|batchInsert|deleteWhere|deleteAll|slice)\b""",
            )

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("the three tables are named only by the store, the contributor and the generated objects") {
            files
                .filter { "/db/generated/" !in it.path }
                .filter { table.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filterNot { it in setOf("PartgReportStore.kt", "PartgReportPersonalData.kt") }
                .shouldBeEmpty()
        }

        test("every query on them lives in the store or the contributor") {
            files
                .filter { query.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filterNot { it in setOf("PartgReportStore.kt", "PartgReportPersonalData.kt") }
                .shouldBeEmpty()
        }

        test("only the store, the writer, the service, the routes and the chapter service use the store") {
            files
                .filter { Regex("""\bPartgReportStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(
                    setOf(
                        "PartgReportStore.kt",
                        "PartgSheetWriter.kt",
                        "PartyReportService.kt",
                        "PartgReportRoutes.kt",
                        "RegionalChapterService.kt",
                    ),
                ).shouldBeEmpty()
        }
    })
