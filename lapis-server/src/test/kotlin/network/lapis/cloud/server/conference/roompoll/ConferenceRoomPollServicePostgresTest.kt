package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.304 -- the same scenarios against a real PostgreSQL (the `postgresTest` task): row locks, no deadlock between room, poll and audit. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceRoomPollServicePostgresTest : ConferenceRoomPollServiceScenarios(TestDatabase.Postgres())
