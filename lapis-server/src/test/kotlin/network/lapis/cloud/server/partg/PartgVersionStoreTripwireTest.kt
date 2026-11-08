package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.291 (R6) -- `PartgVersionStore` is the only reader and writer of the four version tables, and it has NO function that changes the content of a
 * kept version. Outside of it only the generated objects and the data subject contributor `PartgReportPersonalData` may name the tables; only the
 * writer, the view, the operations, the export routes, the service, the dashboard source and the pure consumers may use the store.
 */
class PartgVersionStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val tables = """(ReportVersionCounter|ReportVersion|ReportDeclaration|ReportDeadline)"""
        val table = Regex("""\bPartg${tables}Table\b""")
        val query =
            Regex("""\bPartg${tables}Table\s*\.\s*(selectAll|select|insert|update|upsert|batchInsert|deleteWhere|deleteAll|slice)\b""")
        val allowedNaming = setOf("PartgVersionStore.kt", "PartgReportPersonalData.kt")

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

        test("only the store, the writer, the view, the operations, the routes, the dashboard source and the format use the store") {
            files
                .filter { Regex("""\bPartgVersionStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(
                    setOf(
                        "PartgVersionStore.kt",
                        "PartgVersionWriter.kt",
                        "PartgVersionView.kt",
                        "PartgVersionOperations.kt",
                        "PartgReportExportRoutes.kt",
                        "PartyReportSignals.kt",
                    ),
                ).shouldBeEmpty()
        }

        test("the store has exactly one UPDATE on a version and it sets only the finalisation pair") {
            val raw = files.single { it.name == "PartgVersionStore.kt" }.readText()
            val start = raw.indexOf("PartgReportVersionTable.update(")
            val end = raw.indexOf("== 1", start)
            val block = raw.substring(start, end)
            Regex("""it\[(\w+)]\s*=""").findAll(block).map { it.groupValues[1] }.toList() shouldBe listOf("finalizedAt", "finalizedBy")
            raw.windowed(size = "PartgReportVersionTable.update(".length).count { it == "PartgReportVersionTable.update(" } shouldBe 1
        }

        test("a version is only removed by deleteVersions, and the counter table is never deleted from") {
            val code = SourceScan.blank(files.single { it.name == "PartgVersionStore.kt" }.readText())
            Regex("""PartgReportVersionCounterTable\s*\.\s*deleteWhere""").containsMatchIn(code) shouldBe false
            Regex("""PartgReportVersionTable\s*\.\s*deleteWhere""").findAll(code).count() shouldBe 1
        }
    })
