package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns every member-FK-bearing table of Democratic Elections (V0.2.4): [ElectionTable] (`opened_by`),
 * [ElectionCandidacyTable] (`member_id` + free-text `motivation_text`), [ElectionBoardMemberTable]
 * (`member_id`), [ElectionEligibleVoterTable] (`member_id`), [ElectionParticipationTable] (`member_id`),
 * [ElectionTallyApprovalTable] (`member_id`) and [ElectionBallotTable] (`member_id`, nullable -- always
 * `NULL` on the `secret` path, populated only on the non-secret path).
 * [network.lapis.cloud.server.db.generated.ElectionOptionTable] and
 * [network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable] deliberately have **no**
 * contributor entry -- see [PersonalDataRegistry.noPersonalDataAllowlist] for the written reason
 * each is allowlisted instead: neither carries a `member` FK of its own (an option resolves to a
 * member only one hop away via `election_candidacy`; a selection resolves to one only two hops away
 * via `election_ballot`).
 *
 * Retain-with-reason across the board, same precedent as [GovernancePersonalData]: who opened an
 * Election, who stood as a candidate (and why), who served on an election board, who was eligible to
 * vote, who approved counting, and who participated (without revealing *what* they voted, on the
 * secret path -- and even on the non-secret path, the ballot itself is an electoral record kept
 * for the same reason [GovernancePersonalData] retains `vote_ballot`) are all
 * accountability-relevant electoral records, not purely personal data.
 */
object ElectionPersonalData : PersonalDataContributor {
    override val sectionKey = "elections"
    override val displayName = "Democratic Elections"
    override val coveredTables =
        setOf(
            ElectionTable,
            ElectionCandidacyTable,
            ElectionBoardMemberTable,
            ElectionEligibleVoterTable,
            ElectionParticipationTable,
            ElectionTallyApprovalTable,
            ElectionBallotTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("electionsOpened") {
                ElectionTable
                    .selectAll()
                    .where { ElectionTable.openedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionTable.id].toString())
                                put("motionId", row[ElectionTable.motionId].toString())
                                put("title", row[ElectionTable.title])
                                put("status", row[ElectionTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("candidacies") {
                ElectionCandidacyTable
                    .selectAll()
                    .where { ElectionCandidacyTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionCandidacyTable.id].toString())
                                put("electionId", row[ElectionCandidacyTable.electionId].toString())
                                put("motivationText", row[ElectionCandidacyTable.motivationText])
                                put("submittedAt", row[ElectionCandidacyTable.submittedAt].toString())
                                put("withdrawnAt", row[ElectionCandidacyTable.withdrawnAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("electionBoardAppointments") {
                ElectionBoardMemberTable
                    .selectAll()
                    .where { ElectionBoardMemberTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionBoardMemberTable.id].toString())
                                put("electionId", row[ElectionBoardMemberTable.electionId].toString())
                                put("appointedAt", row[ElectionBoardMemberTable.appointedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("eligibilities") {
                ElectionEligibleVoterTable
                    .selectAll()
                    .where { ElectionEligibleVoterTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionEligibleVoterTable.id].toString())
                                put("electionId", row[ElectionEligibleVoterTable.electionId].toString())
                            },
                        )
                    }
            }
            putJsonArray("participations") {
                ElectionParticipationTable
                    .selectAll()
                    .where { ElectionParticipationTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionParticipationTable.id].toString())
                                put("electionId", row[ElectionParticipationTable.electionId].toString())
                                put("votedAt", row[ElectionParticipationTable.votedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("tallyApprovals") {
                ElectionTallyApprovalTable
                    .selectAll()
                    .where { ElectionTallyApprovalTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionTallyApprovalTable.id].toString())
                                put("electionId", row[ElectionTallyApprovalTable.electionId].toString())
                                put("approvedAt", row[ElectionTallyApprovalTable.approvedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("ballotsNonSecret") {
                // Only the non-secret path ever has a non-null member_id here -- a secret Election's
                // ballots are unreachable from this export by design (see ElectionTable KDoc).
                ElectionBallotTable
                    .selectAll()
                    .where { ElectionBallotTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ElectionBallotTable.id].toString())
                                put("electionId", row[ElectionBallotTable.electionId].toString())
                                put("castAt", row[ElectionBallotTable.castAt].toString())
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val electionCount = ElectionTable.selectAll().where { ElectionTable.openedBy eq memberId }.count()

        val candidacyCount = ElectionCandidacyTable.selectAll().where { ElectionCandidacyTable.memberId eq memberId }.count()

        val electionBoardCount = ElectionBoardMemberTable.selectAll().where { ElectionBoardMemberTable.memberId eq memberId }.count()

        val electionEligibleVoterCount =
            ElectionEligibleVoterTable
                .selectAll()
                .where { ElectionEligibleVoterTable.memberId eq memberId }
                .count()

        val participationCount = ElectionParticipationTable.selectAll().where { ElectionParticipationTable.memberId eq memberId }.count()

        val tallyApprovalCount = ElectionTallyApprovalTable.selectAll().where { ElectionTallyApprovalTable.memberId eq memberId }.count()

        val ballotCount = ElectionBallotTable.selectAll().where { ElectionBallotTable.memberId eq memberId }.count()

        // Kept deliberately terse (unlike GovernancePersonalData's longer prose): outcomeSummary
        // is JSON-encoded into ErasureRequestTable.outcomeSummary, a VARCHAR(4000) column shared
        // by every contributor's outcomes combined -- verbose per-table reasons here would risk
        // exceeding that shared budget once a 7-table contributor like this one is added in.
        return listOf(
            TableErasureOutcome(
                table = "election",
                rowsRetained = electionCount.toInt(),
                retentionReason = "Opening is part of the accountable resolution process.",
            ),
            TableErasureOutcome(
                table = "election_candidacy",
                rowsRetained = candidacyCount.toInt(),
                retentionReason = "Candidacy/motivation text are part of the public election process.",
            ),
            TableErasureOutcome(
                table = "election_board_member",
                rowsRetained = electionBoardCount.toInt(),
                retentionReason = "Record of who formed the election board of an Election.",
            ),
            TableErasureOutcome(
                table = "election_eligible_voter",
                rowsRetained = electionEligibleVoterCount.toInt(),
                retentionReason = "Frozen eligibility snapshot, part of the election record.",
            ),
            TableErasureOutcome(
                table = "election_participation",
                rowsRetained = participationCount.toInt(),
                retentionReason = "One-member-one-vote record for the secret path, not the ballot content.",
            ),
            TableErasureOutcome(
                table = "election_tally_approval",
                rowsRetained = tallyApprovalCount.toInt(),
                retentionReason = "Record of the four-eyes principle before every tally.",
            ),
            TableErasureOutcome(
                table = "election_ballot",
                rowsRetained = ballotCount.toInt(),
                retentionReason = "The ballot is the core electoral artifact, analogous to vote_ballot.",
            ),
        )
    }
}
