package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [ConferenceRecordingRecomputeScenarios]. */
class ConferenceRecordingRecomputeTest : ConferenceRecordingRecomputeScenarios(TestDatabase.H2)
