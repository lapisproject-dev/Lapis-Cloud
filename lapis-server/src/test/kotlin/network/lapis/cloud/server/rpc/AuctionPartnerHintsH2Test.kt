package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [AuctionPartnerHintsScenarios]. */
class AuctionPartnerHintsH2Test : AuctionPartnerHintsScenarios(TestDatabase.H2)
