package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [TodaysAnniversaryQueryScenarios]. */
class TodaysAnniversaryQueryTest : TodaysAnniversaryQueryScenarios(TestDatabase.H2)
