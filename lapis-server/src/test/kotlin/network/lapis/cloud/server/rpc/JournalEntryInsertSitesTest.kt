package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.289 (R4a) -- every place that inserts a `journal_entry` is pinned. Only `AccountingService` knows chapters (it sets `regional_chapter_id`);
 * the automatic bridges (contributions, donations, event fees, open items, travel expenses, volunteer allowances) book on the FEDERAL level (the column
 * stays NULL) until a later wave distributes them. A new insert site must be added here ON PURPOSE, deciding which level it books on.
 */
class JournalEntryInsertSitesTest :
    FunSpec({
        test("the insert sites of journal_entry are exactly the known ones") {
            val inserts =
                SourceScan
                    .mainFiles()
                    .map { file ->
                        file.name to
                            Regex("""\bJournalEntryTable\s*\.\s*insert\b""").findAll(SourceScan.blank(file.readText())).count()
                    }.filter { it.second > 0 }
                    .toMap()
            inserts shouldBe
                mapOf(
                    "AccountingService.kt" to 1,
                    "ContributionPostingBridge.kt" to 1,
                    "DonationPostingBridge.kt" to 1,
                    "EventFeePostingBridge.kt" to 1,
                    "OpenItemPostingBridge.kt" to 2,
                    "TravelExpensePostingBridge.kt" to 1,
                    "VolunteerAllowancePostingBridge.kt" to 1,
                )
        }

        test("only AccountingService sets regional_chapter_id on an insert (the bridges leave it NULL = federal)") {
            SourceScan
                .mainFiles()
                .filter { it.name.endsWith("PostingBridge.kt") }
                .none { Regex("""JournalEntryTable\.regionalChapterId""").containsMatchIn(SourceScan.blank(it.readText())) } shouldBe true
        }
    })
