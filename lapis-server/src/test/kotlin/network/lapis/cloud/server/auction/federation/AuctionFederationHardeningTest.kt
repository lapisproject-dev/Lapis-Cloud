package network.lapis.cloud.server.auction.federation

import network.lapis.cloud.server.testdb.TestDatabase

class AuctionFederationHardeningTest : AuctionFederationHardeningScenarios(TestDatabase.H2)
