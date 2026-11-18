package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.312 -- the columns and the CHECK of `V160__carpool_teaser_feed.sql` on H2 AND PostgreSQL: a partner written WITHOUT the new columns ends on the
 * safe defaults (no pinned key, zero counters), the audit literal is accepted, an unknown literal is refused, and a second run of the file is harmless.
 */
abstract class CarpoolTeaserFeedMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec { db.deactivate() }

        fun refused(block: () -> Unit): Boolean =
            try {
                block()
                false
            } catch (_: Exception) {
                true
            }

        test("a partner written without the new columns has no pinned key and zero counters") {
            val partnerId = Uuid.random()
            transaction {
                exec(
                    "INSERT INTO partner_server (id, host, created_at, next_fetch_at) " +
                        "VALUES ('$partnerId', 'mig-" + partnerId.toString().take(n = 8) + ".example.org', " +
                        "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
                )
            }
            val values =
                transaction {
                    var row: List<Any?> = emptyList()
                    exec(
                        "SELECT signing_key_id, signing_public_key_pem, signing_key_pinned_at, carpool_feed_served_at, " +
                            "carpool_feed_served_count, carpool_feed_last_items FROM partner_server WHERE id = '$partnerId'",
                    ) { rs ->
                        if (rs.next()) row = (1..6).map { rs.getObject(it) }
                    }
                    row
                }
            values.take(n = 4) shouldBe listOf(null, null, null, null)
            (values[4] as Number).toLong() shouldBe 0L
            values[5] shouldBe null
            transaction { exec("DELETE FROM partner_server WHERE id = '$partnerId'") }
        }

        test("the audit log accepts the new entity type and still refuses an unknown one") {
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.CARPOOL_FEDERATION_FEED,
                    entityId = Uuid.random(),
                    action = AuditAction.CREATE,
                    after = "{\"itemCount\":0,\"complete\":true}",
                )
            }
            val unknownLiteral =
                "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                    "VALUES ('${Uuid.random()}', 9000000001, TIMESTAMP '2026-01-01 00:00:00', 'NOT_A_TYPE', '${Uuid.random()}', " +
                    "'CREATE', '" + "0".repeat(n = 64) + "')"
            refused { transaction { exec(unknownLiteral) } } shouldBe true
        }

        test("the migration is repeatable") {
            val sql =
                checkNotNull(
                    CarpoolTeaserFeedMigrationScenarios::class.java.classLoader.getResourceAsStream(
                        "db/migration/V160__carpool_teaser_feed.sql",
                    ),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 8) shouldBe true
            // Replay the audit CHECK with the CURRENT type list: replaying it as written would drop the entity types of later waves from the shared test schema.
            val currentTypes = AuditEntityType.entries.joinToString(", ") { "'${it.name}'" }
            transaction {
                statements.forEach { statement ->
                    if (statement.contains("chk_audit_log_entry_entity_type") && statement.contains("ADD CONSTRAINT")) {
                        exec(
                            "ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type CHECK (entity_type IN ($currentTypes))",
                        )
                    } else {
                        exec(statement)
                    }
                }
            }
        }
    })
