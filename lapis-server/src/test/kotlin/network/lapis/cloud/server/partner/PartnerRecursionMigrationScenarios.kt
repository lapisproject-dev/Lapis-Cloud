package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * V1.9.245 -- `V132__partner_recursion.sql` on the real upgrade path V131 -> V132, on H2 and on PostgreSQL: the old events get their origin host,
 * the new unique constraint is in force after the duplicate guard ran, the CHECK and CASCADE rules bite, a re-run is a no-op and V131 stays
 * untouched (its checksum is frozen: `validate()` after the upgrade).
 *
 * On PostgreSQL the upgrade runs in a scratch schema of the spec's own database, so the template database stays migrated.
 */
abstract class PartnerRecursionMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        installLaneGuards(db = db)
        val postgres = db as? TestDatabase.Postgres
        val h2Url = "jdbc:h2:mem:p2-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val schema = "p2mig"

        beforeSpec {
            db.activate()
            postgres?.db?.rawConnection()?.use { it.createStatement().use { st -> st.execute("CREATE SCHEMA IF NOT EXISTS $schema") } }
        }
        afterSpec { db.deactivate() }

        fun flyway(target: String?): Flyway {
            val configuration =
                if (postgres == null) {
                    Flyway.configure().dataSource(h2Url, "sa", "")
                } else {
                    Flyway.configure().dataSource(postgres.db.jdbcUrl, postgres.db.user, postgres.db.password).schemas(schema)
                }
            configuration.locations("classpath:db/migration")
            if (target != null) configuration.target(target)
            return configuration.load()
        }

        fun <T> connected(block: (Connection) -> T): T {
            val connection =
                if (postgres == null) {
                    DriverManager.getConnection(h2Url, "sa", "")
                } else {
                    DriverManager.getConnection(postgres.db.jdbcUrl, postgres.db.user, postgres.db.password)
                }
            return connection.use {
                if (postgres != null) it.createStatement().use { st -> st.execute("SET search_path TO $schema") }
                block(it)
            }
        }

        fun exec(sql: String) = connected { c -> c.createStatement().use { it.execute(sql) } }

        fun rejected(sql: String): Boolean = runCatching { exec(sql) }.isFailure

        fun <T> scalar(
            sql: String,
            read: (java.sql.ResultSet) -> T,
        ): T =
            connected { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        rs.next()
                        read(rs)
                    }
                }
            }

        fun count(sql: String): Int = scalar(sql) { it.getInt(1) }

        test(
            "V131 -> V132: origin host and hops are filled, a duplicate is dropped, the new unique, CHECKs and CASCADE bite, a re-run is a no-op",
        ) {
            flyway("131").migrate()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val remote = UUID.randomUUID()
            val remoteB = UUID.randomUUID()
            listOf(a to "a.beispiel.de", b to "b.beispiel.de").forEach { (id, host) ->
                exec(
                    "INSERT INTO partner_server (id, host, active, created_at, last_status, " +
                        "event_count, consecutive_failures, next_fetch_at) " +
                        "VALUES ('$id', '$host', TRUE, TIMESTAMP '2026-11-01 10:00:00', 'OK', 0, 0, TIMESTAMP '2026-11-05 11:00:00')",
                )
            }

            fun event(
                id: UUID,
                partner: UUID,
                remoteId: UUID,
                eventId: String,
            ) = exec(
                "INSERT INTO partner_event (id, partner_server_id, remote_server_id, " +
                    "remote_event_id, title, starts_at, ends_at, time_zone, " +
                    "online, url, fetched_at, expires_at) VALUES ('$id', '$partner', '$remoteId', " +
                    "'$eventId', 'T', TIMESTAMP '2026-11-12 18:00:00', " +
                    "TIMESTAMP '2026-11-12 20:00:00', 'Europe/Berlin', FALSE, " +
                    "'https://x.beispiel.de/e', TIMESTAMP '2026-11-05 10:00:00', " +
                    "TIMESTAMP '2026-11-05 16:00:00')",
            )
            val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val high = UUID.fromString("ffffffff-0000-0000-0000-000000000001")
            event(UUID.randomUUID(), a, remote, "e1")
            // The same (announced server id, event id) under two partner rows: impossible by P1's invariants, but the guard must cope with it.
            event(low, a, remoteB, "dup")
            event(high, b, remoteB, "dup")
            flyway(null).migrate()

            count("SELECT COUNT(*) FROM partner_event") shouldBe 2
            count("SELECT COUNT(*) FROM partner_event WHERE id = '$low'") shouldBe 1
            count("SELECT COUNT(*) FROM partner_event WHERE id = '$high'") shouldBe 0
            count("SELECT COUNT(*) FROM partner_event WHERE hops = 1 AND discovered_server_id IS NULL AND via_hosts IS NULL") shouldBe 2
            count("SELECT COUNT(*) FROM partner_event WHERE partner_server_id = '$a' AND origin_host = 'a.beispiel.de'") shouldBe 2
            scalar("SELECT partner_recursion_enabled FROM organization_settings") { it.getBoolean(1) } shouldBe true
            count("SELECT partner_recursion_depth FROM organization_settings") shouldBe 2
            rejected("UPDATE organization_settings SET partner_recursion_depth = 0") shouldBe true
            rejected("UPDATE organization_settings SET partner_recursion_depth = 4") shouldBe true
            // origin_host is NOT NULL now
            rejected(
                "INSERT INTO partner_event (id, partner_server_id, remote_server_id, remote_event_id, " +
                    "title, starts_at, ends_at, time_zone, online, " +
                    "url, fetched_at, expires_at) VALUES ('${UUID.randomUUID()}', '$a', '$remote', " +
                    "'n', 'T', TIMESTAMP '2026-11-12 18:00:00', " +
                    "TIMESTAMP '2026-11-12 20:00:00', 'UTC', FALSE, 'https://x/e', TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 16:00:00')",
            ) shouldBe true

            // the new unique constraint: one foreign event exists once, whoever it was reached through
            val d = UUID.randomUUID()
            exec(
                "INSERT INTO partner_discovered_server (id, host, remote_server_id, " +
                    "root_partner_server_id, hops, created_at, next_fetch_at) " +
                    "VALUES ('$d', 'd.beispiel.de', '${UUID.randomUUID()}', '$a', 2, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 11:00:00')",
            )
            val insertDiscoveredEvent = { id: UUID, remoteId: UUID, eventId: String, hops: Int ->
                "INSERT INTO partner_event (id, partner_server_id, discovered_server_id, hops, " +
                    "origin_host, remote_server_id, remote_event_id, title, " +
                    "starts_at, ends_at, time_zone, online, url, fetched_at, expires_at) VALUES " +
                    "('$id', '$a', '$d', $hops, 'd.beispiel.de', " +
                    "'$remoteId', '$eventId', 'T', TIMESTAMP '2026-11-12 18:00:00', TIMESTAMP '2026-11-12 20:00:00', 'UTC', FALSE, " +
                    "'https://d.beispiel.de/e', TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 16:00:00')"
            }
            val remoteD = UUID.randomUUID()
            exec(insertDiscoveredEvent(UUID.randomUUID(), remoteD, "x", 2))
            rejected(insertDiscoveredEvent(UUID.randomUUID(), remoteD, "x", 2)) shouldBe true
            rejected(insertDiscoveredEvent(UUID.randomUUID(), remoteD, "y", 5)) shouldBe true
            rejected(
                "INSERT INTO partner_discovered_server (id, host, root_partner_server_id, hops, created_at, next_fetch_at) VALUES ('${UUID.randomUUID()}', 'e.beispiel.de', '$a', 1, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 11:00:00')",
            ) shouldBe
                true
            rejected(
                "INSERT INTO partner_discovered_server (id, host, root_partner_server_id, hops, created_at, next_fetch_at) VALUES ('${UUID.randomUUID()}', 'D.beispiel.de', '$a', 2, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 11:00:00')",
            ) shouldBe
                true
            rejected(
                "INSERT INTO partner_discovered_server (id, host, root_partner_server_id, hops, created_at, next_fetch_at) VALUES ('${UUID.randomUUID()}', 'd.beispiel.de', '$a', 2, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 11:00:00')",
            ) shouldBe
                true

            // CASCADE: deleting the direct partner removes the discovered row and every event under it
            exec("DELETE FROM partner_server WHERE id = '$a'")
            count("SELECT COUNT(*) FROM partner_discovered_server") shouldBe 0
            count("SELECT COUNT(*) FROM partner_event WHERE partner_server_id = '$a'") shouldBe 0

            flyway(null).migrate().migrationsExecuted shouldBe 0
            flyway(null).validate()
        }
    })
