package network.lapis.cloud.server.meetingreward

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.IMeetingRewardService

/**
 * V1.9.316 -- the recognition for attending a Sitzung is booked in ONE file, and nothing else about it touches the ledger:
 *
 *  1. `MEETING_REWARD` and `LtrLedgerReferenceType.MEETING` are written only in `MeetingRewardPayout.kt` (outside the generated objects and the shared
 *     enums that define them; the readers `PublicTransparencyReader` may count the type);
 *  2. `MeetingRewardPayout.kt` never takes a plain row lock on a member (only `lockedMemberStatuses` / `forMemberUpdate`), never names EUR and writes
 *     the ledger only with the issuance type;
 *  3. the five `meeting_reward*` tables are read and written only by `MeetingRewardStore` (the DSGVO contributor may read them);
 *  4. the RPC surface is exactly the known eleven methods, every one except the own-lines read is gated by the administrator role.
 */
class MeetingRewardLedgerTripwireTest :
    FunSpec({
        val serverFiles = SourceScan.mainFiles()

        test("the scan sees the code base and the payout file") {
            serverFiles.size shouldBeGreaterThan 500
            serverFiles.count { it.name == "MeetingRewardPayout.kt" } shouldBeGreaterThan 0
        }

        test(
            "MEETING_REWARD and LtrLedgerReferenceType.MEETING appear in no server file but MeetingRewardPayout.kt (and the transparency reader)",
        ) {
            val offenders =
                serverFiles
                    .filter { "/db/generated/" !in it.path && it.name != "MeetingRewardPayout.kt" }
                    .filter {
                        val code = SourceScan.blank(it.readText())
                        Regex("""\bMEETING_REWARD\b""").containsMatchIn(code) ||
                            Regex("""LtrLedgerReferenceType\.MEETING\b""").containsMatchIn(code)
                    }.map { it.name }
            offenders shouldBe listOf("PublicTransparencyReader.kt")
        }

        test(
            "the ledger table is written only with the issuance type by the payout, and the payout never names EUR or takes a plain member lock",
        ) {
            val code = SourceScan.blank(serverFiles.single { it.name == "MeetingRewardPayout.kt" }.readText())
            Regex("""MemberTable[^\n]*forUpdate\(\)""").containsMatchIn(code) shouldBe false
            ("forUpdate()" in code) shouldBe false
            ("EUR" in code) shouldBe false
            Regex("""LtrLedgerEntryTable\s*\.\s*(update|deleteWhere|deleteAll)""").containsMatchIn(code) shouldBe false
            Regex("""LtrLedgerEntryType\.(?!MEETING_REWARD)\w+""").containsMatchIn(code) shouldBe false
            // no other meeting-reward file writes the ledger
            serverFiles
                .filter { it.path.contains("/meetingreward/") && it.name != "MeetingRewardPayout.kt" }
                .filter { Regex("""LtrLedgerEntryTable""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("the meeting_reward tables are used only by the store, the generated objects and the data-subject contributor") {
            val tables = Regex("""\bMeetingReward(Policy|PolicyChange|Committee|Settlement|Line)Table\b""")
            val users =
                serverFiles
                    .filter { "/db/generated/" !in it.path }
                    .filter { tables.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            // the payout and the settlement operations name a table only in a column constant read of an already loaded row
            users shouldBe
                setOf(
                    "MeetingRewardStore.kt",
                    "MeetingRewardPersonalData.kt",
                    "MeetingRewardPayout.kt",
                    "MeetingRewardSettlementOperations.kt",
                    "MeetingRewardPolicyOperations.kt",
                )
            // ... but none of them runs a query of its own: every insert/update/delete is in the store
            listOf("MeetingRewardPayout.kt", "MeetingRewardSettlementOperations.kt", "MeetingRewardPolicyOperations.kt").forEach { name ->
                val code = SourceScan.blank(serverFiles.single { it.name == name }.readText())
                Regex("""MeetingReward\w*Table\s*\.\s*(insert|update|deleteWhere|deleteAll|batchInsert|upsert|selectAll|select)\b""")
                    .findAll(code)
                    .map { it.value }
                    .toList()
                    .filter { name != "MeetingRewardPolicyOperations.kt" || !it.contains("select") }
                    .shouldBeEmpty()
            }
        }

        test("the RPC surface is exactly the known eleven methods, in this order") {
            IMeetingRewardService::class.java.declaredMethods
                .filter { !it.isSynthetic && '$' !in it.name }
                .map { it.name }
                .toSet() shouldBe
                setOf(
                    "getMeetingRewardPolicy",
                    "listMeetingRewardResolutionOptions",
                    "proposeMeetingRewardPolicyChange",
                    "confirmMeetingRewardPolicyChange",
                    "withdrawMeetingRewardPolicyChange",
                    "disableMeetingReward",
                    "previewMeetingRewardSettlement",
                    "prepareMeetingRewardSettlement",
                    "releaseMeetingRewardSettlement",
                    "discardMeetingRewardSettlement",
                    "listMyMeetingRewardLines",
                )
        }

        test(
            "the service gates every method but the own-lines read by an administrator-only role set, and never takes a member id for the own lines",
        ) {
            val code = SourceScan.blank(serverFiles.single { it.name == "MeetingRewardService.kt" }.readText())
            // the definition of the helper + the six policy methods; the definition + the four settlement methods
            Regex("""policyActor\(\)""").findAll(code).count() shouldBe 7
            Regex("""settlementActor\(\)""").findAll(code).count() shouldBe 5
            Regex("""requireRole\(\*MEETING_REWARD_POLICY_ROLES\.toTypedArray\(\)\)""").findAll(code).count() shouldBe 1
            Regex("""requireRole\(\*MEETING_REWARD_SETTLEMENT_ROLES\.toTypedArray\(\)\)""").findAll(code).count() shouldBe 1
            // the own-lines read passes the id of the CALLER and nothing else
            Regex("""ownLines\(\s*memberId = current\.memberId,""").containsMatchIn(code) shouldBe true
        }

        test("the service is the LAST registerService of Application.kt") {
            val code = SourceScan.blank(java.io.File(SourceScan.mainRoot(), "network/lapis/cloud/server/Application.kt").readText())
            val registrations = Regex("""registerService\(""").findAll(code).map { it.range.first }.toList()
            registrations.last() shouldBe code.indexOf("registerService(IMeetingRewardService::class)")
        }

        test("no log line of the recognition code carries a name, an amount or a text") {
            serverFiles
                .filter { it.path.contains("/meetingreward/") || it.name == "MeetingRewardService.kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (Regex("""logger\.(info|warn|error|debug|trace)""").containsMatchIn(line) &&
                            Regex("""displayName|amount|total|\.name\b|email""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                        ) {
                            "${file.name}:${index + 1}"
                        } else {
                            null
                        }
                    }
                }.shouldBeEmpty()
        }
    })
