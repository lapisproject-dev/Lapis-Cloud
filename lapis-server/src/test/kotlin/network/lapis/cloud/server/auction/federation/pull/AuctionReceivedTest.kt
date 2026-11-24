package network.lapis.cloud.server.auction.federation.pull

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [AuctionReceivedScenarios]. */
class AuctionReceivedTest : AuctionReceivedScenarios(TestDatabase.H2)
