package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolFederationScenarios]. */
class CarpoolFederationTest : CarpoolFederationScenarios(TestDatabase.H2)
