package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.SetupAccountView
import network.lapis.cloud.shared.domain.financeSetupStatusOf
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * V1.9.206. Reads the finance-setup state (shared rules in `financeSetupStatusOf`) from the database.
 * Must run inside a `transaction {}`; shared by `AccountingService.getFinanceSetupStatus` and the
 * activation gate in `PaymentGatewayService.enablePaymentGateway`, so both always agree.
 */
internal fun loadFinanceSetupStatusInTx(): FinanceSetupStatusDto {
    val accounts =
        LedgerAccountTable
            .selectAll()
            .orderBy(LedgerAccountTable.accountNumber, SortOrder.ASC)
            .map {
                SetupAccountView(
                    id = it[LedgerAccountTable.id].toString(),
                    number = it[LedgerAccountTable.accountNumber],
                    type = it[LedgerAccountTable.type],
                    active = it[LedgerAccountTable.active],
                    isCashRegister = it[LedgerAccountTable.isCashRegister],
                )
            }
    val settings =
        OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
    return financeSetupStatusOf(
        accounts = accounts,
        bankId = settings[OrganizationSettingsTable.paymentBankAccountId]?.toString(),
        contributionId = settings[OrganizationSettingsTable.contributionIncomeAccountId]?.toString(),
        donationId = settings[OrganizationSettingsTable.donationIncomeAccountId]?.toString(),
        eventId = settings[OrganizationSettingsTable.eventIncomeAccountId]?.toString(),
        feeId = settings[OrganizationSettingsTable.paymentFeeAccountId]?.toString(),
    )
}
