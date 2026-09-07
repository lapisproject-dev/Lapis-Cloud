package network.lapis.cloud.server.accounting.export.lexoffice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val LEXOFFICE_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

/** `GET /v1/profile` response -- see `docs/architecture/accounting-export-lexoffice.adoc`
 * "Verified API facts" for the live-verified field list. Every field beyond [companyName] is
 * ignored ([ignoreUnknownKeys]). */
@Serializable
internal data class LexofficeProfileResponse(
    val organizationId: String,
    val companyName: String? = null,
)

/** One entry of `GET /v1/posting-categories`. */
@Serializable
internal data class LexofficePostingCategory(
    val id: String,
    val name: String,
    /** `"income"` or `"outgo"` -- see [network.lapis.cloud.server.accounting.export.lexoffice
     * .toDirection]. */
    val type: String,
    val contactRequired: Boolean = false,
    val splitAllowed: Boolean = false,
    val groupName: String? = null,
)

/** One `voucherItems[]` entry of `POST /v1/vouchers` -- see [LexofficeVoucherRequest] KDoc. */
@Serializable
internal data class LexofficeVoucherItem(
    val amount: String,
    val taxAmount: String,
    val taxRatePercent: Int,
    val categoryId: String,
)

/**
 * `POST /v1/vouchers` request body -- field set and shapes are LIVE-VERIFIED against
 * `https://developers.lexware.io/docs/#vouchers-endpoint-create-a-voucher` (see the adoc's
 * "Verified API facts" table for the retrieval date). Amounts travel as decimal-formatted STRINGS
 * (`"119.00"`), never a JSON number -- avoids any binary floating-point round-off on a monetary
 * value across the wire, same reasoning [network.lapis.cloud.server.payment.psp.StripeCheckoutClient]
 * gives for converting to Stripe's own integer minor-units instead of a raw `Double`.
 *
 * `voucherDate` is `date` format `yyyy-MM-dd` for this specific endpoint (confirmed against the
 * live "Create a Voucher" sample request) -- NOT `dateTime` the way `createdDate`/`updatedDate` (or
 * other lexoffice endpoints such as Invoices) are. `taxType` is always `"gross"` with every
 * [LexofficeVoucherItem.taxRatePercent] `0` -- Lapis Cloud carries no USt-Schlüssel at all (see
 * `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer`); `"net"` is deliberately never used
 * (the live docs additionally note lexoffice prohibits `net` combined with `voucherStatus:
 * "unchecked"`, which would be one more reason to avoid it even if 0%-net were otherwise viable).
 * `useCollectiveContact = true` always, `contactId`/`contactName` never sent -- no member/donor
 * Stammdaten cross the wire to lexoffice, ever.
 */
@Serializable
internal data class LexofficeVoucherRequest(
    val type: String,
    val voucherStatus: String,
    val voucherNumber: String,
    val voucherDate: String,
    val totalGrossAmount: String,
    val totalTaxAmount: String,
    val taxType: String,
    val useCollectiveContact: Boolean,
    val remark: String,
    val voucherItems: List<LexofficeVoucherItem>,
)

/** `POST /v1/vouchers` success response -- `201` body, only [id] is used. */
@Serializable
internal data class LexofficeVoucherResponse(
    val id: String? = null,
)

/** The "legacy error response" shape the live docs confirm the `vouchers` endpoint uses (also
 * `contacts`/`files`) -- NOT a Stripe-style `{"error": {"message": ...}}` envelope. Each
 * [LexofficeIssue] carries an `i18nKey` (never directly user-facing English text) plus a `source`
 * field path -- [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeApiClient] renders
 * a readable message by joining `source`/`i18nKey` pairs, since no single human-readable `message`
 * field exists in this shape. */
@Serializable
internal data class LexofficeLegacyErrorEnvelope(
    @SerialName("IssueList") val issueList: List<LexofficeIssue> = emptyList(),
)

@Serializable
internal data class LexofficeIssue(
    val i18nKey: String? = null,
    val source: String? = null,
    val type: String? = null,
)

/** The plain `{"message": "..."}` shape 401/500/504 responses use (see the live docs' "Authorization
 * and Connection Error Responses" table) -- distinct from [LexofficeLegacyErrorEnvelope]. */
@Serializable
internal data class LexofficeSimpleErrorEnvelope(
    val message: String? = null,
)
