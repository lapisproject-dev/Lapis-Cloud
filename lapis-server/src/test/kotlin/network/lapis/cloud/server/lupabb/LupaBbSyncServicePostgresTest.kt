package network.lapis.cloud.server.lupabb

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [LupaBbSyncServiceScenarios]: the real Flyway chain up to V122, row locks, the guard's compare-and-set, savepoints. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class LupaBbSyncServicePostgresTest : LupaBbSyncServiceScenarios(TestDatabase.Postgres())
