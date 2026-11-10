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
        // file -> (number of direct calls, reason). V1.9.298: the freeze paths moved to electorateMemberIds (membership AND voting right);
        // eligibleMemberIds stays the pure MEMBERSHIP for the places that are not a decision.
        val allowed =
            mapOf(
                "network/lapis/cloud/server/rpc/CommitteeEligibility.kt" to
                    (2 to "the definition and electorateMemberIds (which intersects the membership with the voting right)"),
                "network/lapis/cloud/server/rpc/SystemicConsensusService.kt" to
                    (1 to "proposerMembersOf: who may PROPOSE an option is membership, not the electorate (V1.9.298)"),
                "network/lapis/cloud/server/rpc/SystemicConsensusReads.kt" to
                    (1 to "canPropose of the participation DTO: the same membership rule as proposerMembersOf"),
                "network/lapis/cloud/server/messaging/DirectMessageGroups.kt" to
                    (1 to "the recipients of a group message: whoever sits in the committee TODAY (a message, not a decision)"),
                "network/lapis/cloud/server/routes/MailmergeRoutes.kt" to
                    (1 to "the invitation audience of a rule committee (a letter list, not a decision)"),
            )
        // V1.9.298: the ELECTORATE (membership AND voting right) -- every freeze path, the live fallback and the own-participation helper
        val electorateAllowed =
            mapOf(
                "network/lapis/cloud/server/rpc/CommitteeEligibility.kt" to
                    (2 to "the definition and frozenOrLiveElectorate's live fallback"),
                "network/lapis/cloud/server/rpc/GovernanceService.kt" to
                    (2 to "castVoteBallot for a vote WITHOUT the marker (opened before V1.9.112) and freezeVoteElectorate itself"),
                "network/lapis/cloud/server/rpc/ElectionOpening.kt" to (1 to "openVotingLocked: the election's own snapshot is taken here"),
                "network/lapis/cloud/server/rpc/SystemicConsensusService.kt" to
                    (1 to "snapshotEligibility: the consensus' own snapshot is taken here"),
                "network/lapis/cloud/server/db/StagingSeedGovernance.kt" to (1 to "staging seed: writes an election snapshot"),
            )
        val call = Regex("""(?<![A-Za-z0-9_])eligibleMemberIds\s*\(""")

        val electorateCall = Regex("""(?<![A-Za-z0-9_])electorateMemberIds\s*\(""")
        val voterCall = Regex("""(?<![A-Za-z0-9_])isCommitteeVoter\s*\(""")

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

        test("electorateMemberIds is called only from the freeze paths, the live fallback and the seed") {
            val counts =
                SourceScan
                    .mainFiles()
                    .associate { relative(it) to electorateCall.findAll(SourceScan.blank(it.readText())).count() }
                    .filterValues { it > 0 }
            counts.keys shouldBe electorateAllowed.keys
            electorateAllowed.forEach { (path, expected) -> counts.getValue(path) shouldBe expected.first }
        }

        test("isCommitteeVoter (the point query of the electorate) is called only by the definition and the own-participation helper") {
            val counts =
                SourceScan
                    .mainFiles()
                    .associate { relative(it) to voterCall.findAll(SourceScan.blank(it.readText())).count() }
                    .filterValues { it > 0 }
            counts shouldBe
                mapOf(
                    "network/lapis/cloud/server/rpc/CommitteeEligibility.kt" to 1,
                    "network/lapis/cloud/server/rpc/VoteOwnParticipation.kt" to 1,
                )
        }

        test("the three freeze files never use the pure membership eligibleMemberIds any more") {
            listOf("GovernanceService.kt", "ElectionOpening.kt", "StagingSeedGovernance.kt").forEach { name ->
                val file = SourceScan.mainFiles().first { it.name == name }
                call.containsMatchIn(SourceScan.blank(file.readText())) shouldBe false
            }
        }

        test("the quorum and the vote check go through frozenOrLiveElectorate") {
            val book = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/ResolutionBook.kt").readText())
            Regex("""frozenOrLiveElectorate\s*\(""").containsMatchIn(book) shouldBe true
            call.containsMatchIn(book) shouldBe false
            val own = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/VoteOwnParticipation.kt").readText())
            call.containsMatchIn(own) shouldBe false
        }
    })
