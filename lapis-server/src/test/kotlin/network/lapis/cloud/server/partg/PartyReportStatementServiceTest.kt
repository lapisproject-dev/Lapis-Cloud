package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartyReportStatementServiceScenarios]. */
class PartyReportStatementServiceTest : PartyReportStatementServiceScenarios(TestDatabase.H2)
