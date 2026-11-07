package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.269 (R3) -- `PartgAnnexStore` is the only reader and writer of the nine separate-statement tables (R3b: sponsor, sponsoring entry, youth organisation; R3c: enterprise, all in `PartgStatementStore`). Outside of it only the generated
 * objects and the data subject contributor `PartgReportPersonalData` may name them; only the writer, the snapshot reader, the service and the
 * chapter service may use the store.
 */
class PartgAnnexStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val table = Regex("""\bPartgReport(Annex|LargeDonor|NotesSheet|Note|Holding|Sponsor|SponsoringEntry|YouthOrg|Enterprise)Table\b""")
        val query =
            Regex(
                """\bPartgReport(Annex|LargeDonor|NotesSheet|Note|Holding|Sponsor|SponsoringEntry|YouthOrg|Enterprise)Table\s*\.\s*(selectAll|select|insert|update|upsert|batchInsert|deleteWhere|deleteAll|slice)\b""",
            )
        val allowedNaming = setOf("PartgAnnexStore.kt", "PartgStatementStore.kt", "PartgReportPersonalData.kt")

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("the nine tables are named only by the store, the contributor and the generated objects") {
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

        test("only the store, the writer, the snapshot reader, the service and the chapter service use the store") {
            files
                .filter { Regex("""\bPartgAnnexStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(
                    setOf(
                        "PartgAnnexStore.kt",
                        "PartgAnnexWriter.kt",
                        "PartgReportSnapshotReader.kt",
                        "PartyReportService.kt",
                        "RegionalChapterService.kt",
                        // pure consumers of the store's value classes (no query): the assembler and the plausibility check
                        "PartgReportAssembler.kt",
                        "PartgPlausibility.kt",
                    ),
                ).shouldBeEmpty()
        }

        test(
            "only the statement store, the annex store, the writer, the service, the assembler and the plausibility check use PartgStatementStore",
        ) {
            files
                .filter { Regex("""\bPartgStatementStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(
                    setOf(
                        "PartgStatementStore.kt",
                        "PartgAnnexStore.kt",
                        "PartgAnnexWriter.kt",
                        "PartyReportService.kt",
                        "PartgReportAssembler.kt",
                        "PartgPlausibility.kt",
                    ),
                ).shouldBeEmpty()
        }
    })
