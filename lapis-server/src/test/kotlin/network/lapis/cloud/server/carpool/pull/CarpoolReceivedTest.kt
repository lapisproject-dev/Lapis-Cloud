package network.lapis.cloud.server.carpool.pull

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolReceivedScenarios]. */
class CarpoolReceivedTest : CarpoolReceivedScenarios(TestDatabase.H2)
