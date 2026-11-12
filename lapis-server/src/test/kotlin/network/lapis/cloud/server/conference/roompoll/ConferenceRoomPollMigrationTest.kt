package network.lapis.cloud.server.conference.roompoll

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [ConferenceRoomPollMigrationScenarios]. */
class ConferenceRoomPollMigrationTest : ConferenceRoomPollMigrationScenarios(TestDatabase.H2)
