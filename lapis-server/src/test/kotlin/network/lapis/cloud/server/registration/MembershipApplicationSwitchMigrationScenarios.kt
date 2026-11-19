package network.lapis.cloud.server.registration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.rpc.MembershipApplicationStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.315 -- `V163__organization_settings_membership_application_enabled.sql` on H2 AND PostgreSQL: the column exists, is NOT NULL, the
 * seeded settings row carries TRUE (today's behaviour on every existing instance) and a second run of the file is harmless.
 */
abstract class MembershipApplicationSwitchMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec { db.deactivate() }

        test("the column exists and is NOT NULL") {
            val nullable =
                transaction {
                    exec(
                        "SELECT is_nullable FROM information_schema.columns WHERE lower(table_name) = 'organization_settings' " +
                            "AND lower(column_name) = 'membership_application_enabled'",
                    ) { rs -> if (rs.next()) rs.getString(1) else null }
                }
            nullable shouldBe "NO"
        }

        test("the settings row carries TRUE and the store reads it") {
            transaction { MembershipApplicationStore.load() } shouldBe true
            transaction {
                exec("SELECT membership_application_enabled FROM organization_settings WHERE id = '$ORGANIZATION_SETTINGS_ID'") { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1)
                }
            } shouldBe true
        }

        test("running the migration file again is harmless and keeps the stored value") {
            val sql =
                MembershipApplicationSwitchMigrationScenarios::class.java
                    .getResource("/db/migration/V163__organization_settings_membership_application_enabled.sql")!!
                    .readText()
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 1) shouldBe true
            transaction {
                exec(
                    "UPDATE organization_settings SET membership_application_enabled = FALSE WHERE id = '$ORGANIZATION_SETTINGS_ID'",
                )
            }
            try {
                statements.forEach { statement -> transaction { exec(statement) } }
                transaction { MembershipApplicationStore.load() } shouldBe false
            } finally {
                transaction {
                    exec(
                        "UPDATE organization_settings SET membership_application_enabled = TRUE WHERE id = '$ORGANIZATION_SETTINGS_ID'",
                    )
                }
            }
        }
    })
