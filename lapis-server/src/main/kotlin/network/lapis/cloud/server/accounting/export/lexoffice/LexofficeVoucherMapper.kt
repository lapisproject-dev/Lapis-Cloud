package network.lapis.cloud.server.accounting.export.lexoffice

import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.shared.domain.AccountingExportDirection

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- the ONLY place an [OutboundVoucher] is turned into
 * lexoffice's own [LexofficeVoucherRequest] JSON shape. See [LexofficeVoucherRequest] KDoc for the
 * live-verified field values this mapping relies on (`taxType = "gross"`, `voucherDate` as a plain
 * `date`, `voucherStatus = "open"`).
 */
internal object LexofficeVoucherMapper {
    /**
     * `voucherStatus = "open"` -- a deliberate implementation choice, not the only writable value:
     * the live docs confirm BOTH `"open"` and `"unchecked"` are writable via this endpoint.
     * `"unchecked"` (= submitted for review, most mandatory fields waived) would arguably be the
     * more honest status for an automated import from a foreign system, letting the Steuerberater
     * see at a glance that a voucher arrived unreviewed -- but `"open"` is this wave's design
     * choice (see the plan's own §11.2, left open for the operator to revisit) and is unconditionally
     * safe here regardless of that choice, because every voucher this mapper produces already
     * carries every field `"open"` requires (no partial/incomplete vouchers are ever sent -- see
     * `AccountingExportPlanner`, which blocks an entry entirely rather than sending it half-mapped).
     */
    private const val VOUCHER_STATUS = "open"

    /** `taxType = "gross"` with every line's `taxRatePercent = 0` -- Lapis Cloud carries no
     * USt-Schlüssel at all (see `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer`).
     * `"net"`/`"vatfree"` are never used for this endpoint (the live "Voucher Properties" table
     * only documents `net`/`gross` as valid `taxType` values for `POST /v1/vouchers` -- `vatfree`
     * exists only on OTHER lexoffice endpoints such as Invoices/Credit Notes, confirmed against the
     * live docs, not this one). */
    private const val TAX_TYPE = "gross"
    private const val ZERO_TAX_RATE_PERCENT = 0

    fun toRequest(voucher: OutboundVoucher): LexofficeVoucherRequest {
        val type =
            when (voucher.direction) {
                AccountingExportDirection.INCOME -> "salesinvoice"
                AccountingExportDirection.EXPENSE -> "purchaseinvoice"
            }
        val amountString = voucher.grossAmount.toLexofficeAmountString()
        val zeroTaxAmount =
            java.math.BigDecimal.ZERO
                .toLexofficeAmountString()
        return LexofficeVoucherRequest(
            type = type,
            voucherStatus = VOUCHER_STATUS,
            voucherNumber = voucher.voucherNumber,
            // yyyy-MM-dd -- kotlinx.datetime.LocalDate.toString() already produces exactly this
            // ISO-8601 format, live-verified against the "Create a Voucher" sample request.
            voucherDate = voucher.voucherDate.toString(),
            totalGrossAmount = amountString,
            totalTaxAmount = zeroTaxAmount,
            taxType = TAX_TYPE,
            useCollectiveContact = true,
            // Deliberately NEVER journal_entry.description -- see OutboundVoucher KDoc "remark".
            remark = voucher.remark,
            voucherItems =
                listOf(
                    LexofficeVoucherItem(
                        amount = amountString,
                        taxAmount = zeroTaxAmount,
                        taxRatePercent = ZERO_TAX_RATE_PERCENT,
                        categoryId = voucher.externalCategoryId,
                    ),
                ),
        )
    }
}
