package network.lapis.cloud.server.bulletin

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [BulletinBoardMigrationScenarios]. */
class BulletinBoardMigrationTest : BulletinBoardMigrationScenarios(TestDatabase.H2)
