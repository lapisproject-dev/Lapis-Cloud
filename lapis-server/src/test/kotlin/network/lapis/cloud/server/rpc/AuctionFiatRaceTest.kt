package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

class AuctionFiatRaceTest : AuctionFiatRaceScenarios(TestDatabase.H2)
