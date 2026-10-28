package network.lapis.cloud.server.governance.session

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.239 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): the V129 chain, repeatable, constraints, savepoints, races. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MeetingProtocolDraftPostgresTest : MeetingProtocolDraftScenarios(TestDatabase.Postgres())
