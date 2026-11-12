package network.lapis.cloud.server.conference.roompoll

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.304 -- the room-poll scenarios on H2 (normal `test` task). */
class ConferenceRoomPollServiceH2Test : ConferenceRoomPollServiceScenarios(TestDatabase.H2)
