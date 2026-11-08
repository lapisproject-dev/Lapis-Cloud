package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.289 (R4a) -- the DEFAULT of the booking scope is refusal. `bookingScope(` is the ONE function that lets a plain member with a chapter-treasurer
 * grant into the books; it may be called only in `AccountingService` and only in the methods listed here. Every other method keeps its `requireRole`
 * and therefore still refuses a MEMBER. A new call fails the build until the list is changed on purpose (and the negative tests are extended).
 * `activeChapterTreasurerIds(` (the raw grant lookup) is likewise limited to the files that need it.
 */
class BookingScopeAllowlistScanTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun blanked(name: String): String = SourceScan.blank(files.single { it.name == name }.readText())

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("bookingScope( is called only in AccountingService.kt") {
            files
                .filter { it.name != "BookingScope.kt" }
                .filter { Regex("""\.bookingScope\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filterNot { it == "AccountingService.kt" }
                .shouldBeEmpty()
        }

        test("in AccountingService.kt exactly these methods open the books to a chapter treasurer") {
            val code = blanked("AccountingService.kt")
            val methods =
                Regex("""\.bookingScope\(""")
                    .findAll(code)
                    .map { call ->
                        Regex("""override suspend fun (\w+)\(""").findAll(code.substring(0, call.range.first)).last().groupValues[1]
                    }.toSet()
            methods shouldBe
                setOf(
                    // master data without amounts
                    "listLedgerAccounts",
                    "listCostCenters",
                    "listBookingUnits",
                    // booking
                    "saveDraftEntry",
                    "postJournalEntry",
                    "postDraftEntry",
                    // reading the books of the granted chapters
                    "getJournalEntry",
                    "listJournal",
                    "getGeneralLedgerAccount",
                    "getKassenbuch",
                )
        }

        test("every call of bookingScope( names the roles of the method as its argument (no default)") {
            Regex("""\.bookingScope\(([^)]*)\)""")
                .findAll(blanked("AccountingService.kt"))
                .map { it.groupValues[1].trim() }
                .all { it == "TREASURY_ROLES" || it == "ACCOUNTING_READ_ROLES" } shouldBe true
        }

        test("the raw grant lookup activeChapterTreasurerIds( is used only by the scope, the session info and the party report gate") {
            files
                .filter { Regex("""\bactiveChapterTreasurerIds\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .minus(setOf("BookingScope.kt", "AuthService.kt", "PartgReportAccess.kt"))
                .shouldBeEmpty()
        }

        test("journalCondition( is used only by the accounting service and the journal CSV of the party report (V1.9.292, R4b)") {
            files
                .filter { it.name != "BookingScope.kt" }
                .filter { Regex("""\.journalCondition\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet() shouldBe setOf("AccountingService.kt", "PartgJournalCsvExport.kt")
        }

        test("BookingScope.Chapters( is constructed only by the scope itself and the journal CSV (V1.9.292, R4b)") {
            files
                .filter { Regex("""\bBookingScope\.Chapters\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet() shouldBe setOf("BookingScope.kt", "PartgJournalCsvExport.kt")
        }

        test("the journal CSV derives its scope from the actor's grants, not from a second lookup (V1.9.292, R4b)") {
            val code = blanked("PartgJournalCsvExport.kt")
            Regex("""\bactiveChapterTreasurerIds\(""").containsMatchIn(code) shouldBe false
            Regex("""\bbookingScope\(""").containsMatchIn(code) shouldBe false
            code.contains("actor.chapterIds") shouldBe true
        }

        test("the memberVisibility allowlist is untouched: BookingScope does not call memberVisibility") {
            Regex("""\bmemberVisibility\(""").containsMatchIn(blanked("BookingScope.kt")) shouldBe false
        }
    })
