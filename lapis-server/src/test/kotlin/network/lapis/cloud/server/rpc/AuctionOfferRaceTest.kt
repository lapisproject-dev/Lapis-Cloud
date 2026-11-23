package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

class AuctionOfferRaceTest : AuctionOfferRaceScenarios(TestDatabase.H2)
