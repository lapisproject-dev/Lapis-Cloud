package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

class AuctionFiatServiceH2Test : AuctionFiatServiceScenarios(TestDatabase.H2)
