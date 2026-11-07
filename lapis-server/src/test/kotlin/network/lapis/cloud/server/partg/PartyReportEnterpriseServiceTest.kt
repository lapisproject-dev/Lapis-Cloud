package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartyReportEnterpriseServiceScenarios]. */
class PartyReportEnterpriseServiceTest : PartyReportEnterpriseServiceScenarios(TestDatabase.H2)
