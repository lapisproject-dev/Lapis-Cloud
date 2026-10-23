package network.lapis.cloud.server.routes

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.199 -- the document route scenarios (incl. the parallel-upload quota lock) against a real PostgreSQL (the `postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TravelExpenseDocumentRoutesPostgresTest : TravelExpenseDocumentRoutesScenarios(TestDatabase.Postgres())
