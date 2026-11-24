package network.lapis.cloud.server.auction.federation.feed

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [AuctionTeaserFeedScenarios]. */
class AuctionTeaserFeedTest : AuctionTeaserFeedScenarios(TestDatabase.H2)
