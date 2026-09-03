package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [PeerTransferTable] (V0.6.3 direkte LTR-Peer-to-Peer-Uebertragung). Three separate member
 * FKs -- `sender_member_id`, `recipient_member_id`, `initiated_by` (the acting TREASURER/BOARD/
 * ADMIN for a privileged [network.lapis.cloud.server.rpc.PeerTransferService.executeArbitrationTransfer]
 * correction, null for a self-initiated transfer) -- so [export]/[erase] both check all three,
 * same "actor plus subject(s)" shape [AuditLogPersonalData] already establishes for
 * `audit_log_entry.actor_member_id`/`subject_member_id`.
 *
 * Retain-with-reason across the board, same precedent as [LtrPersonalData]: a completed transfer
 * is unwiderruflich by design (see `18-peer-transfer.kuml.kts` file header) and is simultaneously
 * the sender's and the recipient's own property record -- anonymizing or deleting it on erasure
 * would corrupt that shared, mutually-relied-upon transaction history for whichever party did NOT
 * request erasure. The corresponding `ltr_ledger_entry` rows (PEER_TRANSFER_OUT/PEER_TRANSFER_IN)
 * are already covered by [LtrPersonalData] and not duplicated here.
 */
object PeerTransferPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "peerTransfer"
    override val displayName = "LTR-Peer-to-Peer-Uebertragungen"
    override val coveredTables = setOf(PeerTransferTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("transfersSent") {
                PeerTransferTable
                    .selectAll()
                    .where { PeerTransferTable.senderMemberId eq memberId }
                    .forEach { row -> add(transferSummaryJson(row)) }
            }
            putJsonArray("transfersReceived") {
                PeerTransferTable
                    .selectAll()
                    .where { PeerTransferTable.recipientMemberId eq memberId }
                    .forEach { row -> add(transferSummaryJson(row)) }
            }
            putJsonArray("arbitrationTransfersInitiated") {
                PeerTransferTable
                    .selectAll()
                    .where { PeerTransferTable.initiatedBy eq memberId }
                    .forEach { row -> add(transferSummaryJson(row)) }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val condition =
            (PeerTransferTable.senderMemberId eq memberId) or
                (PeerTransferTable.recipientMemberId eq memberId) or
                (PeerTransferTable.initiatedBy eq memberId)
        val total = PeerTransferTable.selectAll().where { condition }.count()
        return listOf(
            TableErasureOutcome(
                table = "peer_transfer",
                rowsRetained = total.toInt(),
                retentionReason = "Unwiderruflicher Transfer, zugleich Eigentumsnachweis beider Seiten -- keine Loeschung/Anonymisierung.",
            ),
        )
    }
}

private fun transferSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[PeerTransferTable.id].toString())
        put("amountLtr", row[PeerTransferTable.amountLtr].toPlainString())
        put("characterization", row[PeerTransferTable.characterization].name)
        put("purpose", row[PeerTransferTable.purpose])
        put("senderMemberId", row[PeerTransferTable.senderMemberId].toString())
        put("recipientMemberId", row[PeerTransferTable.recipientMemberId].toString())
        put("initiatedById", row[PeerTransferTable.initiatedBy]?.toString())
        put("createdAt", row[PeerTransferTable.createdAt].toString())
    }
