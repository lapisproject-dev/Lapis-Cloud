package network.lapis.cloud.server.governance.session

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.240 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): the V130 chain, savepoints, races. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MeetingProtocolFinalisePostgresTest : MeetingProtocolFinaliseScenarios(TestDatabase.Postgres())
