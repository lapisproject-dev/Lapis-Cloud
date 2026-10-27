package network.lapis.cloud.server.lupabb

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [LupaBbSyncServiceScenarios]. */
class LupaBbSyncServiceTest : LupaBbSyncServiceScenarios(TestDatabase.H2)
