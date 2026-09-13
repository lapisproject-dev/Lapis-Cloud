package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.VatComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)". Owns [VatComplianceAcknowledgmentTable] (a
 * direct `acknowledged_by_member_id` FK) -- exact structural + retention mirror of
 * [DunningPersonalData]'s own `dunning_compliance_acknowledgment` half, see that object's KDoc
 * for the full mechanism this one reuses unchanged.
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- no field is ever cleared.** The row
 * IS the accountability record (Art. 5(2) DSGVO): proof of WHICH ADMIN quittierte WHICH
 * [network.lapis.cloud.server.rpc.VatComplianceDisclaimer] version before the USt module was
 * switched on. Unlike [DunningPersonalData] (which still clears a free-text
 * `cancellation_reason` on its sibling table), there is no free-text field here to clear.
 */
object VatPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "vat"
    override val displayName = "Umsatzsteuer"
    override val coveredTables = setOf(VatComplianceAcknowledgmentTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonArray {
            VatComplianceAcknowledgmentTable
                .selectAll()
                .where { VatComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[VatComplianceAcknowledgmentTable.id].toString())
                            put("acknowledgedAt", row[VatComplianceAcknowledgmentTable.acknowledgedAt].toString())
                            put("disclaimerVersion", row[VatComplianceAcknowledgmentTable.disclaimerVersion])
                        },
                    )
                }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val acknowledgmentCount =
            VatComplianceAcknowledgmentTable
                .selectAll()
                .where { VatComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "vat_compliance_acknowledgment",
                rowsRetained = acknowledgmentCount.toInt(),
                retentionReason = "Nachweis der ADMIN-Rechtshinweis-Bestaetigung -- accountability (Art. 5(2) DSGVO).",
            ),
        )
    }
}
