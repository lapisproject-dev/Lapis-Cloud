package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- `V25__accounting_export.sql`'s two
 * application-maintained shadow-column UNIQUE indexes (`uq_accounting_export_item_exported`,
 * `uq_accounting_export_run_active`) and their two backing CHECK constraints actually fire against
 * the real migrated H2 schema. Same "CHECK-Sonde" pattern [MemberFamilyConstraintTest] establishes
 * -- a raw `exec()` INSERT, expecting an [ExposedSQLException] naming the violated constraint. See
 * `43-accounting-export.kuml.kts` file header "Why a partial unique index is NOT used here" for the
 * portability rationale this test protects.
 */
class AccountingExportConstraintTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEntryIds = mutableListOf<Uuid>()
        val createdRunIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                exec(
                    "DELETE FROM accounting_export_item WHERE run_id IN (${createdRunIds.joinToString(
                        ",",
                    ) { "'$it'" }.ifEmpty { "'00000000-0000-0000-0000-000000000000'" }})",
                )
                if (createdRunIds.isNotEmpty()) {
                    exec(
                        "DELETE FROM accounting_export_run WHERE id IN (${createdRunIds.joinToString(",") { "'$it'" }})",
                    )
                }
                if (createdEntryIds.isNotEmpty()) JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdEntryIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Export Constraint Testmitglied"
                    it[email] = "export-constraint-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        fun newJournalEntry(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = LocalDate(2026, 1, 15)
                    it[description] = "Export Constraint Testbuchung"
                    it[voucherReference] = null
                    it[JournalEntryTable.createdBy] = createdBy
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(2026, 1, 15, 9, 0)
                    it[createdAt] = LocalDateTime(2026, 1, 15, 8, 0)
                }
            }
            createdEntryIds += id
            return id
        }

        fun probeInsert(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        fun itemSql(
            id: Uuid,
            runId: Uuid,
            journalEntryId: Uuid,
            status: String,
            exportedKey: String?,
        ): String {
            val keySql = exportedKey?.let { "'$it'" } ?: "NULL"
            return "INSERT INTO accounting_export_item (id, run_id, provider, journal_entry_id, entry_date, " +
                "external_category_id, voucher_number, direction, gross_amount, status, exported_key, attempts) " +
                "VALUES ('$id', '$runId', 'LEXOFFICE', '$journalEntryId', DATE '2026-01-15', 'cat-1', " +
                "'LAPIS-20260115-${id.toString().take(8)}', 'INCOME', 1.00, '$status', $keySql, 0)"
        }

        fun runSql(
            id: Uuid,
            status: String,
            activeKey: String?,
            startedBy: Uuid,
        ): String {
            val keySql = activeKey?.let { "'$it'" } ?: "NULL"
            return "INSERT INTO accounting_export_run (id, provider, period_from, period_to, status, active_key, " +
                "started_by, started_at, total_count, succeeded_count, failed_count, skipped_count, unknown_count) " +
                "VALUES ('$id', 'LEXOFFICE', DATE '2026-01-01', DATE '2026-01-31', '$status', $keySql, '$startedBy', " +
                "TIMESTAMP '2026-01-01 00:00:00', 0, 0, 0, 0, 0)"
        }

        test("uq_accounting_export_item_exported rejects a second SUCCEEDED item for the same (provider, journal_entry)") {
            val actor = newMember()
            val runId = Uuid.random()
            createdRunIds += runId
            probeInsert(runSql(runId, "COMPLETED", null, actor)) shouldBe null
            val entryId = newJournalEntry(actor)
            val exportedKey = "LEXOFFICE:$entryId"
            val first = probeInsert(itemSql(Uuid.random(), runId, entryId, "SUCCEEDED", exportedKey))
            first shouldBe null
            val secondEntryButSameKey = probeInsert(itemSql(Uuid.random(), runId, entryId, "SUCCEEDED", exportedKey))
            (secondEntryButSameKey is ExposedSQLException) shouldBe true
            (secondEntryButSameKey?.message ?: "").contains("uq_accounting_export_item_exported", ignoreCase = true) shouldBe true
        }

        test("chk_accounting_export_item_exported_key rejects SUCCEEDED with a NULL exported_key") {
            val actor = newMember()
            val runId = Uuid.random()
            createdRunIds += runId
            probeInsert(runSql(runId, "COMPLETED", null, actor)) shouldBe null
            val entryId = newJournalEntry(actor)
            val exception = probeInsert(itemSql(Uuid.random(), runId, entryId, "SUCCEEDED", null))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_accounting_export_item_exported_key", ignoreCase = true) shouldBe true
        }

        test("chk_accounting_export_item_exported_key rejects PENDING with a non-NULL exported_key") {
            val actor = newMember()
            val runId = Uuid.random()
            createdRunIds += runId
            // Deliberately NOT the literal "LEXOFFICE" -- reserved for the dedicated
            // uq_accounting_export_run_active collision test below; any other non-terminal run left
            // lingering under that exact key would make that test's own first insert spuriously fail.
            probeInsert(runSql(runId, "RUNNING", "LEXOFFICE-1", actor)) shouldBe null
            val entryId = newJournalEntry(actor)
            val exception = probeInsert(itemSql(Uuid.random(), runId, entryId, "PENDING", "LEXOFFICE:$entryId"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_accounting_export_item_exported_key", ignoreCase = true) shouldBe true
        }

        test("a PENDING item with a NULL exported_key is accepted") {
            val actor = newMember()
            val runId = Uuid.random()
            createdRunIds += runId
            probeInsert(runSql(runId, "RUNNING", "LEXOFFICE-2", actor)) shouldBe null
            val entryId = newJournalEntry(actor)
            val exception = probeInsert(itemSql(Uuid.random(), runId, entryId, "PENDING", null))
            exception shouldBe null
        }

        test("uq_accounting_export_run_active rejects a second non-terminal run for the same provider") {
            val actor = newMember()
            val runA = Uuid.random()
            val runB = Uuid.random()
            createdRunIds += listOf(runA, runB)
            val first = probeInsert(runSql(runA, "RUNNING", "LEXOFFICE", actor))
            first shouldBe null
            val second = probeInsert(runSql(runB, "PLANNED", "LEXOFFICE", actor))
            (second is ExposedSQLException) shouldBe true
            (second?.message ?: "").contains("uq_accounting_export_run_active", ignoreCase = true) shouldBe true
        }

        test("two terminal runs (active_key NULL) for the same provider are both allowed -- the whole point of the shadow-column trick") {
            val actor = newMember()
            val runA = Uuid.random()
            val runB = Uuid.random()
            createdRunIds += listOf(runA, runB)
            val first = probeInsert(runSql(runA, "COMPLETED", null, actor))
            val second = probeInsert(runSql(runB, "ABORTED", null, actor))
            first shouldBe null
            second shouldBe null
        }

        test("chk_accounting_export_run_active_key rejects RUNNING with a NULL active_key") {
            val actor = newMember()
            val runId = Uuid.random()
            createdRunIds += runId
            val exception = probeInsert(runSql(runId, "RUNNING", null, actor))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_accounting_export_run_active_key", ignoreCase = true) shouldBe true
        }

        test("chk_accounting_export_run_active_key rejects COMPLETED with a non-NULL active_key") {
            val actor = newMember()
            val runId = Uuid.random()
            createdRunIds += runId
            val exception = probeInsert(runSql(runId, "COMPLETED", "LEXOFFICE-3", actor))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_accounting_export_run_active_key", ignoreCase = true) shouldBe true
        }
    })
