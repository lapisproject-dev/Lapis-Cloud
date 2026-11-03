package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartgReportR3MigrationScenarios]. */
class PartgReportR3MigrationTest : PartgReportR3MigrationScenarios(TestDatabase.H2)
