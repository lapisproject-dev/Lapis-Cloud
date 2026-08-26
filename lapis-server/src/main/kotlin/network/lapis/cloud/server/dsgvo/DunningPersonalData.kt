package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Owns [DunningNoticeTable] (via `contribution.member_id`,
 * see the explicit-join note on [export]) and [DunningComplianceAcknowledgmentTable] (a direct
 * `acknowledged_by_member_id` FK).
 *
 * **Retained on erasure regardless of [ErasureMode]** -- same accounting-retention override
 * [ContributionPersonalData] already applies to `contribution` itself (GoBD/HGB/AO, 10 Jahre): a
 * dunning notice is part of that same Beitrags-/Buchungshistorie. Only the free-text
 * `cancellation_reason` (may contain a remark about a person) is cleared -- same "export/erase
 * symmetry" discipline [SocialPostModerationSnapshot]-adjacent contributors already follow.
 */
object DunningPersonalData : PersonalDataContributor {
    override val sectionKey = "dunning"
    override val displayName = "Mahnwesen"
    override val coveredTables = setOf(DunningNoticeTable, DunningComplianceAcknowledgmentTable)

    override fun export(memberId: Uuid) =
        buildJsonObject {
            put(
                "notices",
                buildJsonArray {
                    // Explicit join, not `DunningNoticeTable innerJoin ContributionTable` -- dunning_notice
                    // ALSO carries its own FK into member (created_by), so joining straight from
                    // DunningNoticeTable to ContributionTable and filtering on ContributionTable.memberId
                    // is unambiguous only because this join never touches MemberTable at all.
                    (DunningNoticeTable innerJoin ContributionTable)
                        .selectAll()
                        .where { ContributionTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[DunningNoticeTable.id].toString())
                                    put("contributionId", row[DunningNoticeTable.contributionId].toString())
                                    put("cycleNumber", row[DunningNoticeTable.cycleNumber])
                                    put("levelNumber", row[DunningNoticeTable.levelNumber])
                                    put("levelName", row[DunningNoticeTable.levelName])
                                    put("status", row[DunningNoticeTable.status].name)
                                    put("amountDue", row[DunningNoticeTable.amountDue].toPlainString())
                                    put("feeAmount", row[DunningNoticeTable.feeAmount]?.toPlainString())
                                    put("issuedAt", row[DunningNoticeTable.issuedAt].toString())
                                    put("respondBy", row[DunningNoticeTable.respondBy].toString())
                                    put("cancellationReason", row[DunningNoticeTable.cancellationReason])
                                },
                            )
                        }
                },
            )
            put(
                "complianceAcknowledgments",
                buildJsonArray {
                    DunningComplianceAcknowledgmentTable
                        .selectAll()
                        .where { DunningComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[DunningComplianceAcknowledgmentTable.id].toString())
                                    put("acknowledgedAt", row[DunningComplianceAcknowledgmentTable.acknowledgedAt].toString())
                                    put("disclaimerVersion", row[DunningComplianceAcknowledgmentTable.disclaimerVersion])
                                },
                            )
                        }
                },
            )
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val noticeIds =
            (DunningNoticeTable innerJoin ContributionTable)
                .selectAll()
                .where { ContributionTable.memberId eq memberId }
                .map { it[DunningNoticeTable.id] }
        if (noticeIds.isNotEmpty()) {
            DunningNoticeTable.update({ DunningNoticeTable.id inList noticeIds }) {
                it[cancellationReason] = null
            }
        }
        val acknowledgmentCount =
            DunningComplianceAcknowledgmentTable
                .selectAll()
                .where { DunningComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()

        return listOf(
            TableErasureOutcome(
                table = "dunning_notice",
                rowsRetained = noticeIds.size,
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) -- Teil der Beitragshistorie.",
            ),
            TableErasureOutcome(
                table = "dunning_compliance_acknowledgment",
                rowsRetained = acknowledgmentCount.toInt(),
                retentionReason = "Nachweis der ADMIN-Rechtshinweis-Bestaetigung -- accountability (Art. 5(2) DSGVO).",
            ),
        )
    }
}
