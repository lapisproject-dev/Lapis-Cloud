package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [ConferenceRecordingRetryScenarios]. */
class ConferenceRecordingRetryTest : ConferenceRecordingRetryScenarios(TestDatabase.H2)
