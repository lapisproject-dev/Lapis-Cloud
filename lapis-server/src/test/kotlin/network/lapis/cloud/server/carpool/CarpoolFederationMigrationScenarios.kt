package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.311 -- the columns and the CHECK of `V159__carpool_federation_foundation.sql` on H2 AND PostgreSQL: rows written WITHOUT the new columns end on
 * the safe defaults (a pre-V159 row is "not shared / switched off"), the hop limit accepts 1..3 only, the audit literal is accepted and a second run of
 * the file is harmless. Every refused statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the transaction).
 */
abstract class CarpoolFederationMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            try {
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun refused(block: () -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun setHop(value: Int) =
            transaction {
                exec("UPDATE organization_settings SET carpool_federation_hop_limit = $value WHERE id = '$ORGANIZATION_SETTINGS_ID'")
            }

        test("a trip and a partner written without the new columns end on the safe defaults (not shared)") {
            val author = data.member(label = "mig-author")
            val postingId = Uuid.random()
            val partnerId = Uuid.random()
            val partnerHost = "mig-" + partnerId.toString().take(n = 8) + ".example.org"
            transaction {
                exec(
                    "INSERT INTO carpool_posting (id, author_member_id, type, from_place, to_place, departure_date, seats_offered, " +
                        "created_at, updated_at) " +
                        "VALUES ('$postingId', '$author', 'OFFER', '38100 A', '30159 B', DATE '2030-01-01', 2, " +
                        "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
                )
                exec(
                    "INSERT INTO partner_server (id, host, created_at, next_fetch_at) " +
                        "VALUES ('$partnerId', '$partnerHost', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
                )
            }
            transaction { CarpoolFederationStore.optIn(postingId) } shouldBe false
            val sharing =
                transaction {
                    var value: Boolean? = null
                    exec("SELECT carpool_sharing FROM partner_server WHERE id = '$partnerId'") { rs ->
                        if (rs.next()) {
                            value =
                                rs.getBoolean(1)
                        }
                    }
                    value
                }
            sharing shouldBe false
            transaction { exec("DELETE FROM partner_server WHERE id = '$partnerId'") }
            transaction { exec("DELETE FROM carpool_posting WHERE id = '$postingId'") }
        }

        test("the hop limit accepts 1..3 only") {
            (1..3).forEach { setHop(it) }
            refused { setHop(0) } shouldBe true
            refused { setHop(4) } shouldBe true
            refused { setHop(-1) } shouldBe true
            setHop(2)
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()[OrganizationSettingsTable.carpoolFederationHopLimit]
                    .toInt()
            } shouldBe 2
        }

        test("the audit log accepts the new entity type") {
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.CARPOOL_FEDERATION_OPT_IN,
                    entityId = Uuid.random(),
                    action = AuditAction.UPDATE,
                    after = "{\"optIn\":true}",
                )
            }
        }

        test("the migration is repeatable and keeps the hop-limit rule in force") {
            val sql =
                checkNotNull(
                    CarpoolFederationMigrationScenarios::class.java.classLoader.getResourceAsStream(
                        "db/migration/V159__carpool_federation_foundation.sql",
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
            // Replaying the audit CHECK exactly as written would drop the entity types of LATER waves from the shared test schema; replay it with
            // the current type list (the literal of this file is asserted by the previous test).
            val currentTypes = AuditEntityType.entries.joinToString(", ") { "'${it.name}'" }
            transaction {
                statements.forEach { statement ->
                    if (statement.contains("chk_audit_log_entry_entity_type") && statement.contains("ADD CONSTRAINT")) {
                        exec(
                            "ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type " +
                                "CHECK (entity_type IN ($currentTypes))",
                        )
                    } else {
                        exec(statement)
                    }
                }
            }
            refused { setHop(0) } shouldBe true
            refused { setHop(4) } shouldBe true
            setHop(2)
        }
    })
