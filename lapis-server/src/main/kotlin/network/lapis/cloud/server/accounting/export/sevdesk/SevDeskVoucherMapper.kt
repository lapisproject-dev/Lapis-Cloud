package network.lapis.cloud.server.accounting.export.sevdesk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonUnquotedLiteral
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.shared.domain.AccountingExportDirection
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- the ONLY place an [OutboundVoucher] is turned into
 * sevDesk's own [SevDeskSaveVoucherRequest] JSON shape. See [SevDeskVoucher]/[SevDeskVoucherPos]
 * KDoc for the live-verified field values this mapping relies on.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SevDeskVoucherMapper {
    private const val OBJECT_NAME_VOUCHER = "Voucher"
    private const val OBJECT_NAME_VOUCHER_POS = "VoucherPos"
    private const val OBJECT_NAME_ACCOUNT_DATEV = "AccountDatev"
    private const val OBJECT_NAME_TAX_RULE = "TaxRule"
    private const val VOUCHER_TYPE = "VOU"

    /**
     * `status = 50` (draft) -- a DELIBERATE departure from the lexoffice path's `voucherStatus =
     * "open"`. The `saveVoucher` endpoint description live-verified against the spec states: "The
     * only valid status values for this endpoint are 50 (draft) and 100 (open). You can only
     * update draft vouchers." A draft is changeable/deletable in the target system; `100` is only
     * reversible via an explicit `resetToDraft` call this wave does not implement. This codebase
     * has repeatedly documented (`LexofficeApiClient`, `resolveUnknownItem`,
     * `AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS`) that a live push is otherwise
     * unwitherable once sent -- when a provider offers a "return ticket", it is taken. The
     * `LexofficeVoucherMapper`'s own KDoc left the analogous question open for the operator to
     * revisit later; here, for sevDesk, it is answered: `50`. If the operator's Steuerberater
     * workflow prefers vouchers to arrive already "open" (`100`), this is a one-constant change --
     * see the plan's own "Offene Frage (3)".
     */
    private const val VOUCHER_STATUS_DRAFT = 50

    /** Constant, no personal reference -- fills a field the live sevDesk UI would otherwise show
     * empty, WITHOUT ever sending member/donor Stammdaten (see [SevDeskVoucher.supplier] KDoc
     * "ALWAYS null"). Wording confirmed with the operator per the plan's "Offene Frage (4)". */
    private const val COLLECTIVE_SUPPLIER_NAME = "Sammelbuchung Lapis Cloud"

    sealed interface MappingResult {
        data class Ok(
            val request: SevDeskSaveVoucherRequest,
        ) : MappingResult

        /** [externalCategoryId] failed to parse as `"<accountDatevId>:<taxRuleId>"` -- see
         * [network.lapis.cloud.server.accounting.export.sevdesk.SevDeskApiClient.listReceiptGuidance]
         * KDoc for why the id is composite. A PERSISTED value that no longer parses (e.g. a
         * category mapping created before a format change, or a hand-edited row) must never crash
         * the poller tick -- it is reported as an ordinary [network.lapis.cloud.server.accounting
         * .export.VoucherPushOutcome.Rejected], exactly like any other business rejection, WITHOUT a
         * single HTTP request ever leaving this process. */
        data class Unmappable(
            val errorCode: String,
            val message: String,
        ) : MappingResult
    }

    fun toRequest(voucher: OutboundVoucher): MappingResult {
        val (accountDatevId, taxRuleId) =
            parseExternalCategoryId(voucher.externalCategoryId)
                ?: return MappingResult.Unmappable(
                    errorCode = "INVALID_CATEGORY_MAPPING",
                    message = "Die Kontenzuordnung für diesen Beleg ist ungültig -- bitte das Konto erneut zuordnen.",
                )
        val creditDebit =
            when (voucher.direction) {
                // Live-verified 2026-09-07 against openapi.yaml: "D" (debit) = "You sold
                // something" = Einnahme; "C" (credit) = "You bought something" = Ausgabe. The
                // naive "C wie Credit wie Einnahme" reading is WRONG and would book every donation
                // as an expense.
                AccountingExportDirection.INCOME -> "D"
                AccountingExportDirection.EXPENSE -> "C"
            }
        val amount = voucher.grossAmount.toSevDeskAmount()
        val sevDeskVoucher =
            SevDeskVoucher(
                objectName = OBJECT_NAME_VOUCHER,
                mapAll = true,
                voucherType = VOUCHER_TYPE,
                status = VOUCHER_STATUS_DRAFT,
                creditDebit = creditDebit,
                // dd.MM.yyyy -- live-verified against the spec's own `example: 01.01.2022`, NOT
                // the ISO-8601 `yyyy-MM-dd` the lexoffice endpoint uses.
                voucherDate = voucher.voucherDate.toSevDeskDateString(),
                description = voucher.voucherNumber,
                supplier = null,
                supplierName = COLLECTIVE_SUPPLIER_NAME,
                taxRule = SevDeskObjectRef(id = taxRuleId.toString(), objectName = OBJECT_NAME_TAX_RULE),
            )
        val sevDeskVoucherPos =
            SevDeskVoucherPos(
                objectName = OBJECT_NAME_VOUCHER_POS,
                mapAll = true,
                accountDatev = SevDeskObjectRef(id = accountDatevId.toString(), objectName = OBJECT_NAME_ACCOUNT_DATEV),
                taxRate = ZERO_TAX_RATE,
                net = false,
                sumGross = amount,
                sumNet = amount,
                // Deliberately NEVER the journal entry's own free-text description -- same
                // DSGVO-motivated rule the lexoffice path's `remark` field already follows.
                comment = voucher.remark,
            )
        return MappingResult.Ok(
            SevDeskSaveVoucherRequest(
                voucher = sevDeskVoucher,
                voucherPosSave = listOf(sevDeskVoucherPos),
            ),
        )
    }

    /** `externalCategoryId` is `"<accountDatevId>:<taxRuleId>"` -- see
     * [network.lapis.cloud.server.accounting.export.sevdesk.SevDeskApiClient.listReceiptGuidance]
     * KDoc for why this composite key exists. Returns `null` (never throws) on any malformed
     * input -- see [MappingResult.Unmappable]. */
    private fun parseExternalCategoryId(externalCategoryId: String): Pair<Int, Int>? {
        val parts = externalCategoryId.split(":")
        if (parts.size != 2) return null
        val accountDatevId = parts[0].toIntOrNull() ?: return null
        val taxRuleId = parts[1].toIntOrNull() ?: return null
        return accountDatevId to taxRuleId
    }

    private fun kotlinx.datetime.LocalDate.toSevDeskDateString(): String =
        "%02d.%02d.%04d".format(java.util.Locale.ROOT, this.dayOfMonth, this.monthNumber, this.year)

    private val ZERO_TAX_RATE: JsonElement = JsonUnquotedLiteral("0")

    /**
     * The amount travels as a JSON NUMBER on the wire (sevDesk declares `sumGross`/`sumNet` as
     * `type: number, format: float`), but was NEVER, at any point in this process, a Kotlin
     * `Double`: [JsonUnquotedLiteral] inserts the EXACT decimal string of the [BigDecimal]
     * unquoted into the JSON output. A `.toDouble()` here would push a monetary amount through an
     * IEEE-754 needle's eye -- see [network.lapis.cloud.server.accounting.export.lexoffice
     * .LexofficeVoucherRequest] KDoc for the equivalent reasoning that led the lexoffice path to
     * carry amounts as decimal-formatted STRINGS instead (not an option here: the sevDesk spec
     * requires an actual JSON number, not a string). **Do not "simplify" this to `.toDouble()`.**
     */
    private fun BigDecimal.toSevDeskAmount(): JsonElement = JsonUnquotedLiteral(this.setScale(2, RoundingMode.UNNECESSARY).toPlainString())
}
