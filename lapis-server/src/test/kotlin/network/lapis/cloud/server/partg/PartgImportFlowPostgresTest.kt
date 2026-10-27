package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgImportFlowScenarios]: the routes against real row locks and the real CHECKs. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgImportFlowPostgresTest : PartgImportFlowScenarios(TestDatabase.Postgres())
