package network.lapis.cloud.server.auction

import network.lapis.cloud.server.testdb.TestDatabase

class AuctionStatusMigrationTest : AuctionStatusMigrationScenarios(TestDatabase.H2)
