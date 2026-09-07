package network.lapis.cloud.server.accounting.export.lexoffice

import network.lapis.cloud.server.accounting.export.AccountingExportProviderAdapter
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.server.accounting.export.VoucherPushOutcome
import network.lapis.cloud.shared.domain.AccountingExportProvider

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- thin [AccountingExportProviderAdapter]
 * implementation over [LexofficeApiClient]. Carries no logic of its own beyond the three method
 * delegations -- every actual rule (mapping, classification, rate limiting) lives in
 * [LexofficeApiClient]/[LexofficeVoucherMapper]/[LexofficeRateLimiter], so a future sevDesk adapter
 * (V1.4.5.4) is not tempted to copy logic out of this file instead of writing its own equivalent
 * trio.
 */
internal class LexofficeAdapter(
    private val client: LexofficeApiClient,
) : AccountingExportProviderAdapter {
    override val provider: AccountingExportProvider = AccountingExportProvider.LEXOFFICE

    override suspend fun testConnection(token: String): ConnectionTestOutcome = client.getProfile(token)

    override suspend fun listCategories(token: String): CategoryListOutcome = client.listPostingCategories(token)

    override suspend fun pushVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome = client.createVoucher(token = token, voucher = voucher)
}
