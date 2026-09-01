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
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SepaReturnTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaSequenceType
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
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdBatchIds = mutableListOf<Uuid>()
        val createdMandateIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdBatchIds.isNotEmpty()) {
                    val itemIds =
                        SepaDebitItemTable.selectAll().where { SepaDebitItemTable.batchId inList createdBatchIds }.map {
                            it[SepaDebitItemTable.id]
                        }
                    if (itemIds.isNotEmpty()) SepaReturnTable.deleteWhere { SepaReturnTable.debitItemId inList itemIds }
                    SepaDebitItemTable.deleteWhere { SepaDebitItemTable.batchId inList createdBatchIds }
                    SepaDebitBatchTable.deleteWhere { SepaDebitBatchTable.id inList createdBatchIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdMandateIds.isNotEmpty()) {
                    SepaMandateTable.deleteWhere { SepaMandateTable.id inList createdMandateIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentTransactionTable.deleteWhere { PaymentTransactionTable.memberId inList createdMemberIds }
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.memberId inList createdMemberIds }
                    SepaMandateTable.deleteWhere { SepaMandateTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
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

        // ════════════════════════════════════════════════════════════════
        // Review Round 1 (2026-08-19, M-1) -- export/erase symmetry for the four new V1.2.2 SEPA
        // tables. Before this extension, NO test exercised PaymentsPersonalData.export/.erase for
        // sepa_mandate/sepa_debit_batch/sepa_debit_item/sepa_return at all, despite the class KDoc
        // already documenting their coverage (added at implementation time, never regression-tested).
        // ════════════════════════════════════════════════════════════════

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "PaymentsPersonalData-Fixture Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun createFullSepaChain(memberId: Uuid): Uuid {
            val tier = createTier()
            val contributionId = Uuid.random()
            val mandateId = Uuid.random()
            val batchId = Uuid.random()
            val itemId = Uuid.random()
            val returnId = Uuid.random()
            val now = LocalDateTime(2026, 4, 1, 10, 0)
            transaction {
                ContributionTable.insert {
                    it[id] = contributionId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = ContributionStatus.RETURNED
                    it[ContributionTable.createdAt] = now
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tier
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[sepaMandateId] = null
                }
                SepaMandateTable.insert {
                    it[id] = mandateId
                    it[SepaMandateTable.memberId] = memberId
                    it[mandateReference] = "LC-PD-${mandateId.toString().take(8)}"
                    it[debtorName] = "PaymentsPersonalData Testkonto"
                    it[debtorIbanCiphertext] = "unused-ciphertext-$mandateId"
                    it[debtorIbanSetAt] = now
                    it[debtorIbanLast4] = "1234"
                    it[debtorBic] = null
                    it[signatureDate] = now.date
                    it[sequenceType] = SepaSequenceType.FRST
                    it[status] = SepaMandateStatus.REVOKED
                    it[grantedAt] = now
                    it[revokedAt] = now
                    it[revokedBy] = memberId
                    it[revocationReason] = "Remark about this member -- must be cleared on erase"
                    it[lastUsedAt] = null
                    it[lastDebitedAmount] = null
                    it[createdBy] = memberId
                }
                SepaDebitBatchTable.insert {
                    it[id] = batchId
                    it[messageId] = "LC-DD-PD-${batchId.toString().take(8)}"
                    it[paymentInfoId] = "LC-DD-PD-${batchId.toString().take(8)}-P1"
                    it[requestedCollectionDate] = now.date
                    it[sequenceType] = SepaSequenceType.RCUR
                    it[status] = SepaDebitBatchStatus.SUBMITTED
                    it[itemCount] = 1
                    it[totalAmount] = BigDecimal("50.00")
                    it[createdBy] = memberId
                    it[createdAt] = now
                    it[notifiedAt] = now
                    it[requiredNoticeDays] = 14
                    it[generatedAt] = now
                    it[generatedDocumentId] = null
                    it[prenotificationDocumentId] = null
                    it[submittedAt] = now
                    it[submittedNote] = "Submitted-note remark about this member -- must be cleared on erase"
                    it[settledAt] = null
                    it[cancelledAt] = null
                    it[cancellationReason] = "Cancellation-reason remark -- must be cleared on erase"
                }
                SepaDebitItemTable.insert {
                    it[id] = itemId
                    it[SepaDebitItemTable.batchId] = batchId
                    it[SepaDebitItemTable.contributionId] = contributionId
                    it[SepaDebitItemTable.mandateId] = mandateId
                    it[endToEndId] = contributionId.toString().replace("-", "").uppercase()
                    it[amount] = BigDecimal("50.00")
                    it[remittanceInformation] = "Testbeitrag"
                    it[status] = SepaDebitItemStatus.RETURNED
                    it[settleableAt] = null
                    it[journalEntryId] = null
                }
                SepaReturnTable.insert {
                    it[id] = returnId
                    it[debitItemId] = itemId
                    it[returnedAt] = now.date
                    it[reasonCode] = SepaReturnReason.MD01
                    it[reasonText] = "Reason-text remark about this member -- must be cleared on erase"
                    it[returnFee] = BigDecimal("3.00")
                    it[recordedBy] = memberId
                    it[recordedAt] = now
                }
            }
            createdContributionIds += contributionId
            createdMandateIds += mandateId
            createdBatchIds += batchId
            return memberId
        }

        test("export includes sepaMandates/sepaDebitBatches/sepaDebitItems/sepaReturns for the four new V1.2.2 tables (M-1)") {
            val member = createTestMember("payments-pd-sepa-export@example.org")
            createFullSepaChain(member)

            val export = transaction { PaymentsPersonalData.export(member) }

            val mandates = export.jsonObject.getValue("sepaMandates").jsonArray
            mandates.size shouldBe 1
            mandates
                .single()
                .jsonObject
                .getValue("mandateReference")
                .jsonPrimitive.content
                .startsWith("LC-PD-") shouldBe true
            mandates
                .single()
                .jsonObject
                .getValue("revocationReason")
                .jsonPrimitive.content shouldBe
                "Remark about this member -- must be cleared on erase"
            // debtor_iban_ciphertext is NEVER exported -- neither the field name nor any IBAN fragment appears.
            mandates.single().jsonObject.containsKey("debtorIbanCiphertext") shouldBe false

            val batches = export.jsonObject.getValue("sepaDebitBatches").jsonArray
            batches.size shouldBe 1
            batches
                .single()
                .jsonObject
                .getValue("submittedNote")
                .jsonPrimitive.content shouldBe
                "Submitted-note remark about this member -- must be cleared on erase"

            val items = export.jsonObject.getValue("sepaDebitItems").jsonArray
            items.size shouldBe 1
            items
                .single()
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content shouldBe "RETURNED"

            val returns = export.jsonObject.getValue("sepaReturns").jsonArray
            returns.size shouldBe 1
            returns
                .single()
                .jsonObject
                .getValue("reasonText")
                .jsonPrimitive.content shouldBe
                "Reason-text remark about this member -- must be cleared on erase"
        }

        test(
            "erase clears the free-text remark fields (revocationReason/submittedNote/cancellationReason/reasonText) on the " +
                "four new SEPA tables but retains the rows AND debtor_iban_ciphertext (GoBD retention, M-1)",
        ) {
            val member = createTestMember("payments-pd-sepa-erase@example.org")
            createFullSepaChain(member)

            val outcomes = transaction { PaymentsPersonalData.erase(memberId = member, mode = ErasureMode.ANONYMIZE) }
            outcomes.single { it.table == "sepa_mandate" }.rowsRetained shouldBe 1
            outcomes.single { it.table == "sepa_debit_batch" }.rowsRetained shouldBe 1
            outcomes.single { it.table == "sepa_debit_item" }.rowsRetained shouldBe 1
            outcomes.single { it.table == "sepa_return" }.rowsRetained shouldBe 1

            transaction {
                val mandateRow = SepaMandateTable.selectAll().where { SepaMandateTable.memberId eq member }.single()
                mandateRow[SepaMandateTable.revocationReason] shouldBe null
                // The ciphertext is retained -- NEVER cleared even on erasure (see class KDoc).
                mandateRow[SepaMandateTable.debtorIbanCiphertext].startsWith("unused-ciphertext-") shouldBe true

                val batchRow = SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.createdBy eq member }.single()
                batchRow[SepaDebitBatchTable.submittedNote] shouldBe null
                batchRow[SepaDebitBatchTable.cancellationReason] shouldBe null

                val returnRow = SepaReturnTable.selectAll().where { SepaReturnTable.recordedBy eq member }.single()
                returnRow[SepaReturnTable.reasonText] shouldBe null
            }

            // export afterward must reflect the cleared fields, not the pre-erasure values.
            val exportAfterErase = transaction { PaymentsPersonalData.export(member) }
            exportAfterErase.jsonObject
                .getValue("sepaMandates")
                .jsonArray
                .single()
                .jsonObject["revocationReason"]
                .toString() shouldBe "null"
        }

        // ════════════════════════════════════════════════════════════════
        // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- export/erase symmetry for the
        // new payment_checkout_session table (F12), plus the two new payment_transaction columns.
        // ════════════════════════════════════════════════════════════════

        fun insertCheckoutSession(memberId: Uuid): Uuid {
            val id = Uuid.random()
            val now = LocalDateTime(2026, 4, 1, 10, 0)
            transaction {
                PaymentCheckoutSessionTable.insert {
                    it[PaymentCheckoutSessionTable.id] = id
                    it[provider] = PaymentProvider.STRIPE
                    it[providerSessionId] = "cs_test_${id.toString().take(8)}"
                    it[status] = PaymentCheckoutSessionStatus.CREATED
                    it[intent] = PaymentIntent.DONATION
                    it[contributionId] = null
                    it[PaymentCheckoutSessionTable.memberId] = memberId
                    it[amount] = BigDecimal("25.00")
                    it[currency] = "EUR"
                    it[donorCategory] = null
                    it[purpose] = "Free-text purpose remark about this member -- must be cleared on erase"
                    it[createdAt] = now
                    it[expiresAt] = now
                    it[completedAt] = null
                    it[providerIdempotencyKey] = "idem-${id.toString().take(8)}"
                    it[redirectUrl] = "https://checkout.stripe.com/c/pay/cs_test_${id.toString().take(8)}"
                }
            }
            return id
        }

        test("export includes paymentCheckoutSessions for the member (F12)") {
            val member = createTestMember("payments-pd-checkout-session-export@example.org")
            insertCheckoutSession(member)

            val export = transaction { PaymentsPersonalData.export(member) }
            val sessions = export.jsonObject.getValue("paymentCheckoutSessions").jsonArray
            sessions.size shouldBe 1
            sessions
                .single()
                .jsonObject
                .getValue("purpose")
                .jsonPrimitive.content shouldBe
                "Free-text purpose remark about this member -- must be cleared on erase"
            sessions
                .single()
                .jsonObject
                .getValue("currency")
                .jsonPrimitive.content shouldBe "EUR"
        }

        test("erase clears payment_checkout_session.purpose but retains the row and amount/currency/status (GoBD retention)") {
            val member = createTestMember("payments-pd-checkout-session-erase@example.org")
            val sessionId = insertCheckoutSession(member)

            val outcomes = transaction { PaymentsPersonalData.erase(memberId = member, mode = ErasureMode.ANONYMIZE) }
            outcomes.single { it.table == "payment_checkout_session" }.rowsRetained shouldBe 1

            transaction {
                val row = PaymentCheckoutSessionTable.selectAll().where { PaymentCheckoutSessionTable.id eq sessionId }.single()
                row[PaymentCheckoutSessionTable.purpose] shouldBe null
                row[PaymentCheckoutSessionTable.amount] shouldBe BigDecimal("25.00")
                row[PaymentCheckoutSessionTable.currency] shouldBe "EUR"
            }
        }

        test("export includes the two new payment_transaction columns (checkoutSessionId/donorCategory) -- symmetry with the new table") {
            val member = createTestMember("payments-pd-transaction-new-columns@example.org")
            val sessionId = insertCheckoutSession(member)
            transaction {
                PaymentTransactionTable.insert {
                    it[id] = Uuid.random()
                    it[provider] = PaymentProvider.STRIPE
                    it[providerEventId] = "evt-${Uuid.random()}"
                    it[providerPaymentId] = "pi-${Uuid.random()}"
                    it[status] = PaymentTransactionStatus.CAPTURED
                    it[amount] = BigDecimal("25.00")
                    it[currency] = "EUR"
                    it[feeAmount] = null
                    it[intent] = PaymentIntent.DONATION
                    it[contributionId] = null
                    it[PaymentTransactionTable.memberId] = member
                    it[payerReference] = null
                    it[receivedAt] = LocalDateTime(2026, 4, 1, 10, 0)
                    it[reconciledAt] = null
                    it[reconciledBy] = null
                    it[journalEntryId] = null
                    it[reconciliationNote] = null
                    it[rawPayloadDigest] = "0".repeat(64)
                    it[checkoutSessionId] = sessionId
                    it[donorCategory] = null
                }
            }

            val export = transaction { PaymentsPersonalData.export(member) }
            val entry =
                export.jsonObject
                    .getValue("paymentTransactions")
                    .jsonArray
                    .single()
                    .jsonObject
            entry.containsKey("checkoutSessionId") shouldBe true
            entry.containsKey("donorCategory") shouldBe true
        }
    })
