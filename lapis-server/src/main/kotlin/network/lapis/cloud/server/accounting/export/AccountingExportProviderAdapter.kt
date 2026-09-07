package network.lapis.cloud.server.accounting.export

import network.lapis.cloud.shared.domain.AccountingExportDirection
import network.lapis.cloud.shared.domain.AccountingExportProvider
import java.math.BigDecimal
import kotlin.time.Duration

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- the provider-neutral seam
 * `network.lapis.cloud.server.accounting.export.lexoffice.LexofficeAdapter` implements. Welle
 * V1.4.5.4 "sevDesk-Live-Anbindung" adds a second implementation,
 * `network.lapis.cloud.server.accounting.export.sevdesk.SevDeskAdapter`, WITHOUT touching
 * `AccountingExportPoller`/`AccountingExportService`/`AccountingExportPlanner` at all -- every one
 * of those three talks ONLY to this interface, never to a concrete provider's own wire types.
 *
 * Deliberately narrow: three operations, matching exactly the three lexoffice endpoints that wave
 * calls (`GET /v1/profile`, `GET /v1/posting-categories`, `POST /v1/vouchers`) -- the sevDesk
 * adapter expresses itself in the SAME three-operation shape (`GET
 * /Tools/bookkeepingSystemVersion`, `GET /ReceiptGuidance/for{Revenue,Expense}`, `POST
 * /Voucher/Factory/saveVoucher`) rather than reintroducing provider-specific plumbing elsewhere.
 * No generic "call any endpoint" escape hatch exists, and a THIRD provider is expected to fit the
 * same shape too.
 */
internal interface AccountingExportProviderAdapter {
    val provider: AccountingExportProvider

    /** A real API call against [token] -- returns the provider's own organization/company name on
     * success (see [ConnectionTestOutcome.Success]) so the UI can show "Verbunden mit: ...". */
    suspend fun testConnection(token: String): ConnectionTestOutcome

    /** The catalogue [network.lapis.cloud.server.rpc.AccountingExportService.mapAccount] maps
     * against. */
    suspend fun listCategories(token: String): CategoryListOutcome

    /** Sends exactly ONE voucher. Never a batch -- the provider's rate limit is per-request, and
     * batching would only move the throttling problem, not remove it. */
    suspend fun pushVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome
}

internal sealed interface ConnectionTestOutcome {
    data class Success(
        val companyName: String?,
    ) : ConnectionTestOutcome

    data class Failure(
        val errorCode: String,
        val message: String,
    ) : ConnectionTestOutcome
}

internal data class ExternalCategory(
    val id: String,
    val name: String,
    val groupName: String?,
    val direction: AccountingExportDirection,
)

internal sealed interface CategoryListOutcome {
    data class Success(
        val categories: List<ExternalCategory>,
    ) : CategoryListOutcome

    data class Failure(
        val errorCode: String,
        val message: String,
    ) : CategoryListOutcome
}

/**
 * A single voucher to push -- already fully resolved by `AccountingExportPlanner` (deterministic
 * [voucherNumber], [direction], [externalCategoryId] from `accounting_export_category_map`). Every
 * field here is provider-neutral; [network.lapis.cloud.server.accounting.export.lexoffice
 * .LexofficeVoucherMapper] is the ONLY place this gets turned into lexoffice's own JSON shape.
 *
 * [remark] is deliberately NEVER the journal entry's own `description` free text -- see
 * `AccountingExportPoller` class KDoc "DSGVO" for why (the same donor-naming concern
 * `network.lapis.cloud.server.routes.DatevRoutes.DATEV_FILE_DOWNLOAD_ROLES` already documents for
 * the DATEV file path applies doubly here: a live push is unwitherable once sent).
 */
internal data class OutboundVoucher(
    val voucherDate: kotlinx.datetime.LocalDate,
    val voucherNumber: String,
    val direction: AccountingExportDirection,
    val grossAmount: BigDecimal,
    val externalCategoryId: String,
    val remark: String,
)

internal sealed interface VoucherPushOutcome {
    data class Succeeded(
        val externalVoucherId: String,
    ) : VoucherPushOutcome

    /** Fachlich abgelehnt -- never retried. */
    data class Rejected(
        val errorCode: String,
        val message: String,
    ) : VoucherPushOutcome

    /** 429 and 503 ONLY -- both mean "not processed", so a resend is safe within the attempt
     * budget. Security review Fund 2026-09-07 (Runde 5): this KDoc used to also say "5xx/timeout",
     * which stopped being true once [network.lapis.cloud.server.accounting.export.lexoffice
     * .LexofficeApiClient.createVoucher] was fixed to classify every OTHER 5xx (500/502/504/...)
     * and every ambiguous post-send/timeout [java.io.IOException] as [Indeterminate] instead --
     * see that method's own KDoc "Klassifikation"/"Runde 5" for the full rationale (a resend risks
     * a genuine duplicate voucher, `POST /v1/vouchers` has no idempotency key). A future provider
     * adapter (sevDesk, V1.4.5.4) implementing this interface MUST follow the same "only a failure
     * GUARANTEED to have happened before any byte of the request left this process is Retryable"
     * rule -- do not resurrect a blanket "5xx/timeout -> Retryable" mapping here. */
    data class Retryable(
        val errorCode: String,
        val message: String,
        val retryAfter: Duration?,
    ) : VoucherPushOutcome

    /** Network failure AFTER the request left this server -- outcome at the provider is unknown,
     * never retried (see `AccountingExportPoller` class KDoc "Klassifikation"). */
    data class Indeterminate(
        val errorCode: String,
    ) : VoucherPushOutcome
}
