package network.lapis.cloud.server.activism

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.246 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): the races of the caps, no `25P02` after a unique violation. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ActivismStorePostgresTest : ActivismStoreScenarios(TestDatabase.Postgres())
