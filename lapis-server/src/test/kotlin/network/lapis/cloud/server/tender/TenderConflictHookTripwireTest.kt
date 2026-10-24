package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.201 -- the governance hooks of the conflict of interest, pinned in the source: on the award motion of a tender its bidders are subtracted from
 * the quorum and from the frozen circle of voters, refused as voters, and the other decision paths are shut. The hooks only SUBTRACT a set that
 * `TenderConflicts` supplies; nobody calls the electorate function a further time (`CommitteeElectorateTripwireTest` keeps that).
 */
class TenderConflictHookTripwireTest :
    FunSpec({
        val rpc = File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc")

        fun code(name: String) = SourceScan.blank(File(rpc, name).readText())

        /** The text of a member function (declaration to the next member declaration at class level). */
        fun bodyOf(
            source: String,
            name: String,
        ): String {
            val start = Regex("""(?m)^    (override suspend |private |internal )?fun $name\(""").find(source) ?: error("no function $name")
            val rest = source.substring(start.range.last)
            val next = Regex("""(?m)^    (override suspend fun|private fun|internal fun|fun )""").find(rest)
            return if (next == null) rest else rest.substring(0, next.range.first)
        }

        test("computeQuorum and insertResolutionRow take the excluded members and subtract them from the electorate") {
            val book = code("ResolutionBook.kt")
            Regex("""fun computeQuorum\([^)]*excludedMemberIds: Set<Uuid> = emptySet\(\)""").containsMatchIn(book) shouldBe true
            Regex("""fun insertResolutionRow\([^)]*excludedMemberIds: Set<Uuid> = emptySet\(\)""").containsMatchIn(book) shouldBe true
            ("electorate.memberIds - excludedMemberIds" in book) shouldBe true
            Regex("""excludedMemberIds = excludedMemberIds""").findAll(book).count() shouldBe 1
        }

        test("resolveMotion and closeVote pass the bidders of the award motion into the resolution book") {
            val governance = code("GovernanceService.kt")
            val resolve = bodyOf(governance, "resolveMotion")
            ("tenderConflictedMemberIdsForMotion(aId)" in resolve) shouldBe true
            ("excludedMemberIds = tenderExcluded" in resolve) shouldBe true
            ("excludedMemberIds = tenderConflictedMemberIdsForMotion(" in bodyOf(governance, "closeVote")) shouldBe true
        }

        test("the frozen circle of a vote and the right to cast a ballot exclude the bidders") {
            val governance = code("GovernanceService.kt")
            ("excludedMemberIds = tenderConflictedMemberIdsForMotion(aId)" in bodyOf(governance, "openVote")) shouldBe true
            ("- excludedMemberIds" in bodyOf(governance, "freezeVoteElectorate")) shouldBe true
            val cast = bodyOf(governance, "castVoteBallot")
            ("tenderConflictedMemberIdsForMotion(voteRow[VoteTable.motionId])" in cast) shouldBe true
            ("ForbiddenException()" in cast) shouldBe true
            // the refusal comes before anything is staked
            (cast.indexOf("tenderConflictedMemberIdsForMotion") < cast.indexOf("lockForDebit")) shouldBe true
        }

        test("an election and a systemic consensus refuse the award motion of a tender") {
            ("requireNotTenderAwardMotion(aId)" in bodyOf(code("ElectionService.kt"), "openElection")) shouldBe true
            ("requireNotTenderAwardMotion(aId)" in bodyOf(code("SystemicConsensusService.kt"), "openSystemicConsensus")) shouldBe true
        }

        test("the governance files read the conflicted set only through the functions of TenderConflicts, never the tender tables") {
            val offenders = mutableListOf<String>()
            rpc.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TenderService.kt" }.forEach {
                if (Regex("""\bTender\w*Table\b|\bTenderStore\b""").containsMatchIn(SourceScan.blank(it.readText()))) offenders += it.name
            }
            offenders.shouldBeEmpty()
        }
    })
