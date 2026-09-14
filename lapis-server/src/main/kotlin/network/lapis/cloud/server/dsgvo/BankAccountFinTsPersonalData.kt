package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.BankAccountFinTsAcknowledgmentTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". Owns [BankAccountFinTsAcknowledgmentTable] (a
 * direct `acknowledged_by_member_id` FK) -- exact structural + retention mirror of [VatPersonalData]
 * for `vat_compliance_acknowledgment`, see that object's own KDoc for the full mechanism this one
 * reuses unchanged. Deliberately NOT allowlisted (unlike `bank_account` itself in
 * [PersonalDataRegistry.noPersonalDataAllowlist]) -- an explicit "member X acknowledged legal text
 * version Y on date Z" row is itself a fact ABOUT that member's own action, same reasoning
 * [VatPersonalData] already applies to its own acknowledgment table.
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- no field is ever cleared.** The row IS
 * the accountability record (Art. 5(2) DSGVO): proof of WHICH ADMIN quittierte WHICH
 * [network.lapis.cloud.server.rpc.FinTsComplianceDisclaimer] version for WHICH bank account before
 * live retrieval was activated.
 */
object BankAccountFinTsPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "bank-account-fints"
    override val displayName = "FinTS/HBCI-Live-Kontoabruf"
    override val coveredTables = setOf(BankAccountFinTsAcknowledgmentTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonArray {
            BankAccountFinTsAcknowledgmentTable
                .selectAll()
                .where { BankAccountFinTsAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[BankAccountFinTsAcknowledgmentTable.id].toString())
                            put("bankAccountId", row[BankAccountFinTsAcknowledgmentTable.bankAccountId].toString())
                            put("acknowledgedAt", row[BankAccountFinTsAcknowledgmentTable.acknowledgedAt].toString())
                            put("disclaimerVersion", row[BankAccountFinTsAcknowledgmentTable.disclaimerVersion])
                        },
                    )
                }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val acknowledgmentCount =
            BankAccountFinTsAcknowledgmentTable
                .selectAll()
                .where { BankAccountFinTsAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "bank_account_fints_acknowledgment",
                rowsRetained = acknowledgmentCount.toInt(),
                retentionReason = "Nachweis der ADMIN-Rechtshinweis-Bestaetigung -- accountability (Art. 5(2) DSGVO).",
            ),
        )
    }
}
