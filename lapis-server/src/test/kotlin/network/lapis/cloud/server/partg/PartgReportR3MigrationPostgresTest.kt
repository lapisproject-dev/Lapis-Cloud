package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgReportR3MigrationScenarios]: the CHECKs, UNIQUE and FOREIGN KEYs on the real dialect. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReportR3MigrationPostgresTest : PartgReportR3MigrationScenarios(TestDatabase.Postgres())
