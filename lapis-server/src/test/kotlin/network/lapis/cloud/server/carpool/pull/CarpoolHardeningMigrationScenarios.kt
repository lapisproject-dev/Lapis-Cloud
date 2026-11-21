package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.allTombstones
import network.lapis.cloud.server.carpool.clearTombstones
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.db.generated.PartnerSigningKeyHoldTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.319 -- `V165__carpool_federation_hardening.sql` on H2 AND PostgreSQL: the new columns and the hold table, NULL for every existing row (an old tombstone counts as OWN),
 * and a second run of the file being harmless.
 */
abstract class CarpoolHardeningMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            try {
                clearTombstones()
            } finally {
                db.deactivate()
            }
        }

        fun columns(table: String): Set<String> {
            val found = mutableSetOf<String>()
            transaction {
                exec("SELECT column_name FROM information_schema.columns WHERE table_name = '$table'") { rs ->
                    while (rs.next()) found += rs.getString(1).lowercase()
                }
            }
            return found
        }

        test("the pending key columns, the hold table and the tombstone provenance columns exist with exactly the pinned shape") {
            columns(
                "partner_server",
            ).containsAll(setOf("signing_key_pending_id", "signing_key_pending_pem", "signing_key_changed_at")) shouldBe
                true
            columns("partner_signing_key_hold") shouldBe setOf("host", "trusted_key_sha256", "detected_at")
            columns("carpool_teaser_tombstone") shouldBe
                setOf("id", "teaser_id", "partner_id", "relay_origin_host", "relay_reporter_partner_id", "created_at", "expires_at")
        }

        test("an existing tombstone and an existing partner have NULL in every new column (no backfill)") {
            clearTombstones()
            transaction {
                CarpoolFederationStore.withdrawRelayed(
                    targets =
                        listOf(
                            network.lapis.cloud.server.carpool
                                .CarpoolRelayedTarget(teaserId = hexId("m1"), departureDate = PULL_DEPARTURE, originHost = "o.beispiel.de"),
                        ),
                    partnerId = null,
                    now = PULL_NOW,
                    requireServedSince = null,
                )
                // a row as V161 wrote it: no provenance columns
                exec(
                    "INSERT INTO carpool_teaser_tombstone (id, teaser_id, created_at, expires_at) VALUES " +
                        "('00000000-0000-0000-0000-0000000000a1', '${hexId(
                            "m2",
                        )}', TIMESTAMP '2026-11-19 10:00:00', TIMESTAMP '2026-11-26 00:00:00')",
                )
            }
            val old = allTombstones().single { it[CarpoolTeaserTombstoneTable.teaserId] == hexId("m2") }
            old[CarpoolTeaserTombstoneTable.relayOriginHost] shouldBe null
            old[CarpoolTeaserTombstoneTable.relayReporterPartnerId] shouldBe null
            transaction { PartnerServerTable.selectAll().count { it[PartnerServerTable.signingKeyChangedAt] != null } } shouldBe 0
            transaction { PartnerSigningKeyHoldTable.selectAll().count() } shouldBe 0L
        }

        test("the migration is repeatable") {
            val sql =
                checkNotNull(
                    CarpoolHardeningMigrationScenarios::class.java.classLoader.getResourceAsStream(
                        "db/migration/V165__carpool_federation_hardening.sql",
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
            (statements.size >= 6) shouldBe true
            transaction { statements.forEach { exec(it) } }
            transaction { PartnerSigningKeyHoldTable.selectAll().count() } shouldBe 0L
        }
    })
