package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartgReportMigrationScenarios]. */
class PartgReportMigrationTest : PartgReportMigrationScenarios(TestDatabase.H2)
