package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgReportR5bScenarios]: the locks, the unique index and the sums on the real dialect. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReportR5bPostgresTest : PartgReportR5bScenarios(TestDatabase.Postgres())
