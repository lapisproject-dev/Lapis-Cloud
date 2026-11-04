package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [TodaysAnniversaryQueryScenarios] (`EXTRACT`-based month/day filter on the real dialect). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TodaysAnniversaryQueryPostgresTest : TodaysAnniversaryQueryScenarios(TestDatabase.Postgres())
