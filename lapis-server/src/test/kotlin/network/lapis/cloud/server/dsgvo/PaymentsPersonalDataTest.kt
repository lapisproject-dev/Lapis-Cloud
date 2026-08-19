package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Security Round 1 (2026-08-19, SHOULD-2) regression coverage: [PaymentsPersonalData.export] must
 * carry `reconciliationNote`/`payerReference`/`feeAmount`/`currency`/`providerPaymentId`, not just
 * the six fields the pre-fix export had -- see that object's class KDoc "DSGVO Art. 15 export/erase
 * symmetry" for the full rationale (a data subject could otherwise have `reconciliation_note`
 * erased about them without ever having been able to see it via their own Art. 15 export first).
 * No V1.2.1 code path writes `payment_transaction` rows yet (webhook ingestion is V1.2.4, see
 * `33-payments.kuml.kts` file header) -- this test inserts a row directly, same "own freshly
 * created fixtures, direct table inserts for setup" house style [ContributionPostingBridgeTest]/
 * [network.lapis.cloud.server.rpc.ContributionPaymentRpcTest] both already establish.
 */
class PaymentsPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            if (createdMemberIds.isEmpty()) return@afterSpec
            transaction {
                PaymentTransactionTable.deleteWhere { PaymentTransactionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PaymentsPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertTransaction(memberId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                PaymentTransactionTable.insert {
                    it[PaymentTransactionTable.id] = id
                    it[provider] = PaymentProvider.STRIPE
                    it[providerEventId] = "evt-${Uuid.random()}"
                    it[providerPaymentId] = "pi-secret-reference-${Uuid.random()}"
                    it[status] = PaymentTransactionStatus.CAPTURED
                    it[amount] = BigDecimal("42.00")
                    it[currency] = "EUR"
                    it[feeAmount] = BigDecimal("1.23")
                    it[intent] = PaymentIntent.CONTRIBUTION
                    it[contributionId] = null
                    it[PaymentTransactionTable.memberId] = memberId
                    it[payerReference] = "payer-ref-${Uuid.random()}"
                    it[receivedAt] = LocalDateTime(2026, 4, 1, 10, 0)
                    it[reconciledAt] = null
                    it[reconciledBy] = null
                    it[journalEntryId] = null
                    it[reconciliationNote] = "Treasurer remark about another member -- must be cleared on erase"
                    it[rawPayloadDigest] = "0".repeat(64)
                }
            }
            return id
        }

        test("export includes providerPaymentId/currency/feeAmount/payerReference/reconciliationNote (SHOULD-2)") {
            val member = createTestMember("payments-pd-export@example.org")
            insertTransaction(member)

            val export = transaction { PaymentsPersonalData.export(member) }
            val transactions = export.jsonObject.getValue("paymentTransactions").jsonArray
            transactions.size shouldBe 1
            val entry = transactions.single().jsonObject
            entry
                .getValue("providerPaymentId")
                .jsonPrimitive.content
                .startsWith("pi-secret-reference-") shouldBe true
            entry.getValue("currency").jsonPrimitive.content shouldBe "EUR"
            entry.getValue("feeAmount").jsonPrimitive.content shouldBe "1.23"
            entry
                .getValue("payerReference")
                .jsonPrimitive.content
                .startsWith("payer-ref-") shouldBe true
            entry.getValue("reconciliationNote").jsonPrimitive.content shouldBe
                "Treasurer remark about another member -- must be cleared on erase"
        }

        test("erase clears reconciliationNote but retains payerReference/providerPaymentId/the row itself (GoBD retention)") {
            val member = createTestMember("payments-pd-erase@example.org")
            val transactionId = insertTransaction(member)

            val outcomes = transaction { PaymentsPersonalData.erase(memberId = member, mode = ErasureMode.ANONYMIZE) }
            outcomes.single { it.table == "payment_transaction" }.rowsRetained shouldBe 1

            transaction {
                val row = PaymentTransactionTable.selectAll().where { PaymentTransactionTable.id eq transactionId }.single()
                row[PaymentTransactionTable.reconciliationNote] shouldBe null
                row[PaymentTransactionTable.payerReference]?.startsWith("payer-ref-") shouldBe true
                row[PaymentTransactionTable.providerPaymentId].startsWith("pi-secret-reference-") shouldBe true
            }

            // export afterward must reflect the cleared field, not the pre-erasure value.
            val exportAfterErase = transaction { PaymentsPersonalData.export(member) }
            val entryAfterErase =
                exportAfterErase.jsonObject
                    .getValue("paymentTransactions")
                    .jsonArray
                    .single()
                    .jsonObject
            entryAfterErase["reconciliationNote"].toString() shouldBe "null"
        }
    })
