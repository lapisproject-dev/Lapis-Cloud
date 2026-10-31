package network.lapis.cloud.server.partner

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.245 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): cascades, no `25P02` after the unique violation, the races. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartnerRecursionStorePostgresTest : PartnerRecursionStoreScenarios(TestDatabase.Postgres())
