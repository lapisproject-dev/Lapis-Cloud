package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns every member-FK-bearing table of Systemic Consensus (V0.2.5): [SystemicConsensusTable]
 * (`opened_by`), [SystemicConsensusOptionTable] (`created_by`), [SystemicConsensusEligibleVoterTable]
 * (`member_id`), [SystemicConsensusParticipationTable] (`member_id`) and [SystemicConsensusBallotTable]
 * (`member_id`, nullable -- always `NULL` on the `secret` path, populated only on the non-secret
 * path, same shape as [ElectionPersonalData]'s `ElectionBallotTable`).
 * [network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable] deliberately has **no**
 * contributor entry -- see [PersonalDataRegistry.noPersonalDataAllowlist] for the written reason:
 * it carries no `member` FK of its own, resolving to a member only two hops away via
 * `systemic_consensus_ballot`, and only on the non-secret path.
 *
 * Retain-with-reason across the board, same precedent as [ElectionPersonalData]: who opened a
 * SystemicConsensus, who proposed which option, who was eligible to rate, and who participated
 * (without revealing *what* resistance they cast, on the secret path) are all
 * accountability-relevant electoral records, not purely personal data.
 */
object SystemicConsensusPersonalData : PersonalDataContributor {
    override val sectionKey = "systemic_consensus"
    override val displayName = "Systemic Consensus"
    override val coveredTables =
        setOf(
            SystemicConsensusTable,
            SystemicConsensusOptionTable,
            SystemicConsensusEligibleVoterTable,
            SystemicConsensusParticipationTable,
            SystemicConsensusBallotTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("systemicConsensusesOpened") {
                SystemicConsensusTable
                    .selectAll()
                    .where { SystemicConsensusTable.openedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[SystemicConsensusTable.id].toString())
                                put("motionId", row[SystemicConsensusTable.motionId].toString())
                                put("title", row[SystemicConsensusTable.title])
                                put("status", row[SystemicConsensusTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("optionsProposed") {
                SystemicConsensusOptionTable
                    .selectAll()
                    .where { SystemicConsensusOptionTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[SystemicConsensusOptionTable.id].toString())
                                put("systemicConsensusId", row[SystemicConsensusOptionTable.systemicConsensusId].toString())
                                put("label", row[SystemicConsensusOptionTable.label])
                            },
                        )
                    }
            }
            putJsonArray("eligibilities") {
                SystemicConsensusEligibleVoterTable
                    .selectAll()
                    .where { SystemicConsensusEligibleVoterTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[SystemicConsensusEligibleVoterTable.id].toString())
                                put("systemicConsensusId", row[SystemicConsensusEligibleVoterTable.systemicConsensusId].toString())
                                put("round", row[SystemicConsensusEligibleVoterTable.round])
                            },
                        )
                    }
            }
            putJsonArray("participations") {
                SystemicConsensusParticipationTable
                    .selectAll()
                    .where { SystemicConsensusParticipationTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[SystemicConsensusParticipationTable.id].toString())
                                put("systemicConsensusId", row[SystemicConsensusParticipationTable.systemicConsensusId].toString())
                                put("votedAt", row[SystemicConsensusParticipationTable.votedAt].toString())
                                put("round", row[SystemicConsensusParticipationTable.round])
                            },
                        )
                    }
            }
            putJsonArray("ballotsNonSecret") {
                // Only the non-secret path ever has a non-null member_id here -- a secret
                // SystemicConsensus's ballots are unreachable from this export by design.
                SystemicConsensusBallotTable
                    .selectAll()
                    .where { SystemicConsensusBallotTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[SystemicConsensusBallotTable.id].toString())
                                put("systemicConsensusId", row[SystemicConsensusBallotTable.systemicConsensusId].toString())
                                put("castAt", row[SystemicConsensusBallotTable.castAt].toString())
                                put("round", row[SystemicConsensusBallotTable.round])
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val systemicConsensusCount = SystemicConsensusTable.selectAll().where { SystemicConsensusTable.openedBy eq memberId }.count()
        val optionCount = SystemicConsensusOptionTable.selectAll().where { SystemicConsensusOptionTable.createdBy eq memberId }.count()
        val eligibleVoterCount =
            SystemicConsensusEligibleVoterTable.selectAll().where { SystemicConsensusEligibleVoterTable.memberId eq memberId }.count()
        val participationCount =
            SystemicConsensusParticipationTable
                .selectAll()
                .where {
                    SystemicConsensusParticipationTable.memberId eq
                        memberId
                }.count()
        val ballotCount =
            SystemicConsensusBallotTable
                .selectAll()
                .where { SystemicConsensusBallotTable.memberId eq memberId }
                .count()

        // Kept extremely terse (more so than ElectionPersonalData.erase) -- outcomeSummary is a
        // JSON-encoded VARCHAR(4000) column shared by EVERY contributor's outcomes combined
        // (see ElectionPersonalData KDoc), and this is already the 8th contributor registered --
        // every extra character here shrinks the budget for every future domain wave too.
        return listOf(
            TableErasureOutcome(
                table = "systemic_consensus",
                rowsRetained = systemicConsensusCount.toInt(),
                retentionReason = "Part of the resolution process.",
            ),
            TableErasureOutcome(
                table = "systemic_consensus_option",
                rowsRetained = optionCount.toInt(),
                retentionReason = "Part of the procedure.",
            ),
            TableErasureOutcome(
                table = "systemic_consensus_eligible_voter",
                rowsRetained = eligibleVoterCount.toInt(),
                retentionReason = "Eligibility snapshot.",
            ),
            TableErasureOutcome(
                table = "systemic_consensus_participation",
                rowsRetained = participationCount.toInt(),
                retentionReason = "One-vote record, secret.",
            ),
            TableErasureOutcome(
                table = "systemic_consensus_ballot",
                rowsRetained = ballotCount.toInt(),
                retentionReason = "Core electoral artifact.",
            ),
        )
    }
}
