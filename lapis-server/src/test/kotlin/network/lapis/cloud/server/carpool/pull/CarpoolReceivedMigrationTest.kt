package network.lapis.cloud.server.carpool.pull

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolReceivedMigrationScenarios]. */
class CarpoolReceivedMigrationTest : CarpoolReceivedMigrationScenarios(TestDatabase.H2)
