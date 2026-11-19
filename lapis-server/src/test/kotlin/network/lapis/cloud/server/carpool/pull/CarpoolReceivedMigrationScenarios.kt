package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.314 -- `V162__carpool_federation_pull.sql` on H2 AND PostgreSQL: the exact columns (nothing personal), the added pull and pin columns, and a second
 * run of the file being harmless. The constraints and foreign keys are exercised by [CarpoolReceivedScenarios].
 */
abstract class CarpoolReceivedMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec { db.deactivate() }

        fun columns(table: String): Set<String> {
            val found = mutableSetOf<String>()
            transaction {
                exec("SELECT column_name FROM information_schema.columns WHERE table_name = '$table'") { rs ->
                    while (rs.next()) found += rs.getString(1).lowercase()
                }
            }
            return found
        }

        test("the two new tables have exactly the pinned columns: a foreign hint holds no member, trip, name, contact or place") {
            columns("carpool_received_hint") shouldBe
                setOf(
                    "id",
                    "teaser_id",
                    "origin_host",
                    "hops",
                    "max_hops",
                    "via_hosts",
                    "source_partner_id",
                    "departure_date",
                    "from_postcode_area",
                    "to_postcode_area",
                    "origin_signature",
                    "received_at",
                    "last_seen_at",
                    "expires_at",
                )
            columns("carpool_received_tombstone") shouldBe
                setOf("id", "teaser_id", "origin_host", "reported_by_partner_id", "created_at", "expires_at")
        }

        test("the pull state columns of partner_server and the pin columns of partner_discovered_server exist") {
            val pull =
                setOf(
                    "carpool_pull_at",
                    "carpool_pull_ok_at",
                    "carpool_pull_next_at",
                    "carpool_pull_failures",
                    "carpool_pull_status",
                    "carpool_pull_error",
                    "carpool_pull_etag",
                    "carpool_pull_last_accepted",
                    "carpool_pull_last_rejected",
                )
            columns("partner_server").containsAll(pull) shouldBe true
            columns(
                "partner_discovered_server",
            ).containsAll(setOf("signing_key_id", "signing_public_key_pem", "signing_key_pinned_at")) shouldBe
                true
        }

        test("the migration is repeatable") {
            val sql =
                checkNotNull(
                    CarpoolReceivedMigrationScenarios::class.java.classLoader.getResourceAsStream(
                        "db/migration/V162__carpool_federation_pull.sql",
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
            (statements.size >= 20) shouldBe true
            transaction { statements.forEach { exec(it) } }
            transaction { CarpoolReceivedHintTable.selectAll().count() } shouldBe 0L
            transaction { CarpoolReceivedTombstoneTable.deleteAll() }
        }
    })
