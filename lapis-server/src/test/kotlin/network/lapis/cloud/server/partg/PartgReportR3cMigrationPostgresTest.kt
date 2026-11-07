package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgReportR3cMigrationScenarios]. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReportR3cMigrationPostgresTest : PartgReportR3cMigrationScenarios(TestDatabase.Postgres())
