package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.308 -- the payout is the ONLY thing that books LTR for a tender, and it does so in ONE file:
 *
 *  1. `TENDER_PAYOUT` and the reference type `LtrLedgerReferenceType.TENDER` are written only in `TenderPayout.kt` (outside the generated objects and
 *     the shared enum that defines them; the migration and the client labels are not server code);
 *  2. `TenderPayout.kt` never takes a plain row lock on a member (only the `forMemberUpdate` helper) and never names EUR.
 */
class TenderPayoutLedgerTripwireTest :
    FunSpec({
        val serverFiles = SourceScan.mainFiles()

        test("the scan sees the code base and the payout file") {
            serverFiles.size shouldBeGreaterThan 500
            serverFiles.count { it.name == "TenderPayout.kt" } shouldBeGreaterThan 0
        }

        test("TENDER_PAYOUT and LtrLedgerReferenceType.TENDER appear in no server file but TenderPayout.kt") {
            val offenders =
                serverFiles
                    .filter { "/db/generated/" !in it.path && it.name != "TenderPayout.kt" }
                    .filter {
                        val code = SourceScan.blank(it.readText())
                        Regex("""\bTENDER_PAYOUT\b""").containsMatchIn(code) ||
                            Regex("""LtrLedgerReferenceType\.TENDER\b""").containsMatchIn(code)
                    }.map { it.path }
            // the reader of the issued total counts the type (it never writes it); nothing else may name it
            offenders.filter { !it.endsWith("PublicTransparencyReader.kt") }.shouldBeEmpty()
        }

        test("TenderPayout.kt takes no plain member lock and never names the reserved currency") {
            val code = SourceScan.blank(serverFiles.single { it.name == "TenderPayout.kt" }.readText())
            Regex("""MemberTable[^\n]*forUpdate\(\)""").containsMatchIn(code) shouldBe false
            ("TenderCurrency.EUR" in code) shouldBe false
            ("forUpdate()" in code) shouldBe false
        }
    })
