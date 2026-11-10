package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.298 -- the voting-right electorate scenarios against a real PostgreSQL. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class VotingRightElectorateScenariosPostgresTest : VotingRightElectorateScenarios(TestDatabase.Postgres())
