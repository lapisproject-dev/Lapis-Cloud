package network.lapis.cloud.server.accounting.export.sevdesk

import network.lapis.cloud.server.accounting.export.AccountingExportProviderAdapter
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.server.accounting.export.VoucherPushOutcome
import network.lapis.cloud.shared.domain.AccountingExportProvider

/**
 * Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- thin [AccountingExportProviderAdapter] implementation
 * over [SevDeskApiClient], exactly the form [network.lapis.cloud.server.accounting.export
 * .lexoffice.LexofficeAdapter] establishes. Carries no logic of its own -- every actual rule
 * (mapping, classification, rate limiting) lives in
 * [SevDeskApiClient]/[SevDeskVoucherMapper]/[SevDeskRateLimiter].
 */
internal class SevDeskAdapter(
    private val client: SevDeskApiClient,
) : AccountingExportProviderAdapter {
    override val provider: AccountingExportProvider = AccountingExportProvider.SEVDESK

    override suspend fun testConnection(token: String): ConnectionTestOutcome = client.getBookkeepingSystemVersion(token)

    override suspend fun listCategories(token: String): CategoryListOutcome = client.listReceiptGuidance(token)

    override suspend fun pushVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome = client.createVoucher(token = token, voucher = voucher)
}
