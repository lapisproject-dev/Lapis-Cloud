package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseRatingTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 -- `V65__polls.sql` as applied by Flyway to the real test database: the migration is
 * recorded as successful and every CHECK / UNIQUE constraint of the poll tables actually bites, plus
 * the widened `audit_log_entry.entity_type` CHECK accepts `'POLL'` on H2 (which enforces BOTH the
 * named constraint and V1's still-unnamed inline one -- see the migration's OPERATOR NOTE).
 */
class PollMigrationTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        val seededMember = "00000000-0000-0000-0000-000000000004"

        /** Executes [sql] in its own transaction and returns whether it was rejected. */
        fun rejected(sql: String): Boolean = runCatching { transaction { exec(sql) } }.isFailure

        fun accepted(sql: String): Boolean = !rejected(sql)

        fun newPoll(
            status: String = "OPEN",
            closedAt: String = "NULL",
            closedBy: String = "NULL",
            closesAt: String = "NULL",
            question: String = "'Frage?'",
        ): String {
            val id = UUID.randomUUID()
            transaction {
                exec(
                    "INSERT INTO poll (id, question, status, created_by, created_at, closes_at, closed_at, closed_by) VALUES " +
                        "('$id', $question, '$status', '$seededMember', TIMESTAMP '2026-01-01 10:00:00', $closesAt, $closedAt, $closedBy)",
                )
            }
            return id.toString()
        }

        afterSpec {
            transaction {
                PollResponseRatingTable.deleteAll()
                PollResponseTable.deleteAll()
                PollParticipationTable.deleteAll()
                PollOptionTable.deleteAll()
                PollTable.deleteAll()
            }
        }

        test("V65 is recorded as successfully applied in flyway_schema_history") {
            val success =
                transaction {
                    var result: Boolean? = null
                    exec("SELECT \"success\" FROM flyway_schema_history WHERE \"version\" = '65'") { rs ->
                        if (rs.next()) result = rs.getBoolean(1)
                    }
                    result
                }
            success shouldBe true
        }

        test("poll CHECK constraints: status, closed-state consistency, deadline after creation, non-blank question") {
            // valid shapes are accepted
            accepted(
                "INSERT INTO poll (id, question, status, created_by, created_at) VALUES " +
                    "('${UUID.randomUUID()}', 'ok', 'OPEN', '$seededMember', TIMESTAMP '2026-01-01 10:00:00')",
            ) shouldBe true
            accepted(
                "INSERT INTO poll (id, question, status, created_by, created_at, closed_at, closed_by) VALUES " +
                    "('${UUID.randomUUID()}', 'ok', 'ABORTED', '$seededMember', TIMESTAMP '2026-01-01 10:00:00', " +
                    "TIMESTAMP '2026-01-02 10:00:00', '$seededMember')",
            ) shouldBe true

            fun insertPoll(
                status: String,
                closedAt: String,
                closedBy: String,
                closesAt: String = "NULL",
                question: String = "'q'",
            ) = rejected(
                "INSERT INTO poll (id, question, status, created_by, created_at, closes_at, closed_at, closed_by) VALUES " +
                    "('${UUID.randomUUID()}', $question, '$status', '$seededMember', TIMESTAMP '2026-01-01 10:00:00', " +
                    "$closesAt, $closedAt, $closedBy)",
            )
            insertPoll("BOGUS", "NULL", "NULL") shouldBe true
            // OPEN must not carry a close instant / closer
            insertPoll("OPEN", "TIMESTAMP '2026-01-02 10:00:00'", "'$seededMember'") shouldBe true
            insertPoll("OPEN", "TIMESTAMP '2026-01-02 10:00:00'", "NULL") shouldBe true
            // CLOSED / ABORTED must carry both
            insertPoll("CLOSED", "NULL", "NULL") shouldBe true
            insertPoll("CLOSED", "TIMESTAMP '2026-01-02 10:00:00'", "NULL") shouldBe true
            insertPoll("ABORTED", "NULL", "'$seededMember'") shouldBe true
            // deadline must lie after creation
            insertPoll("OPEN", "NULL", "NULL", closesAt = "TIMESTAMP '2026-01-01 10:00:00'") shouldBe true
            insertPoll("OPEN", "NULL", "NULL", closesAt = "TIMESTAMP '2025-12-31 10:00:00'") shouldBe true
            // question must not be blank
            insertPoll("OPEN", "NULL", "NULL", question = "'   '") shouldBe true
            // foreign key
            rejected(
                "INSERT INTO poll (id, question, status, created_by, created_at) VALUES " +
                    "('${UUID.randomUUID()}', 'q', 'OPEN', '${UUID.randomUUID()}', TIMESTAMP '2026-01-01 10:00:00')",
            ) shouldBe true
        }

        test("poll_option: position 0..9 only, unique per poll") {
            val poll = newPoll()

            fun option(position: Int) =
                "INSERT INTO poll_option (id, poll_id, position, text) VALUES ('${UUID.randomUUID()}', '$poll', $position, 'o$position')"
            accepted(option(0)) shouldBe true
            accepted(option(9)) shouldBe true
            rejected(option(10)) shouldBe true
            rejected(option(-1)) shouldBe true
            rejected(option(0)) shouldBe true // duplicate position within the same poll
        }

        test("poll_participation: a member participates at most once per poll") {
            val poll = newPoll()

            fun participation() =
                "INSERT INTO poll_participation (id, poll_id, member_id) VALUES ('${UUID.randomUUID()}', '$poll', '$seededMember')"
            accepted(participation()) shouldBe true
            rejected(participation()) shouldBe true
        }

        test("poll_response: weight_ltr must be >= 0; option must exist") {
            val poll = newPoll()
            val option = UUID.randomUUID()
            transaction {
                exec("INSERT INTO poll_option (id, poll_id, position, text) VALUES ('$option', '$poll', 0, 'o')")
            }

            fun response(
                weight: String,
                optionId: UUID = option,
            ) =
                "INSERT INTO poll_response (id, poll_id, option_id, weight_ltr) VALUES ('${UUID.randomUUID()}', '$poll', '$optionId', $weight)"
            accepted(response("0.00")) shouldBe true
            accepted(response("123.45")) shouldBe true
            rejected(response("-0.01")) shouldBe true
            rejected(response("1.00", optionId = UUID.randomUUID())) shouldBe true
        }

        test("audit_log_entry accepts entity_type 'POLL' (H2 enforces the inline CHECK of V1 as well) and still rejects a bogus one") {
            val entryId = Uuid.random()
            transaction {
                AuditLogEntryTable.insert {
                    it[id] = entryId
                    it[sequenceNumber] = 9_000_000_000_000_065L
                    it[occurredAt] = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0)
                    it[actorMemberId] = null
                    it[actorRole] = null
                    it[entityType] = AuditEntityType.POLL
                    it[entityId] = Uuid.random()
                    it[action] = AuditAction.CREATE
                    it[entryHash] = "0".repeat(64)
                    it[previousEntryHash] = null
                }
            }
            transaction { AuditLogEntryTable.deleteWhere { AuditLogEntryTable.id eq entryId } }
            val zeros = "0".repeat(64)
            rejected(
                "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) VALUES " +
                    "('${UUID.randomUUID()}', 9000000000000066, TIMESTAMP '2026-01-01 00:00:00', 'BOGUS', " +
                    "'${UUID.randomUUID()}', 'CREATE', '$zeros')",
            ) shouldBe true
        }
    })
