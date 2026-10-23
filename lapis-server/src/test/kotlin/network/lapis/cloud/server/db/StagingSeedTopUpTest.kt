package network.lapis.cloud.server.db

import network.lapis.cloud.server.testdb.TestDatabase

/** The staging LTR top-up scenarios on the normal (H2) test lane; see [StagingSeedTopUpScenarios]. */
class StagingSeedTopUpTest : StagingSeedTopUpScenarios(TestDatabase.H2)
