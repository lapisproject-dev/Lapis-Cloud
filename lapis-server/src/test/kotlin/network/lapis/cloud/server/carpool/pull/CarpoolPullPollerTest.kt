package network.lapis.cloud.server.carpool.pull

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolPullPollerScenarios]. */
class CarpoolPullPollerTest : CarpoolPullPollerScenarios(TestDatabase.H2)
