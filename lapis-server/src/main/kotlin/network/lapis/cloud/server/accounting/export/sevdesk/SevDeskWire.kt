package network.lapis.cloud.server.accounting.export.sevdesk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val SEVDESK_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

/** `GET /Tools/bookkeepingSystemVersion` response, wrapped in the usual `{"objects": ...}`
 * envelope -- see [SevDeskObjectsEnvelope]. `"1.0"`/`"2.0"` are the only documented values; only
 * `"2.0"` exposes `accountDatev`, which [SevDeskVoucher]/[SevDeskVoucherPos] both require. */
@Serializable
internal data class SevDeskBookkeepingVersion(
    val version: String? = null,
)

/** One entry of `GET /ReceiptGuidance/forRevenue` / `GET /ReceiptGuidance/forExpense`. */
@Serializable
internal data class SevDeskReceiptGuide(
    val accountDatevId: Int? = null,
    val accountNumber: String? = null,
    val accountName: String? = null,
    val description: String? = null,
    val allowedTaxRules: List<SevDeskAllowedTaxRule> = emptyList(),
)

@Serializable
internal data class SevDeskAllowedTaxRule(
    val id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val taxRates: List<String> = emptyList(),
)

/** A sevDesk "object reference" -- the `{id, objectName}` shape every relation (`accountDatev`,
 * `taxRule`, `supplier`) uses on this endpoint family. */
@Serializable
internal data class SevDeskObjectRef(
    val id: String,
    val objectName: String,
)

/**
 * `voucher` half of `POST /Voucher/Factory/saveVoucher` -- field set live-verified against
 * `https://api.sevdesk.de/openapi.yaml` (`Model_Voucher`, retrieved 2026-09-07). See
 * `docs/architecture/accounting-export-sevdesk.adoc` "Verified API facts" for the full table.
 *
 * [creditDebit]: `"D"` (debit = "You sold something") = Einnahme, `"C"` (credit = "You bought
 * something") = Ausgabe -- deliberately the OPPOSITE of the naive "C wie Credit wie Einnahme"
 * reading, see [network.lapis.cloud.server.accounting.export.sevdesk.SevDeskVoucherMapper] KDoc.
 *
 * [status]: `50` (draft) only -- see [SevDeskVoucherMapper] KDoc "status = 50" for why this wave
 * deliberately does NOT use `100` (open), the other value the endpoint documents as writable.
 *
 * [voucherDate]: `dd.MM.yyyy`, NOT ISO-8601 (the spec's own `example: 01.01.2022` confirms this
 * -- distinct from the lexoffice endpoint, which uses `yyyy-MM-dd`).
 *
 * [supplier] is ALWAYS `null` here -- Datensparsamkeit, no member/donor Stammdaten ever cross the
 * wire to sevDesk, same posture [network.lapis.cloud.server.accounting.export.OutboundVoucher]
 * KDoc documents for lexoffice's `useCollectiveContact`. [supplierName] is a constant string with
 * no personal reference, filling the field the live UI would otherwise show empty.
 *
 * `taxType`/`taxSet`/`accountingType` are deliberately NEVER sent -- those are "Buchhaltung 1.0"
 * fields, excluded by the bookkeeping-version gate in `SevDeskApiClient.listReceiptGuidance`.
 */
@Serializable
internal data class SevDeskVoucher(
    val objectName: String,
    val mapAll: Boolean,
    val voucherType: String,
    val status: Int,
    val creditDebit: String,
    val voucherDate: String,
    val description: String,
    // No default value (unlike every other nullable field in this file) -- kotlinx.serialization's
    // `encodeDefaults = false` (this project's SEVDESK_JSON default) OMITS a property from the wire
    // entirely when its value equals its declared default, which for `= null` would silently drop
    // `"supplier":null` from the request body. sevDesk's own docs say "you can set this object to
    // null" -- the KEY must still be present. Every call site (SevDeskVoucherMapper) already passes
    // `supplier = null` explicitly, so removing the default changes nothing at the call site and
    // fixes the wire shape. Regression-guarded by SevDeskApiClientTest's own body-shape assertion.
    val supplier: SevDeskObjectRef?,
    val supplierName: String,
    val taxRule: SevDeskObjectRef,
)

