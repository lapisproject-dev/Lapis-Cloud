package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SepaReturnTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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
 * them erased without ever having had the chance to see it via their own Art. 15 request first. At
 * the time of that fix, no code path wrote rows into this table yet (webhook ingestion was still a
 * future sub-wave, then tracked under the placeholder name "V1.2.4") -- these fields were exported
 * ahead of that first real row, so the export/erase pair was symmetric from the start rather than
 * retrofitted later. That sub-wave is now Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6),
 * see [PaymentCheckoutSessionTable] below for its own coverage.
 *
 * [SepaComplianceAcknowledgmentTable]/[PaymentGatewayComplianceAcknowledgmentTable] retain
 * everything, no field cleared -- same reasoning [AuctionPersonalData] gives for
 * `auction_compliance_acknowledgment`: who acknowledged which disclaimer version and when is the
 * ADMIN's own compliance-accountability record (Art. 5(2) DSGVO).
 *
 * [PaymentCheckoutSessionTable] (Welle V1.2.8) is covered here too -- see [coveredTables] KDoc
 * comment (F12) and [erase]'s own treatment (only the free-text `purpose` column is cleared, same
 * accounting-retention duty as [PaymentTransactionTable]).
 */
object PaymentsPersonalData : PersonalDataContributor {
    override val sectionKey = "payments"
    override val displayName = "Zahlungsverkehr"
    override val coveredTables =
        setOf(
            PaymentTransactionTable,
            SepaComplianceAcknowledgmentTable,
            PaymentGatewayComplianceAcknowledgmentTable,
            // V1.2.2 "SEPA-Lastschriftmandate" -- new FKs on member(id): sepa_mandate.member_id/
            // .revoked_by/.created_by, sepa_debit_batch.created_by, sepa_return.recorded_by.
            // PersonalDataCoverageTest walks information_schema for every such FK and fails if the
            // owning table is not covered by SOME contributor -- these four are covered here.
            SepaMandateTable,
            SepaDebitBatchTable,
            SepaDebitItemTable,
            SepaReturnTable,
            // Welle V1.2.8 "PSP-Checkout (Stripe)" -- new FK on member(id): payment_checkout_session
            // .member_id. PersonalDataCoverageTest walks information_schema for every such FK and
            // fails if the owning table is not covered by SOME contributor -- covered here (F12).
            PaymentCheckoutSessionTable,
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
                                    // Welle V1.2.8 export/erase symmetry (plan's own rule, same
                                    // reasoning as the Security Round 1 SHOULD-2 fix above): the two
                                    // new payment_transaction columns are exported now, from the
                                    // first row that can ever carry them.
                                    put("checkoutSessionId", row[PaymentTransactionTable.checkoutSessionId]?.toString())
                                    put("donorCategory", row[PaymentTransactionTable.donorCategory]?.name)
                                },
                            )
                        }
                },
            )
            put(
                "paymentCheckoutSessions",
                buildJsonArray {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[PaymentCheckoutSessionTable.id].toString())
                                    put("provider", row[PaymentCheckoutSessionTable.provider].name)
                                    put("status", row[PaymentCheckoutSessionTable.status].name)
                                    put("intent", row[PaymentCheckoutSessionTable.intent].name)
                                    put("contributionId", row[PaymentCheckoutSessionTable.contributionId]?.toString())
                                    put("amount", row[PaymentCheckoutSessionTable.amount].toPlainString())
                                    put("currency", row[PaymentCheckoutSessionTable.currency])
                                    put("donorCategory", row[PaymentCheckoutSessionTable.donorCategory]?.name)
                                    put("purpose", row[PaymentCheckoutSessionTable.purpose])
                                    put("createdAt", row[PaymentCheckoutSessionTable.createdAt].toString())
                                    put("expiresAt", row[PaymentCheckoutSessionTable.expiresAt].toString())
                                    put("completedAt", row[PaymentCheckoutSessionTable.completedAt]?.toString())
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
            // V1.2.2 "SEPA-Lastschriftmandate". debtor_iban_ciphertext is NEVER exported -- neither
            // raw nor decrypted. A deliberate weighing against Art. 15: raw would be worthless to
            // the data subject and a foothold for an attacker; decrypted would break the one rule of
            // this wave ("the IBAN leaves the database exclusively toward a pain.008 file"). The
            // subject already has their own IBAN, and debtorIbanLast4 plus the mandate reference
            // uniquely identify the mandate.
            put(
                "sepaMandates",
                buildJsonArray {
                    SepaMandateTable
                        .selectAll()
                        .where {
                            (SepaMandateTable.memberId eq memberId) or
                                (SepaMandateTable.createdBy eq memberId) or
                                (SepaMandateTable.revokedBy eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[SepaMandateTable.id].toString())
                                    put("mandateReference", row[SepaMandateTable.mandateReference])
                                    put("debtorName", row[SepaMandateTable.debtorName])
                                    put("debtorIbanLast4", row[SepaMandateTable.debtorIbanLast4])
                                    put("debtorBic", row[SepaMandateTable.debtorBic])
                                    put("signatureDate", row[SepaMandateTable.signatureDate].toString())
                                    put("sequenceType", row[SepaMandateTable.sequenceType].name)
                                    put("status", row[SepaMandateTable.status].name)
                                    put("grantedAt", row[SepaMandateTable.grantedAt].toString())
                                    put("revokedAt", row[SepaMandateTable.revokedAt]?.toString())
                                    put("revocationReason", row[SepaMandateTable.revocationReason])
                                    put("lastUsedAt", row[SepaMandateTable.lastUsedAt]?.toString())
                                    put("lastDebitedAmount", row[SepaMandateTable.lastDebitedAmount]?.toPlainString())
                                    put("createdBy", row[SepaMandateTable.createdBy].toString())
                                },
                            )
                        }
                },
            )
            put(
                "sepaDebitItems",
                buildJsonArray {
                    (SepaDebitItemTable innerJoin ContributionTable)
                        .selectAll()
                        .where { ContributionTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[SepaDebitItemTable.id].toString())
                                    put("batchId", row[SepaDebitItemTable.batchId].toString())
                                    put("contributionId", row[SepaDebitItemTable.contributionId].toString())
                                    put("endToEndId", row[SepaDebitItemTable.endToEndId])
                                    put("amount", row[SepaDebitItemTable.amount].toPlainString())
                                    put("remittanceInformation", row[SepaDebitItemTable.remittanceInformation])
                                    put("status", row[SepaDebitItemTable.status].name)
                                    put("settleableAt", row[SepaDebitItemTable.settleableAt]?.toString())
                                    put("journalEntryId", row[SepaDebitItemTable.journalEntryId]?.toString())
                                },
                            )
                        }
                },
            )
            put(
                "sepaDebitBatches",
                buildJsonArray {
                    SepaDebitBatchTable
                        .selectAll()
                        .where { SepaDebitBatchTable.createdBy eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[SepaDebitBatchTable.id].toString())
                                    put("messageId", row[SepaDebitBatchTable.messageId])
                                    put("requestedCollectionDate", row[SepaDebitBatchTable.requestedCollectionDate].toString())
                                    put("status", row[SepaDebitBatchTable.status].name)
                                    put("itemCount", row[SepaDebitBatchTable.itemCount])
                                    put("totalAmount", row[SepaDebitBatchTable.totalAmount].toPlainString())
                                    put("createdAt", row[SepaDebitBatchTable.createdAt].toString())
                                    put("submittedNote", row[SepaDebitBatchTable.submittedNote])
                                    put("cancellationReason", row[SepaDebitBatchTable.cancellationReason])
                                },
                            )
                        }
                },
            )
            put(
                "sepaReturns",
                buildJsonArray {
                    (SepaReturnTable innerJoin SepaDebitItemTable)
                        .join(ContributionTable, JoinType.INNER, SepaDebitItemTable.contributionId, ContributionTable.id)
                        .selectAll()
                        .where { (ContributionTable.memberId eq memberId) or (SepaReturnTable.recordedBy eq memberId) }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[SepaReturnTable.id].toString())
                                    put("returnedAt", row[SepaReturnTable.returnedAt].toString())
                                    put("reasonCode", row[SepaReturnTable.reasonCode].name)
                                    put("reasonText", row[SepaReturnTable.reasonText])
                                    put("returnFee", row[SepaReturnTable.returnFee]?.toPlainString())
                                    put("recordedAt", row[SepaReturnTable.recordedAt].toString())
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

        // Welle V1.2.8 "PSP-Checkout (Stripe)". Same accounting retention duty (GoBD/HGB/AO, 10
        // Jahre) as payment_transaction above -- only the free-text `purpose` column (a donor's own
        // optional note, may carry personal context) is cleared; the rest (amount/currency/status/
        // donorCategory/timestamps) is retained as the server-authoritative anchor a completed
        // checkout's PaymentTransaction row still references.
        val checkoutSessionCondition = PaymentCheckoutSessionTable.memberId eq memberId
        val checkoutSessionCount = PaymentCheckoutSessionTable.selectAll().where { checkoutSessionCondition }.count()
        PaymentCheckoutSessionTable.update({ checkoutSessionCondition }) {
            it[purpose] = null
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

        // V1.2.2 "SEPA-Lastschriftmandate". Same accounting/mandate-proof retention duty as
        // payment_transaction above -- only free-text fields that may carry personal remarks ABOUT
        // the subject are cleared; debtor_iban_ciphertext is NEVER cleared (see export's own KDoc
        // "bewusste Abwaegung"): the retention duty for a granted mandate covers the mandate itself,
        // and the ciphertext is unreadable without LAPIS_SECRET_ENCRYPTION_KEY anyway.
        //
        // Security Round 1 (2026-08-20, MAJOR-2) -- CORRECTED CLAIM: this comment used to say
        // "deleting that key, if ever necessary, is the real cryptographic erasure" for the
        // member's IBAN. That was FALSE once a batch has actually been generated for this member:
        // `SepaService.generateBatchFile` writes the SAME IBAN, in plaintext, into an archived
        // pain.008 XML file completely INDEPENDENT of `sepa_mandate.debtor_iban_ciphertext` and
        // `LAPIS_SECRET_ENCRYPTION_KEY` -- destroying that key erases the DB column, not the
        // archived file. A member exercising a DSGVO Art. 17 erasure request would have been falsely
        // told their bank data was cryptographically erased.
        //
        // ACTUAL current retention state, honestly stated: the archived pain.008 file is (as of this
        // round) itself `SecretBox`-sealed at rest under the SAME `LAPIS_SECRET_ENCRYPTION_KEY` (see
        // `SepaService.generateBatchFile` KDoc "Phase 2") -- so destroying the key DOES now also
        // cryptographically erase every archived batch file, not just the DB column, closing the gap
        // this comment used to misstate. What remains a DELIBERATE, DOCUMENTED follow-up (not
        // silently dropped) is a scheduled RETENTION/PURGE job for `document`/`document_version` rows
        // in the "SEPA-Lastschriften" folder once their owning batch reaches a terminal state
        // (SETTLED/CANCELLED, or old enough that the SEPA_RETURN_WINDOW has passed) -- see CHANGELOG
        // "Security Round 1" for this wave's explicit scope decision. Until that job exists, an
        // archived file is retained indefinitely (encrypted, but not time-bounded) even after this
        // erase() call clears what it can from the live DB rows.
        val mandateCondition =
            (SepaMandateTable.memberId eq memberId) or (SepaMandateTable.createdBy eq memberId) or (SepaMandateTable.revokedBy eq memberId)
        val mandateCount = SepaMandateTable.selectAll().where { mandateCondition }.count()
        SepaMandateTable.update({ mandateCondition }) { it[revocationReason] = null }

        val batchCondition = SepaDebitBatchTable.createdBy eq memberId
        val batchCount = SepaDebitBatchTable.selectAll().where { batchCondition }.count()
        SepaDebitBatchTable.update({ batchCondition }) {
            it[submittedNote] = null
            it[cancellationReason] = null
        }

        val itemCount =
            (SepaDebitItemTable innerJoin ContributionTable).selectAll().where { ContributionTable.memberId eq memberId }.count()

        val returnIds =
            (SepaReturnTable innerJoin SepaDebitItemTable)
                .join(ContributionTable, JoinType.INNER, SepaDebitItemTable.contributionId, ContributionTable.id)
                .selectAll()
                .where { (ContributionTable.memberId eq memberId) or (SepaReturnTable.recordedBy eq memberId) }
                .map { it[SepaReturnTable.id] }
        val returnCount = returnIds.size
        if (returnIds.isNotEmpty()) {
            SepaReturnTable.update({ SepaReturnTable.id inList returnIds }) { it[reasonText] = null }
        }

        return listOf(
            TableErasureOutcome(
                table = "payment_transaction",
                rowsRetained = transactionCount.toInt(),
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre).",
            ),
            TableErasureOutcome(
                table = "payment_checkout_session",
                rowsRetained = checkoutSessionCount.toInt(),
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
            TableErasureOutcome(
                table = "sepa_mandate",
                rowsRetained = mandateCount.toInt(),
                retentionReason =
                    "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) sowie Nachweispflicht " +
                        "fuer erteilte SEPA-Mandate ueber deren Gueltigkeitsdauer hinaus.",
            ),
            TableErasureOutcome(
                table = "sepa_debit_batch",
                rowsRetained = batchCount.toInt(),
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre).",
            ),
            TableErasureOutcome(
                table = "sepa_debit_item",
                rowsRetained = itemCount.toInt(),
                retentionReason =
                    "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) -- remittance_information " +
                        "ist der Verwendungszweck aus der eingereichten Bankdatei und darf nachtraeglich nicht veraendert werden.",
            ),
            TableErasureOutcome(
                table = "sepa_return",
                rowsRetained = returnCount,
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre).",
            ),
        )
    }
}
