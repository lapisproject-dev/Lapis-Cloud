package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.shared.domain.FinanceSetupStep
import network.lapis.cloud.shared.domain.FinanceSetupStepState
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.SetupAccountView
import network.lapis.cloud.shared.domain.StandardAssociationChart
import network.lapis.cloud.shared.domain.accepts
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * V1.9.206. Pins that the shared `accepts` rule (what the setup screen offers) and the status loader
 * (what the activation gate reads) agree with the server's own mapping validation: a mapping is DONE
 * exactly when `updateOrganizationSettings` would have accepted it. The consistency test also pins that
 * [DevSeedData]'s demo chart and [StandardAssociationChart] agree on every shared number.
 */
class FinanceSetupParityTest :
    FunSpec({
        val createdIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = null
                    it[contributionIncomeAccountId] = null
                }
                if (createdIds.isNotEmpty()) LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdIds }
            }
        }

        test("every type/active/cash combination: status DONE exactly when accepts() holds, for bank and contribution mapping") {
            for (type in LedgerAccountType.entries) {
                for (active in listOf(true, false)) {
                    for (cash in listOf(true, false)) {
                        if (cash && type != LedgerAccountType.ASSET) continue // forbidden by requireCashRegisterOnlyOnAsset
                        val id = Uuid.random()
                        createdIds += id
                        transaction {
                            LedgerAccountTable.insert {
                                it[LedgerAccountTable.id] = id
                                it[accountNumber] = Random.nextLong(100_000_000L, 999_999_999L).toString()
                                it[name] = "Parity $type $active $cash"
                                it[accountClass] = 1
                                it[LedgerAccountTable.type] = type
                                it[LedgerAccountTable.active] = active
                                it[isCashRegister] = cash
                            }
                        }
                        val view = SetupAccountView(id = id.toString(), number = "x", type = type, active = active, isCashRegister = cash)
                        for (
                        (step, expectedType) in
                        listOf(
                            FinanceSetupStep.BANK_ACCOUNT to LedgerAccountType.ASSET,
                            FinanceSetupStep.CONTRIBUTION_INCOME to LedgerAccountType.INCOME,
                        )
                        ) {
                            transaction {
                                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                                    it[paymentBankAccountId] = if (step == FinanceSetupStep.BANK_ACCOUNT) id else null
                                    it[contributionIncomeAccountId] = if (step == FinanceSetupStep.CONTRIBUTION_INCOME) id else null
                                }
                            }
                            val serverAccepts = active && !cash && type == expectedType
                            step.accepts(view) shouldBe serverAccepts
                            val state = transaction { loadFinanceSetupStatusInTx() }.stateOf(step)
                            (state == FinanceSetupStepState.DONE) shouldBe serverAccepts
                        }
                    }
                }
            }
        }

        test("demo chart and standard chart agree on class, type and flags for every shared number") {
            val demo = DevSeedData.demoLedgerAccounts.associateBy { it.accountNumber }
            for (std in StandardAssociationChart.accounts) {
                val d = demo[std.accountNumber] ?: continue
                "${std.accountNumber}:${d.accountClass}:${d.type}:${d.isCashRegister}:${d.reserveType}" shouldBe
                    "${std.accountNumber}:${std.accountClass}:${std.type}:${std.isCashRegister}:${std.reserveType}"
            }
        }
    })
