package network.lapis.cloud.server.db

import network.lapis.cloud.server.testdb.TestDatabase

/** The staging seed scenarios on the normal (H2) test lane; see [StagingSeedScenarios]. */
class StagingSeedTest : StagingSeedScenarios(TestDatabase.H2)
