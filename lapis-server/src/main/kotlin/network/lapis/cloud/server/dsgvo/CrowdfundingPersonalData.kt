package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [CrowdfundingProjectTable] (V0.6.1 Internes Crowdfunding) plus the two tables that hang
 * off it: [CrowdfundingReactionTable] (Like/Dislike Verteilungs-Korb input) and
 * [CrowdfundingDistributionTable] (`triggered_by` is its only member FK -- `project_id` targets
 * `crowdfunding_project`, not `member`, so it never surfaces in
 * `PersonalDataCoverageTest`'s `information_schema` walk on its own). `crowdfunding_submission_gate`
 * has no member FK at all and needs no entry here.
 *
 * Retain-with-reason across the board, same precedent as [GovernancePersonalData]/
 * [LtrPersonalData]: a project's title/description/submitter is the organizational record of
 * what was proposed and by whom, a Like/Dislike reaction is part of other members' shared
 * donation-allocation history, and a distribution decision is an auditable allocation record --
 * none are purely personal data, and every FK pointer resolves to the now-anonymized
 * [network.lapis.cloud.server.db.generated.MemberTable] row post-erasure.
 */
object CrowdfundingPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "crowdfunding"
    override val displayName = "Internes Crowdfunding"
    override val coveredTables =
        setOf(
            CrowdfundingProjectTable,
            CrowdfundingReactionTable,
            CrowdfundingDistributionTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("projectsSubmitted") {
                CrowdfundingProjectTable
                    .selectAll()
                    .where { CrowdfundingProjectTable.submitterMemberId eq memberId }
                    .forEach { row -> add(projectSummaryJson(row)) }
            }
            putJsonArray("projectsReviewed") {
                CrowdfundingProjectTable
                    .selectAll()
                    .where { CrowdfundingProjectTable.reviewedBy eq memberId }
                    .forEach { row -> add(projectSummaryJson(row)) }
            }
            putJsonArray("reactionsCast") {
                CrowdfundingReactionTable
                    .selectAll()
                    .where { CrowdfundingReactionTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[CrowdfundingReactionTable.id].toString())
                                put("projectId", row[CrowdfundingReactionTable.projectId].toString())
                                put("value", row[CrowdfundingReactionTable.reactionValue].name)
                                put("castAt", row[CrowdfundingReactionTable.castAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("distributionsTriggered") {
                CrowdfundingDistributionTable
                    .selectAll()
                    .where { CrowdfundingDistributionTable.triggeredBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[CrowdfundingDistributionTable.id].toString())
                                put("projectId", row[CrowdfundingDistributionTable.projectId].toString())
                                put("amountEur", row[CrowdfundingDistributionTable.amountEur].toPlainString())
                                put("computedAt", row[CrowdfundingDistributionTable.computedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val projectCondition =
            (CrowdfundingProjectTable.submitterMemberId eq memberId) or (CrowdfundingProjectTable.reviewedBy eq memberId)
        val projectCount = CrowdfundingProjectTable.selectAll().where { projectCondition }.count()

        val reactionCount = CrowdfundingReactionTable.selectAll().where { CrowdfundingReactionTable.memberId eq memberId }.count()

        val distributionCount =
            CrowdfundingDistributionTable.selectAll().where { CrowdfundingDistributionTable.triggeredBy eq memberId }.count()

        return listOf(
            TableErasureOutcome(
                table = "crowdfunding_project",
                rowsRetained = projectCount.toInt(),
                retentionReason =
                    "Title, description and review decision are organizational records of what " +
                        "was proposed to the association and how the board decided -- no field " +
                        "is erased.",
            ),
            TableErasureOutcome(
                table = "crowdfunding_reaction",
                rowsRetained = reactionCount.toInt(),
                retentionReason =
                    "A Like/Dislike is part of the shared, auditable basis for other members' " +
                        "monthly donation-pool allocation -- erasing it would silently change a " +
                        "past distribution's basis.",
            ),
            TableErasureOutcome(
                table = "crowdfunding_distribution",
                rowsRetained = distributionCount.toInt(),
                retentionReason = "Who triggered a monthly distribution is part of its own auditable allocation record.",
            ),
        )
    }
}

private fun projectSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[CrowdfundingProjectTable.id].toString())
        put("title", row[CrowdfundingProjectTable.title])
        put("status", row[CrowdfundingProjectTable.status].name)
        put("submittedAt", row[CrowdfundingProjectTable.submittedAt].toString())
    }
