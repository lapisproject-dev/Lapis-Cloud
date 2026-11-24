package network.lapis.cloud.server.auction.federation

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [AuctionFederationStoreScenarios]. */
class AuctionFederationStoreTest : AuctionFederationStoreScenarios(TestDatabase.H2)
