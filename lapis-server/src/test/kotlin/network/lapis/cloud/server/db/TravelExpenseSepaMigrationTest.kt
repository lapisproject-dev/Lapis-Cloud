package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.rpc.TravelExpenseSepaTestData
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.123 -- `V98__travel_expense_sepa_credit_transfer.sql`: defaults of the new columns and the CHECK constraints
 * actually fire against the real migrated schema ("CHECK-Sonde" idiom, see [CrmMigrationTest]).
 */
class TravelExpenseSepaMigrationTest :
    FunSpec({
        val data = PollTestData()
        val sepa = TravelExpenseSepaTestData(data)

        beforeSpec {
            DatabaseConfig.connect()
            sepa.configureOrganization()
        }
        afterSpec {
            sepa.cleanUp()
            data.cleanUp()
        }

        fun probe(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        fun violates(
            sql: String,
            constraint: String,
        ) {
            val e = probe(sql)
            (e is ExposedSQLException) shouldBe true
            (e?.message ?: "").contains(constraint, ignoreCase = true) shouldBe true
        }

        fun newReport(): Uuid = sepa.executedReport(subject = data.member(label = "mig", role = AccountRole.MEMBER), iban = null)

        test("a pre-V98 style report has the column defaults: not exported, no changes, reexport count 0") {
            val row =
                transaction {
                    val id = newReport()
                    var result = ""
                    exec(
                        "SELECT refund_account_changed_after_submit, sepa_reexport_count, sepa_exported_at, refund_iban_ciphertext " +
                            "FROM travel_expense_report WHERE id = '$id'",
                    ) { rs ->
                        rs.next()
                        result = "${rs.getBoolean(1)}|${rs.getInt(2)}|${rs.getObject(3)}|${rs.getObject(4)}"
                    }
                    result
                }
            row shouldBe "false|0|null|null"
        }

        test("chk_ter_sepa_export_consistent: the three export columns are all null or all set") {
            val id = newReport()
            violates(
                "UPDATE travel_expense_report SET sepa_exported_at = TIMESTAMP '2026-10-13 10:00:00' WHERE id = '$id'",
                "chk_ter_sepa_export_consistent",
            )
        }

        test("chk_ter_sepa_reexport_count: 0..99") {
            val id = newReport()
            violates("UPDATE travel_expense_report SET sepa_reexport_count = 100 WHERE id = '$id'", "chk_ter_sepa_reexport_count")
            violates("UPDATE travel_expense_report SET sepa_reexport_count = -1 WHERE id = '$id'", "chk_ter_sepa_reexport_count")
        }

        test("chk_ter_refund_iban_pair: ciphertext and last4 are set together or not at all") {
            val id = newReport()
            violates("UPDATE travel_expense_report SET refund_iban_last4 = '2051' WHERE id = '$id'", "chk_ter_refund_iban_pair")
            violates("UPDATE travel_expense_report SET refund_iban_ciphertext = 'v1:a:b' WHERE id = '$id'", "chk_ter_refund_iban_pair")
        }

        test("the batch tables refuse an empty batch, a non-positive sum, a re-export without reason and a reason without re-export") {
            val admin = data.member(label = "mig-admin", role = AccountRole.ADMIN)
            val bank = sepa.bankAccount(createdBy = admin)

            fun batch(
                count: Int = 1,
                sum: Long = 100,
                reexport: Boolean = false,
                reason: String? = null,
            ) = "INSERT INTO travel_expense_sepa_batch (id, debtor_bank_account_id, debtor_account_label, debtor_iban_last4, " +
                "requested_execution_date, transaction_count, control_sum_cents, pain_version, created_by, created_at, " +
                "is_reexport, reason) " +
                "VALUES ('${Uuid.random()}', '$bank', 'Konto', '3000', DATE '2026-10-14', $count, $sum, 'pain.001.001.09', '$admin', " +
                "TIMESTAMP '2026-10-13 10:00:00', $reexport, ${reason?.let { "'$it'" } ?: "NULL"})"
            violates(batch(count = 0), "chk_tesb_transaction_count")
            violates(batch(count = 501), "chk_tesb_transaction_count")
            violates(batch(sum = 0), "chk_tesb_control_sum")
            violates(batch(reexport = true, reason = null), "chk_tesb_reexport_reason")
            violates(batch(reexport = false, reason = "grundlos"), "chk_tesb_reexport_reason")
            probe(batch()) shouldBe null
            probe("DELETE FROM travel_expense_sepa_batch WHERE created_by = '$admin'") shouldBe null
        }

        test("an item amount must be positive and at most 999999999.99 EUR; the EndToEndId is globally unique") {
            val admin = data.member(label = "mig-admin2", role = AccountRole.ADMIN)
            val bank = sepa.bankAccount(createdBy = admin, iban = "AT611904300234573201", label = "Zweit")
            val report = newReport()
            val batchId = Uuid.random()
            transaction {
                exec(
                    "INSERT INTO travel_expense_sepa_batch (id, debtor_bank_account_id, debtor_account_label, debtor_iban_last4, " +
                        "requested_execution_date, transaction_count, control_sum_cents, pain_version, created_by, created_at, " +
                        "is_reexport) " +
                        "VALUES ('$batchId', '$bank', 'Konto', '3201', DATE '2026-10-14', 1, 100, 'pain.001.001.09', '$admin', " +
                        "TIMESTAMP '2026-10-13 10:00:00', FALSE)",
                )
            }

            fun item(
                cents: Long,
                e2e: String,
            ) = "INSERT INTO travel_expense_sepa_batch_item (id, batch_id, report_id, amount_cents, end_to_end_id) " +
                "VALUES ('${Uuid.random()}', '$batchId', '$report', $cents, '$e2e')"
            violates(item(0, "TEUNIT0001"), "chk_tesbi_amount")
            violates(item(100_000_000_000, "TEUNIT0002"), "chk_tesbi_amount")
            probe(item(100, "TEUNIT0003")) shouldBe null
            // Same EndToEndId again (a different report would not matter): the unique index fires.
            val duplicate = probe(item(100, "TEUNIT0003"))
            (duplicate is ExposedSQLException) shouldBe true
            // Same report twice in the same batch is refused as well.
            violates(item(100, "TEUNIT0004"), "uq_tesbi_batch_report")
            transaction { exec("DELETE FROM travel_expense_sepa_batch_item WHERE batch_id = '$batchId'") }
            transaction { exec("DELETE FROM travel_expense_sepa_batch WHERE id = '$batchId'") }
        }

        test("audit_log_entry accepts the new entity type and still refuses an unknown one") {
            val admin = data.member(label = "mig-admin3", role = AccountRole.ADMIN)

            fun audit(type: String) =
                "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                    "action, " +
                    "entry_hash, previous_entry_hash) VALUES ('${Uuid.random()}', ${900_000_000L + (1..99_999_999).random()}, " +
                    "TIMESTAMP '2026-10-13 10:00:00', '$admin', 'ADMIN', '$type', '${Uuid.random()}', 'CREATE', '${"0".repeat(64)}', NULL)"
            violates(audit("NOT_A_REAL_ENTITY"), "chk_audit_log_entry_entity_type")
            probe(audit("TRAVEL_EXPENSE_SEPA_BATCH")) shouldBe null
            transaction { exec("DELETE FROM audit_log_entry WHERE actor_member_id = '$admin'") }
        }
    })
