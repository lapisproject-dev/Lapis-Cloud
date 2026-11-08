package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartgLedgerProposalScenarios]. */
class PartgLedgerProposalTest : PartgLedgerProposalScenarios(TestDatabase.H2)
