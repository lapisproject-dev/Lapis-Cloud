package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgReportR5bMigrationScenarios]. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReportR5bMigrationPostgresTest : PartgReportR5bMigrationScenarios(TestDatabase.Postgres())
