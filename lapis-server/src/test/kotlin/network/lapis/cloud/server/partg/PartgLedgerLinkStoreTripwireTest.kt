package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.289 (R4a) -- `PartgLedgerLinkStore` is the only reader and writer of `partg_account_mapping` and `partg_report_unit_mode` (the data subject
 * contributor `PartgReportPersonalData` is the one documented exception), and `PartgLedgerReader` is the only code of the party report that reads the
 * booking tables. The package `partg/report` touches no table at all.
 */
class PartgLedgerLinkStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val linkTables = Regex("""\b(PartgAccountMappingTable|PartgReportUnitModeTable)\b""")
        val bookingTables = Regex("""\b(JournalEntryTable|PostingTable)\b""")

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("the two link tables are named only by the store, the contributor and the generated objects") {
            files
                .filter { "/db/generated/" !in it.path }
                .filter { linkTables.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(setOf("PartgLedgerLinkStore.kt", "PartgReportPersonalData.kt"))
                .shouldBeEmpty()
        }

        test("the store is used only by the service, the writer, the proposal glue and the chapter service") {
            files
                .filter { Regex("""\bPartgLedgerLinkStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(
                    setOf(
                        "PartgLedgerLinkStore.kt",
                        "PartyReportService.kt",
                        "PartgSheetWriter.kt",
                        "PartgLedgerProposals.kt",
                        "RegionalChapterService.kt",
                        // V1.9.293 (R5b): the writer re-reads the mode of the federal unit under the lock of the annex
                        "PartgAnnexWriter.kt",
                    ),
                ).shouldBeEmpty()
        }

        test("the booking tables are read in the party report only by PartgLedgerReader; partg/report touches no table") {
            files
                .filter { "/partg/" in it.path.replace('\\', '/') }
                .filter { bookingTables.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus("PartgLedgerReader.kt")
                .shouldBeEmpty()
            files
                .filter { "/partg/report/" in it.path.replace('\\', '/') }
                .filter { Regex("""\b\w+Table\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("the reader is used only by the service, the proposal glue and the writer's glue") {
            files
                .filter { Regex("""\bPartgLedgerReader\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(setOf("PartgLedgerReader.kt", "PartyReportService.kt", "PartgLedgerProposals.kt"))
                .shouldBeEmpty()
        }
    })
