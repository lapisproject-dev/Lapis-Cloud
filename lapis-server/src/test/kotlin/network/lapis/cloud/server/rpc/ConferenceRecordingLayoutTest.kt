package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [ConferenceRecordingLayoutScenarios]. */
class ConferenceRecordingLayoutTest : ConferenceRecordingLayoutScenarios(TestDatabase.H2)
