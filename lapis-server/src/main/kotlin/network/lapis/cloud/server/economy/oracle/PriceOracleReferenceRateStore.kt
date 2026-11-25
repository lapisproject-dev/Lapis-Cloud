package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.shared.domain.AnchorAsset
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** A manually confirmed reference rate (V1.9.324). */
internal data class ReferenceRateRow(
    val id: Uuid,
    val anchorAsset: AnchorAsset,
    val donationCurrency: String,
    val effectiveDate: LocalDate,
    val price: BigDecimal,
    val evidenceText: String,
    val confirmedBy: Uuid,
    val confirmedAt: LocalDateTime,
    val referenceSnapshotMedian: BigDecimal?,
    val deviationAcknowledged: Boolean,
)

/**
 * V1.9.324 -- the ONLY reader and writer of `price_oracle_reference_rate` (the DSGVO contributor is the one exception, it reads its own member's
 * rows). A confirmed rate is a booking basis for an LTR mint and therefore **immutable**: there is deliberately no update and no delete function
 * (a tripwire test pins that). A wrong rate is corrected by a new conversion, never by editing the old one.
 *
 * Both functions expect an already open transaction. A second confirmation for the same (anchor, currency, effective date) violates the unique
 * constraint `uq_price_oracle_reference_rate`; the caller maps that to a conflict outside its transaction.
 */
internal object PriceOracleReferenceRateStore {
    const val UNIQUE_CONSTRAINT_NAME = "uq_price_oracle_reference_rate"

    fun find(
        anchorAsset: AnchorAsset,
        donationCurrency: String,
        effectiveDate: LocalDate,
    ): ReferenceRateRow? =
        PriceOracleReferenceRateTable
            .selectAll()
            .where {
                (PriceOracleReferenceRateTable.anchorAsset eq anchorAsset) and
                    (PriceOracleReferenceRateTable.donationCurrency eq donationCurrency.uppercase()) and
                    (PriceOracleReferenceRateTable.effectiveDate eq effectiveDate)
            }.singleOrNull()
            ?.toRow()

    fun insert(row: ReferenceRateRow) {
        PriceOracleReferenceRateTable.insert {
            it[id] = row.id
            it[anchorAsset] = row.anchorAsset
            it[donationCurrency] = row.donationCurrency.uppercase()
            it[effectiveDate] = row.effectiveDate
            it[price] = row.price
            it[evidenceText] = row.evidenceText
            it[confirmedBy] = row.confirmedBy
            it[confirmedAt] = row.confirmedAt
            it[referenceSnapshotMedian] = row.referenceSnapshotMedian
            it[deviationAcknowledged] = row.deviationAcknowledged
        }
    }

    private fun ResultRow.toRow(): ReferenceRateRow =
        ReferenceRateRow(
            id = this[PriceOracleReferenceRateTable.id],
            anchorAsset = this[PriceOracleReferenceRateTable.anchorAsset],
            donationCurrency = this[PriceOracleReferenceRateTable.donationCurrency],
            effectiveDate = this[PriceOracleReferenceRateTable.effectiveDate],
            price = this[PriceOracleReferenceRateTable.price],
            evidenceText = this[PriceOracleReferenceRateTable.evidenceText],
            confirmedBy = this[PriceOracleReferenceRateTable.confirmedBy],
            confirmedAt = this[PriceOracleReferenceRateTable.confirmedAt],
            referenceSnapshotMedian = this[PriceOracleReferenceRateTable.referenceSnapshotMedian],
            deviationAcknowledged = this[PriceOracleReferenceRateTable.deviationAcknowledged],
        )
}
