package network.lapis.cloud.server.partner

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.245 -- the V131 -> V132 upgrade on a real PostgreSQL (the `postgresTest` task), in a scratch schema. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartnerRecursionMigrationPostgresTest : PartnerRecursionMigrationScenarios(TestDatabase.Postgres())
