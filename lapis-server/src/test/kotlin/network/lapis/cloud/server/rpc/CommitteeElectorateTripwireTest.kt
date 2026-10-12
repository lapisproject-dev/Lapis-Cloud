package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.112 -- source-scan tripwire: whoever decides WHO MAY VOTE or what the QUORUM's N is must ask [frozenOrLiveElectorate], never
 * [eligibleMemberIds] directly (that is the live set and ignores the electorate frozen when a decision was opened). Every direct call of
 * [eligibleMemberIds] is classified below with the reason it is the right one; a new caller fails here and forces that decision.
 */
class CommitteeElectorateTripwireTest :
    FunSpec({
        // file -> (number of direct calls, reason)
        val allowed =
            mapOf(
                "network/lapis/cloud/server/rpc/CommitteeEligibility.kt" to
                    (2 to "the definition and frozenOrLiveElectorate's live fallback"),
                "network/lapis/cloud/server/rpc/GovernanceService.kt" to
                    (2 to "castVoteBallot for a vote WITHOUT the marker (opened before V1.9.112) and freezeVoteElectorate itself"),
                "network/lapis/cloud/server/rpc/ElectionService.kt" to (1 to "openVoting: the election's own snapshot is taken here"),
                "network/lapis/cloud/server/rpc/SystemicConsensusService.kt" to
                    (1 to "snapshotEligibility: the consensus' own snapshot is taken here"),
                "network/lapis/cloud/server/rpc/SystemicConsensusReads.kt" to
                    (1 to "the live preview of a consensus that has no snapshot yet"),
                "network/lapis/cloud/server/db/StagingSeedGovernance.kt" to (1 to "staging seed: writes an election snapshot"),
                "network/lapis/cloud/server/routes/MailmergeRoutes.kt" to
                    (1 to "the invitation audience of a rule committee (a letter list, not a decision)"),
            )
        val call = Regex("""(?<![A-Za-z0-9_])eligibleMemberIds\s*\(""")

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        test("eligibleMemberIds is called only from the classified places") {
            val counts =
                SourceScan
                    .mainFiles()
                    .associate { relative(it) to call.findAll(SourceScan.blank(it.readText())).count() }
                    .filterValues { it > 0 }
            counts.keys shouldBe allowed.keys
            allowed.forEach { (path, expected) -> counts.getValue(path) shouldBe expected.first }
        }

        test("the quorum and the vote check go through frozenOrLiveElectorate") {
            val book = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/ResolutionBook.kt").readText())
            Regex("""frozenOrLiveElectorate\s*\(""").containsMatchIn(book) shouldBe true
            call.containsMatchIn(book) shouldBe false
            val own = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/VoteOwnParticipation.kt").readText())
            call.containsMatchIn(own) shouldBe false
        }
    })
