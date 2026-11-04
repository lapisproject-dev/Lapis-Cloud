package network.lapis.cloud.server.routes

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.278 -- the embed-feed series scenarios against a real PostgreSQL (the `postgresTest` task): id tiebreak, anti-join, grouping. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EmbedEventsFeedSeriesPostgresTest : EmbedEventsFeedSeriesScenarios(TestDatabase.Postgres())
