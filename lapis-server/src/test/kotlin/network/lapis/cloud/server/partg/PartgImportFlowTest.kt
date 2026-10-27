package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartgImportFlowScenarios]. */
class PartgImportFlowTest : PartgImportFlowScenarios(TestDatabase.H2)
