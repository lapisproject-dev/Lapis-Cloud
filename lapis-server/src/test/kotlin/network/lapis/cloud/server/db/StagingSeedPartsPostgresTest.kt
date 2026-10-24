package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** The V1.9.205 top-up parts against a real PostgreSQL (the `postgresTest` lane); skipped when `LAPIS_TEST_POSTGRES_URL` is not set. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class StagingSeedPartsPostgresTest : StagingSeedPartsScenarios(TestDatabase.Postgres())
