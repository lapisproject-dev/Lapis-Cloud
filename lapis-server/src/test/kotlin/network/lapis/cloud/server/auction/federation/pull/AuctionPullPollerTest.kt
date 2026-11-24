package network.lapis.cloud.server.auction.federation.pull

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [AuctionPullPollerScenarios]. */
class AuctionPullPollerTest : AuctionPullPollerScenarios(TestDatabase.H2)
