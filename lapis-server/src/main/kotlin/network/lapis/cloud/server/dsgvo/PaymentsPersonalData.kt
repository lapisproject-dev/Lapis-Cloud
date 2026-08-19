package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns [PaymentTransactionTable]/[SepaComplianceAcknowledgmentTable]/
 * [PaymentGatewayComplianceAcknowledgmentTable] (Welle V1.2.1 "Zahlungs-Fundament"). Same
 * three-table-shape-per-domain precedent [AuctionPersonalData] already establishes.
 *
 * [PaymentTransactionTable] retains everything regardless of [ErasureMode] -- same accounting
 * retention duty (GoBD/HGB/AO, 10 Jahre) [ContributionPersonalData] already applies to `contribution`
 * -- only the free-text `reconciliation_note` column (may contain a treasurer's remark about
 * another member) is cleared. `payer_reference` is deliberately NOT cleared: it is already a
 * pseudonymous PSP-side identifier by construction (see `33-payments.kuml.kts` file header), never
 * a name/email, and is needed to prove which payment a reconciliation decision was actually made
 * against.
 *
 * **DSGVO Art. 15 export/erase symmetry (Security Round 1, 2026-08-19, SHOULD-2):** [export]
 * carries `reconciliationNote`/`payerReference`/`feeAmount`/`currency`/`providerPaymentId` too, not
 * just the six fields the pre-round export had -- before this fix, [erase] cleared
 * `reconciliation_note` because it may contain personal data ABOUT the subject (see its own KDoc
 * below), but [export] never surfaced that same field, so a data subject could have a remark about
 * them erased without ever having had the chance to see it via their own Art. 15 request first. No
 * V1.2.1 code path writes rows into this table yet (webhook ingestion is V1.2.4, see
 * `33-payments.kuml.kts` file header) -- these fields are exported now so the export/erase pair
 * stays symmetric from the first real row onward, not retrofitted later once real payment data
 * exists.
 *
 * [SepaComplianceAcknowledgmentTable]/[PaymentGatewayComplianceAcknowledgmentTable] retain
 * everything, no field cleared -- same reasoning [AuctionPersonalData] gives for
 * `auction_compliance_acknowledgment`: who acknowledged which disclaimer version and when is the
 * ADMIN's own compliance-accountability record (Art. 5(2) DSGVO).
 */
object PaymentsPersonalData : PersonalDataContributor {
    override val sectionKey = "payments"
    override val displayName = "Zahlungsverkehr"
    override val coveredTables =
        setOf(
            PaymentTransactionTable,
            SepaComplianceAcknowledgmentTable,
            PaymentGatewayComplianceAcknowledgmentTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            put(
                "paymentTransactions",
                buildJsonArray {
                    PaymentTransactionTable
                        .selectAll()
                        .where { (PaymentTransactionTable.memberId eq memberId) or (PaymentTransactionTable.reconciledBy eq memberId) }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[PaymentTransactionTable.id].toString())
                                    put("provider", row[PaymentTransactionTable.provider].name)
                                    put("status", row[PaymentTransactionTable.status].name)
                                    put("amount", row[PaymentTransactionTable.amount].toPlainString())
                                    put("intent", row[PaymentTransactionTable.intent].name)
                                    put("contributionId", row[PaymentTransactionTable.contributionId]?.toString())
                                    put("receivedAt", row[PaymentTransactionTable.receivedAt].toString())
                                    // Security Round 1 (2026-08-19, SHOULD-2): export/erase symmetry --
                                    // see class KDoc.
                                    put("providerPaymentId", row[PaymentTransactionTable.providerPaymentId])
                                    put("currency", row[PaymentTransactionTable.currency])
                                    put("feeAmount", row[PaymentTransactionTable.feeAmount]?.toPlainString())
                                    put("payerReference", row[PaymentTransactionTable.payerReference])
                                    put("reconciliationNote", row[PaymentTransactionTable.reconciliationNote])
                                },
                            )
                        }
                },
            )
            put(
                "sepaComplianceAcknowledgments",
                buildJsonArray {
                    SepaComplianceAcknowledgmentTable
                        .selectAll()
                        .where { SepaComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[SepaComplianceAcknowledgmentTable.id].toString())
                                    put("disclaimerVersion", row[SepaComplianceAcknowledgmentTable.disclaimerVersion])
                                    put("acknowledgedAt", row[SepaComplianceAcknowledgmentTable.acknowledgedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "paymentGatewayComplianceAcknowledgments",
                buildJsonArray {
                    PaymentGatewayComplianceAcknowledgmentTable
                        .selectAll()
                        .where { PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[PaymentGatewayComplianceAcknowledgmentTable.id].toString())
                                    put("disclaimerVersion", row[PaymentGatewayComplianceAcknowledgmentTable.disclaimerVersion])
                                    put("provider", row[PaymentGatewayComplianceAcknowledgmentTable.provider].name)
                                    put("acknowledgedAt", row[PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt].toString())
                                },
                            )
                        }
                },
            )
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val transactionCondition = (PaymentTransactionTable.memberId eq memberId) or (PaymentTransactionTable.reconciledBy eq memberId)
        val transactionCount = PaymentTransactionTable.selectAll().where { transactionCondition }.count()
        PaymentTransactionTable.update({ transactionCondition }) {
            it[reconciliationNote] = null
        }

        val sepaAckCount =
            SepaComplianceAcknowledgmentTable
                .selectAll()
                .where { SepaComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()

        val gatewayAckCount =
            PaymentGatewayComplianceAcknowledgmentTable
                .selectAll()
                .where { PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()

        return listOf(
            TableErasureOutcome(
                table = "payment_transaction",
                rowsRetained = transactionCount.toInt(),
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre).",
            ),
            TableErasureOutcome(
                table = "sepa_compliance_acknowledgment",
                rowsRetained = sepaAckCount.toInt(),
                retentionReason =
                    "Who acknowledged which disclaimer version and when is the ADMIN's own " +
                        "compliance-accountability record (Art. 5(2) DSGVO).",
            ),
            TableErasureOutcome(
                table = "payment_gateway_compliance_acknowledgment",
                rowsRetained = gatewayAckCount.toInt(),
                retentionReason =
                    "Who acknowledged which disclaimer version and when is the ADMIN's own " +
                        "compliance-accountability record (Art. 5(2) DSGVO).",
            ),
        )
    }
}
