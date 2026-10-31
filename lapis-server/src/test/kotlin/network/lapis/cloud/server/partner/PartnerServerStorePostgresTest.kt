package network.lapis.cloud.server.partner

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.244 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): V131 on the full Flyway chain, no `25P02` after a lost insert race. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartnerServerStorePostgresTest : PartnerServerStoreScenarios(TestDatabase.Postgres())
