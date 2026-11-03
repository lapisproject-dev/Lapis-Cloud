package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartyReportAnnexServiceScenarios]. */
class PartyReportAnnexServiceTest : PartyReportAnnexServiceScenarios(TestDatabase.H2)
