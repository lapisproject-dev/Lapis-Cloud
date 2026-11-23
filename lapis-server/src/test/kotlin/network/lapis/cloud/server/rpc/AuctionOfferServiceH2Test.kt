package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

class AuctionOfferServiceH2Test : AuctionOfferServiceScenarios(TestDatabase.H2)
