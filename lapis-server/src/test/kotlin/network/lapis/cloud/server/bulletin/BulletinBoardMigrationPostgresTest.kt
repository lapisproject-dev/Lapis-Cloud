package network.lapis.cloud.server.bulletin

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [BulletinBoardMigrationScenarios]: the `INTERVAL '90' DAY` CHECK and the widened ledger / audit CHECKs on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class BulletinBoardMigrationPostgresTest : BulletinBoardMigrationScenarios(TestDatabase.Postgres())
