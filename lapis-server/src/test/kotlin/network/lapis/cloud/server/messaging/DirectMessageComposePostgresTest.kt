package network.lapis.cloud.server.messaging

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.237 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): V128 chain, collation-stable order, no 25P02 on a double block. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DirectMessageComposePostgresTest : DirectMessageComposeScenarios(TestDatabase.Postgres())
