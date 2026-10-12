package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.102 -- `V83__committee_membership_auto_end.sql` on the upgrade path V82 -> V83, on H2 AND on PostgreSQL: an existing seat survives
 * with `auto_end_reason` NULL (no backfill), the column accepts exactly the four ending statuses, the audit-log CHECK accepts
 * `COMMITTEE_MEMBERSHIP` and still rejects unknown entity types, and a re-run is a no-op.
 */
abstract class CommitteeAutoEndMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rows(sql: String): List<List<String>> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val memberId = UUID.randomUUID()
        val committeeId = UUID.randomUUID()
        val oldSeat = UUID.randomUUID()
        var seq = 9500
        val dummyHash = "b".repeat(64)

        fun seatInsert(
            id: UUID,
            reason: String?,
        ) = "INSERT INTO committee_membership (id, role, since, until, committee_id, member_id, auto_end_reason) " +
            "VALUES ('$id', 'MEMBER', DATE '2026-01-01', NULL, '$committeeId', '$memberId', ${reason?.let { "'$it'" } ?: "NULL"})"

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-11 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'UPDATE', '$dummyHash')"

        beforeSpec {
            h = newHarness()
            h.flyway("82").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'seat-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO committee (id, name, type, description, active, quorum_percent, created_at) " +
                    "VALUES ('$committeeId', 'AG', 'WORKING_GROUP', 'x', TRUE, 50, TIMESTAMP '2026-01-01 00:00:00')",
            )
            exec(
                "INSERT INTO committee_membership (id, role, since, until, committee_id, member_id) " +
                    "VALUES ('$oldSeat', 'MEMBER', DATE '2026-01-01', NULL, '$committeeId', '$memberId')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing seat survives with auto_end_reason NULL: nothing is backfilled") {
            rows("SELECT auto_end_reason FROM committee_membership WHERE id = '$oldSeat'").single() shouldBe listOf("null")
        }

        test("the column is nullable and accepts exactly the four ending statuses (and, since V92, RULE_MODE)") {
            listOf("WITHDRAWN", "REJECTED", "DECEASED", "DONOR", "RULE_MODE").forEach {
                rejection(seatInsert(UUID.randomUUID(), it)) shouldBe null
            }
            rejection(seatInsert(UUID.randomUUID(), null)) shouldBe null
            listOf("ACTIVE", "FRIEND", "APPLICATION", "GUEST", "BOGUS").forEach {
                (rejection(seatInsert(UUID.randomUUID(), it)) != null) shouldBe true
            }
        }

        test("the (auto_end_reason, until) index exists") {
            val index =
                h.connection { c ->
                    val result = mutableMapOf<String, MutableList<Pair<Short, String>>>()
                    c.metaData.getIndexInfo(null, null, "committee_membership", false, false).use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("INDEX_NAME")?.lowercase() ?: continue
                            result.getOrPut(name) { mutableListOf() } +=
                                rs.getShort("ORDINAL_POSITION") to rs.getString("COLUMN_NAME").lowercase()
                        }
                    }
                    result["idx_committee_membership_auto_end"]?.sortedBy { it.first }?.map { it.second }
                }
            index shouldBe listOf("auto_end_reason", "until")
        }

        test("the audit-log CHECK accepts COMMITTEE_MEMBERSHIP, keeps the earlier types and still rejects an unknown one") {
            rejection(auditInsert("COMMITTEE_MEMBERSHIP")) shouldBe null
            rejection(auditInsert("EVENT_IMPORT")) shouldBe null
            rejection(auditInsert("BOARD_MEMBERSHIP")) shouldBe null
            (rejection(auditInsert("BOGUS")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class CommitteeAutoEndMigrationTest : CommitteeAutoEndMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CommitteeAutoEndMigrationPostgresTest : CommitteeAutoEndMigrationScenarios({ PostgresMigrationHarness() })
