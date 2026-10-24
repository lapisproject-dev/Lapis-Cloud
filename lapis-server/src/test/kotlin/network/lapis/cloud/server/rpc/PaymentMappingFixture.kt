package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.shared.domain.LedgerAccountType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * V1.9.206: `enablePaymentGateway` now refuses to switch online payment on while the finance setup is
 * incomplete. Tests that need an enabled gateway call [seedComplete] first (fresh accounts with random
 * numbers, mapping pointed at them) and [clear] afterwards, so the shared test database is left without
 * a stale mapping.
 */
internal object PaymentMappingFixture {
    /** Creates bank/contribution/donation/event accounts, maps them, returns their ids for [clear]. */
    fun seedComplete(): List<Uuid> {
        val types =
            listOf(LedgerAccountType.ASSET, LedgerAccountType.INCOME, LedgerAccountType.INCOME, LedgerAccountType.INCOME)
        val ids = types.map { Uuid.random() }
        transaction {
            types.forEachIndexed { i, type ->
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = ids[i]
                    it[accountNumber] = Random.nextLong(100_000_000L, 999_999_999L).toString()
                    it[name] = "Fixture mapping account $i"
                    it[accountClass] = if (type == LedgerAccountType.ASSET) 1 else 4
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[isCashRegister] = false
                }
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentBankAccountId] = ids[0]
                it[contributionIncomeAccountId] = ids[1]
                it[donationIncomeAccountId] = ids[2]
                it[eventIncomeAccountId] = ids[3]
            }
        }
        return ids
    }

    fun clear(ids: Collection<Uuid>) {
        if (ids.isEmpty()) return
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentBankAccountId] = null
                it[contributionIncomeAccountId] = null
                it[donationIncomeAccountId] = null
                it[eventIncomeAccountId] = null
            }
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList ids }
        }
    }
}
