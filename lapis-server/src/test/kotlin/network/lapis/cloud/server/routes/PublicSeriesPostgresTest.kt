package network.lapis.cloud.server.routes

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.243 -- the series scenarios against a real PostgreSQL (the `postgresTest` task): grouping with `rrule`, anti-join, id tiebreak. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PublicSeriesPostgresTest : PublicSeriesScenarios(TestDatabase.Postgres())
