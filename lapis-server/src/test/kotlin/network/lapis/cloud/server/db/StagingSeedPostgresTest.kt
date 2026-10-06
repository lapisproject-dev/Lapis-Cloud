package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** The staging seed scenarios against a real PostgreSQL (the `postgresTest` lane); skipped when `LAPIS_TEST_POSTGRES_URL` is not set. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class StagingSeedPostgresTest : StagingSeedScenarios(TestDatabase.Postgres())
