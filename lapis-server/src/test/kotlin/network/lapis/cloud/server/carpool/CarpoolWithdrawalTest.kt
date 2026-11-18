package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolWithdrawalScenarios]. */
class CarpoolWithdrawalTest : CarpoolWithdrawalScenarios(TestDatabase.H2)
