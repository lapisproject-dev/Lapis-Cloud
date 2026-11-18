package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolFederationMigrationScenarios]. */
class CarpoolFederationMigrationTest : CarpoolFederationMigrationScenarios(TestDatabase.H2)
