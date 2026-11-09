package network.lapis.cloud.server.bulletin

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.297 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): row locks, no deadlock between notice and member. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class NoticeBoardPostgresTest : NoticeBoardScenarios(TestDatabase.Postgres())
