package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.MeetingRewardCommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyChangeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns the five `meeting_reward*` tables (V1.9.316, LTR recognition for attending a Sitzung). The member columns are
 * `meeting_reward_line.member_id` (the recognised person), `meeting_reward_policy_change.proposed_by` / `decided_by` and
 * `meeting_reward_settlement.prepared_by` / `released_by`. `meeting_reward_policy` and `meeting_reward_committee` hold no member.
 *
 * Erasure: EVERYTHING is retained. The lines, the settlements and the changes of the rules are the booking evidence of an LTR issuance (GoBD, like
 * a MINT and a tender payout) and the decision record of the organisation; they name a person only by id, the name leaves with the anonymised member
 * row. The recognition the member RECEIVED is a ledger row and appears in the LTR section as well.
 * The export lists the member's own lines (meeting, amounts, status), the changes they proposed or decided and the settlements they prepared or released.
 */
object MeetingRewardPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "meeting-reward"
    override val displayName = "LTR-Anerkennung für Sitzungsteilnahme"
    override val coveredTables =
        setOf(
            MeetingRewardPolicyTable,
            MeetingRewardPolicyChangeTable,
            MeetingRewardCommitteeTable,
            MeetingRewardSettlementTable,
            MeetingRewardLineTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("lines") {
                MeetingRewardLineTable
                    .selectAll()
                    .where { MeetingRewardLineTable.memberId eq memberId }
                    .orderBy(MeetingRewardLineTable.periodYear to SortOrder.ASC, MeetingRewardLineTable.id to SortOrder.ASC)
                    .forEach { row ->
                        val meeting =
                            MeetingTable
                                .selectAll()
                                .where { MeetingTable.id eq row[MeetingRewardLineTable.meetingId] }
                                .singleOrNull()
                        add(
                            buildJsonObject {
                                put("meetingId", row[MeetingRewardLineTable.meetingId].toString())
                                put("meetingTitle", meeting?.get(MeetingTable.title))
                                put("attendanceStatus", row[MeetingRewardLineTable.attendanceStatus].name)
                                put("reason", row[MeetingRewardLineTable.reason]?.name)
                                put("functionRole", row[MeetingRewardLineTable.functionRole]?.name)
                                put("baseAmount", row[MeetingRewardLineTable.baseAmount].toPlainString())
                                put("bonusAmount", row[MeetingRewardLineTable.bonusAmount].toPlainString())
                                put("amount", row[MeetingRewardLineTable.amount].toPlainString())
                                put("paid", row[MeetingRewardLineTable.ledgerEntryId] != null)
                            },
                        )
                    }
            }
            putJsonArray("policyChanges") {
                MeetingRewardPolicyChangeTable
                    .selectAll()
                    .where {
                        (MeetingRewardPolicyChangeTable.proposedBy eq memberId) or
                            (MeetingRewardPolicyChangeTable.decidedBy eq memberId)
                    }.orderBy(
                        MeetingRewardPolicyChangeTable.proposedAt to SortOrder.ASC,
                        MeetingRewardPolicyChangeTable.id to SortOrder.ASC,
                    ).forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MeetingRewardPolicyChangeTable.id].toString())
                                put("kind", row[MeetingRewardPolicyChangeTable.kind].name)
                                put("status", row[MeetingRewardPolicyChangeTable.status].name)
                                put("proposedByMe", row[MeetingRewardPolicyChangeTable.proposedBy] == memberId)
                                put("decidedByMe", row[MeetingRewardPolicyChangeTable.decidedBy] == memberId)
                                put("proposedAt", row[MeetingRewardPolicyChangeTable.proposedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("settlements") {
                MeetingRewardSettlementTable
                    .selectAll()
                    .where {
                        (MeetingRewardSettlementTable.preparedBy eq memberId) or (MeetingRewardSettlementTable.releasedBy eq memberId)
                    }.orderBy(MeetingRewardSettlementTable.preparedAt to SortOrder.ASC, MeetingRewardSettlementTable.id to SortOrder.ASC)
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("meetingId", row[MeetingRewardSettlementTable.meetingId].toString())
                                put("status", row[MeetingRewardSettlementTable.status].name)
                                put("preparedByMe", row[MeetingRewardSettlementTable.preparedBy] == memberId)
                                put("releasedByMe", row[MeetingRewardSettlementTable.releasedBy] == memberId)
                                put("preparedAt", row[MeetingRewardSettlementTable.preparedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val lines = MeetingRewardLineTable.selectAll().where { MeetingRewardLineTable.memberId eq memberId }.count()
        val changes =
            MeetingRewardPolicyChangeTable
                .selectAll()
                .where { (MeetingRewardPolicyChangeTable.proposedBy eq memberId) or (MeetingRewardPolicyChangeTable.decidedBy eq memberId) }
                .count()
        val settlements =
            MeetingRewardSettlementTable
                .selectAll()
                .where { (MeetingRewardSettlementTable.preparedBy eq memberId) or (MeetingRewardSettlementTable.releasedBy eq memberId) }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "meeting_reward_line",
                rowsRetained = lines.toInt(),
                retentionReason =
                    "A line is the booking evidence of an LTR issuance (GoBD); it names the person only by id. The name leaves with the " +
                        "anonymised member row.",
            ),
            TableErasureOutcome(
                table = "meeting_reward_policy_change",
                rowsRetained = changes.toInt(),
                retentionReason =
                    "A change of the rules is a decision record of the organisation (it cites a resolution of the general " +
                        "assembly); it names the proposing and the confirming administrator only by id.",
            ),
            TableErasureOutcome(
                table = "meeting_reward_settlement",
                rowsRetained = settlements.toInt(),
                retentionReason =
                    "A settlement is the booking record of an LTR issuance (GoBD); it names the preparing and the releasing administrator only by id.",
            ),
            TableErasureOutcome(
                table = "meeting_reward_policy",
                rowsRetained = 0,
                retentionReason = "The rules of the organisation hold no personal data.",
            ),
            TableErasureOutcome(
                table = "meeting_reward_committee",
                rowsRetained = 0,
                retentionReason = "The list of the committees that take part holds no personal data.",
            ),
        )
    }
}
