package network.lapis.cloud.server.carpool.pull

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolHardeningMigrationScenarios]: V165 on the full Flyway chain. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolHardeningMigrationPostgresTest : CarpoolHardeningMigrationScenarios(TestDatabase.Postgres())
