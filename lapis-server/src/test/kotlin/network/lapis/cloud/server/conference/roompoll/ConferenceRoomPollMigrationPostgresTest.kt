package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [ConferenceRoomPollMigrationScenarios]: the lifecycle CHECKs, the unique open poll and the widened audit CHECK on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceRoomPollMigrationPostgresTest : ConferenceRoomPollMigrationScenarios(TestDatabase.Postgres())
