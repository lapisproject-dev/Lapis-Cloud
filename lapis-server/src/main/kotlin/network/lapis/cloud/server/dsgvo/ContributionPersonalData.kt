package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns [ContributionTable]. Retained on erasure regardless of [ErasureMode] — accounting
 * retention duty (GoBD/HGB/AO, 10 Jahre) explicitly overrides the general anonymize-or-delete
 * default. Only the free-text `note` column (may contain third-party remarks, e.g. a
 * treasurer's comment about another member) is cleared. The `member_id` pointer stays intact and
 * now resolves to the anonymized [network.lapis.cloud.server.db.generated.MemberTable] row — see
 * [FoundationPersonalData].
 */
object ContributionPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "contributions"
    override val displayName = "Beitraege"
    override val coveredTables = setOf(ContributionTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonArray {
            ContributionTable
                .selectAll()
                .where { ContributionTable.memberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[ContributionTable.id].toString())
                            put("periodStart", row[ContributionTable.periodStart].toString())
                            put("periodEnd", row[ContributionTable.periodEnd].toString())
                            put("amountDue", row[ContributionTable.amountDue].toPlainString())
                            put("status", row[ContributionTable.status].name)
                            put("paidAt", row[ContributionTable.paidAt]?.toString())
                            put("paidAmount", row[ContributionTable.paidAmount]?.toPlainString())
                            put("note", row[ContributionTable.note])
                        },
                    )
                }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total = ContributionTable.selectAll().where { ContributionTable.memberId eq memberId }.count()
        ContributionTable.update({ ContributionTable.memberId eq memberId }) {
            it[note] = null
        }
        return listOf(
            TableErasureOutcome(
                table = "contribution",
                rowsRetained = total.toInt(),
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre)",
            ),
        )
    }
}
