package network.lapis.cloud.server.carpool

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolTeaserTombstoneMigrationScenarios]: V161 on the full Flyway chain. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolTeaserTombstoneMigrationPostgresTest : CarpoolTeaserTombstoneMigrationScenarios(TestDatabase.Postgres())
