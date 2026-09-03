package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [PriceOracleConversionTable] (V0.6.5 Price-Oracle fuer die Anker-Bindung). Two separate
 * member FKs -- `member_id` (the LTR recipient/donor) and `created_by` (the acting TREASURER/
 * BOARD/ADMIN who triggered the conversion, null for none recorded) -- so [export]/[erase] both
 * check both, same "actor plus subject" shape [PeerTransferPersonalData] already establishes for
 * `peer_transfer.sender_member_id`/`recipient_member_id`/`initiated_by`.
 *
 * Retain-with-reason across the board, same precedent as [LtrPersonalData]/[PeerTransferPersonalData]:
 * this row is a financial/LTR-provenance audit trail (which price/sources/timestamp/status
 * justified a specific LTR mint) -- anonymizing or deleting it on erasure would corrupt the
 * ledger's own traceability for whichever party did NOT request erasure (the `MINT`
 * `ltr_ledger_entry` row this conversion caused remains covered by [LtrPersonalData] and is not
 * duplicated here).
 *
 * `price_oracle_config` (the single-row, ADMIN-tunable policy row) has NO member FK at all and is
 * therefore not covered by any contributor -- see [PersonalDataRegistry.noPersonalDataAllowlist].
 */
object PriceOraclePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "priceOracleConversions"
    override val displayName = "Price-Oracle-Konvertierungen"
    override val coveredTables = setOf(PriceOracleConversionTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("conversionsReceived") {
                PriceOracleConversionTable
                    .selectAll()
                    .where { PriceOracleConversionTable.memberId eq memberId }
                    .forEach { row -> add(conversionSummaryJson(row)) }
            }
            putJsonArray("conversionsInitiated") {
                PriceOracleConversionTable
                    .selectAll()
                    .where { PriceOracleConversionTable.createdById eq memberId }
                    .forEach { row -> add(conversionSummaryJson(row)) }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val condition = (PriceOracleConversionTable.memberId eq memberId) or (PriceOracleConversionTable.createdById eq memberId)
        val total = PriceOracleConversionTable.selectAll().where { condition }.count()
        return listOf(
            TableErasureOutcome(
                table = "price_oracle_conversion",
                rowsRetained = total.toInt(),
                retentionReason = "Finanzielle Provenienz-/Auditspur fuer einen LTR-Mint -- keine Loeschung/Anonymisierung.",
            ),
        )
    }
}

private fun conversionSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[PriceOracleConversionTable.id].toString())
        put("donationAmount", row[PriceOracleConversionTable.donationAmount].toPlainString())
        put("donationCurrency", row[PriceOracleConversionTable.donationCurrency])
        put("anchorAsset", row[PriceOracleConversionTable.anchorAsset].name)
        put("anchorPrice", row[PriceOracleConversionTable.anchorPrice].toPlainString())
        put("ltrMinted", row[PriceOracleConversionTable.ltrMinted].toPlainString())
        put("priceStatus", row[PriceOracleConversionTable.priceStatus].name)
        put("sourceCount", row[PriceOracleConversionTable.sourceCount])
        put("sourcesUsed", row[PriceOracleConversionTable.sourcesUsed])
        put("priceTimestamp", row[PriceOracleConversionTable.priceTimestamp].toString())
        put("ltrLedgerEntryId", row[PriceOracleConversionTable.ltrLedgerEntryId].toString())
        put("memberId", row[PriceOracleConversionTable.memberId].toString())
        put("createdById", row[PriceOracleConversionTable.createdById]?.toString())
        put("createdAt", row[PriceOracleConversionTable.createdAt].toString())
    }
