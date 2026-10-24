package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.ITenderService
import java.io.File

private val sharedMain: File =
    File("../lapis-shared/src/commonMain/kotlin").let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin") }
private val clientMain: File =
    File("../lapis-client/src/jsMain/kotlin").let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

/**
 * V1.9.201 -- keeps the tender foundation as narrow as it was designed:
 *
 *  1. the RPC surface is EXACTLY the known sixteen methods (a new method fails the build until it is listed and classified here);
 *  2. `MemberStatusSets.TENDER_PARTICIPANT` (ACTIVE, FRIEND, GUEST) is used only by the tender files and where it is defined;
 *  3. the four tender tables are referenced only by the tender package, the service, the data-subject contributor and the generated objects; every
 *     QUERY on them (select, insert, update, delete) lives in `TenderStore` (and, for export/erasure, `TenderPersonalData`);
 *  4. the tender files never touch the LTR ledger or a balance provider (nothing is booked in wave 1) and never name `TenderCurrency.EUR` except to
 *     refuse it;
 *  5. no log line of the service carries a price, a text, a title or a name.
 */
class TenderAccessTripwireTest :
    FunSpec({
        val allMethods =
            setOf(
                "createTender",
                "updateTenderDraft",
                "publishTender",
                "cancelTender",
                "getTender",
                "listTenders",
                "submitBid",
                "withdrawBid",
                "listMyBids",
                "registerAwardMotion",
                "awardTender",
                "recordAcceptance",
                "getTenderDefaults",
                "listTenderMotionOptions",
                "listAwardMotionOptions",
                "previewTenderDescription",
            )
        val serverFiles = SourceScan.mainFiles()
        val tenderFiles =
            serverFiles.filter { "/server/tender/" in it.path || it.name == "TenderService.kt" || it.name == "TenderPersonalData.kt" }

        test("the RPC surface is exactly the known sixteen methods") {
            ITenderService::class.java.declaredMethods
                .filter { !it.isSynthetic && '$' !in it.name }
                .map { it.name }
                .toSet() shouldBe allMethods
        }

        test("the scan sees the code base and the tender files") {
            serverFiles.size shouldBeGreaterThan 500
            tenderFiles.map { it.name }.toSet() shouldBe
                setOf(
                    "TenderService.kt",
                    "TenderPersonalData.kt",
                    "TenderStore.kt",
                    "TenderDtos.kt",
                    "TenderMotionBinding.kt",
                    "TenderConflicts.kt",
                    "TenderVisibilityPolicy.kt",
                    "TenderAwardRules.kt",
                    "TenderOptions.kt",
                )
        }

        test("TENDER_PARTICIPANT is used only where it is defined and by the tender files") {
            val allowed = setOf("Foundation.kt", "TenderService.kt", "TenderVisibilityPolicy.kt")
            val offenders = mutableListOf<String>()
            listOf(SourceScan.mainRoot(), sharedMain, clientMain).filter { it.exists() }.forEach { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                    if ("TENDER_PARTICIPANT" in SourceScan.blank(file.readText()) && file.name !in allowed) offenders += file.path
                }
            }
            offenders.shouldBeEmpty()
        }

        test(
            "the tender tables are referenced only by the tender files and the generated objects; queries only by the store and the contributor",
        ) {
            val table = Regex("""\bTender(Bid|Award|Acceptance)?Table\b""")
            val query =
                Regex(
                    """\bTender(Bid|Award|Acceptance)?Table\s*\.\s*(selectAll|select|insert|update|upsert|batchInsert|deleteWhere|deleteAll|slice)\b""",
                )
            val referencing = mutableListOf<String>()
            val querying = mutableListOf<String>()
            serverFiles.filter { "/db/generated/" !in it.path }.forEach { file ->
                val code = SourceScan.blank(file.readText())
                if (table.containsMatchIn(code) && file !in tenderFiles) referencing += file.path
                if (query.containsMatchIn(code) && file.name !in setOf("TenderStore.kt", "TenderPersonalData.kt")) querying += file.path
            }
            referencing.shouldBeEmpty()
            querying.shouldBeEmpty()
        }

        test("the tender files never touch the LTR ledger or a balance provider, and EUR is named only to refuse it") {
            val offenders = mutableListOf<String>()
            tenderFiles.forEach { file ->
                val code = SourceScan.blank(file.readText())
                if (Regex("""LtrLedger|LtrBalanceProvider|LedgerBacked|lockForDebit|freeBalance""").containsMatchIn(code)) {
                    offenders +=
                        "ledger: ${file.name}"
                }
                if ("TenderCurrency.EUR" in code && file.name != "TenderService.kt") offenders += "EUR: ${file.name}"
            }
            offenders.shouldBeEmpty()
            val service = SourceScan.blank(tenderFiles.single { it.name == "TenderService.kt" }.readText())
            Regex("""TenderCurrency\.EUR""").findAll(service).count() shouldBe 0 // the service compares against LTR, EUR never appears
        }

        test("no log line of the tender service names a price, a text, a title or a name") {
            val service = tenderFiles.single { it.name == "TenderService.kt" }.readText()
            val logLines = service.lines().filter { "logger." in it }
            (logLines.size > 0) shouldBe true
            logLines.filter { Regex("""(?i)price|text|title|name|reason|justification""").containsMatchIn(it) }.shouldBeEmpty()
        }
    })
