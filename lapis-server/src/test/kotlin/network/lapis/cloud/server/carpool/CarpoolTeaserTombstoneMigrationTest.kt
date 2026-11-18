package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolTeaserTombstoneMigrationScenarios]. */
class CarpoolTeaserTombstoneMigrationTest : CarpoolTeaserTombstoneMigrationScenarios(TestDatabase.H2)