/**
 * `voucherPos` half of `POST /Voucher/Factory/saveVoucher` -- field set live-verified against
 * `Model_VoucherPos` in the same spec. Exactly ONE of these travels per voucher (same "single
 * line" posture the lexoffice path establishes).
 *
 * [net] is `false` -- `sumGross` governs (the spec: "Determines whether 'sumNet' or 'sumGross' is
 * regarded"). [sumGross]/[sumNet]/[taxRate] carry raw [kotlinx.serialization.json.JsonElement]
 * (unquoted numeric literal), NEVER a Kotlin `Double` -- see [SevDeskVoucherMapper
 * .toSevDeskAmount] KDoc for why a `.toDouble()` here would be a real, not merely cosmetic, bug.
 *
 * [comment] is deliberately [network.lapis.cloud.server.accounting.export.OutboundVoucher.remark],
 * NEVER the journal entry's own free-text `description` -- same DSGVO-motivated rule the
 * lexoffice path's `remark` field already follows.
 */
@Serializable
internal data class SevDeskVoucherPos(
    val objectName: String,
    val mapAll: Boolean,
    val accountDatev: SevDeskObjectRef,
    val taxRate: kotlinx.serialization.json.JsonElement,
    val net: Boolean,
    val sumGross: kotlinx.serialization.json.JsonElement,
    val sumNet: kotlinx.serialization.json.JsonElement,
    val comment: String,
)

/**
 * `POST /Voucher/Factory/saveVoucher` request body. **Spec inconsistency, live-verified
 * 2026-09-07**: `saveVoucher.required` lists `[voucher, voucherPos]`, but the ACTUAL request
 * schema only defines a `voucherPosSave` property -- `voucherPos` does not exist as a property at
 * all (`openapi.yaml` around the `saveVoucher` operation). This class follows the real property
 * name, `voucherPosSave`, not the `required` list.
 */
@Serializable
internal data class SevDeskSaveVoucherRequest(
    val voucher: SevDeskVoucher,
    val voucherPosSave: List<SevDeskVoucherPos>,
)

/** Nested `voucher` object inside [SevDeskSaveVoucherResponse] -- `Model_VoucherResponse.id` is
 * `type: string` in the spec. */
@Serializable
internal data class SevDeskVoucherResponse(
    val id: String? = null,
)

/**
 * `POST /Voucher/Factory/saveVoucher` success response. **Spec inconsistency, live-verified
 * 2026-09-07**: the spec documents `saveVoucherResponse` UNGWRAPPED (`{voucher, voucherPos,
 * filename}`), unlike every other sevDesk endpoint in this file which wraps in
 * `{"objects": ...}` -- [network.lapis.cloud.server.accounting.export.sevdesk.SevDeskApiClient
 * .createVoucher] therefore tries BOTH shapes (see that method's own KDoc).
 */
@Serializable
internal data class SevDeskSaveVoucherResponse(
    val voucher: SevDeskVoucherResponse? = null,
)

/** Generic `{"objects": ...}` envelope every `GET` endpoint used here wraps its payload in (except
 * [SevDeskSaveVoucherResponse] -- see that class' own KDoc for the spec inconsistency). */
@Serializable
internal data class SevDeskObjectsEnvelope<T>(
    val objects: T,
)

/** `422` validation-error body shape -- `{"error": {"message": ..., "exceptionUUID": ...}}`. */
@Serializable
internal data class SevDeskValidationError(
    val error: SevDeskValidationErrorBody? = null,
)

@Serializable
internal data class SevDeskValidationErrorBody(
    val message: String? = null,
    val exceptionUUID: String? = null,
)
