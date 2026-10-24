package network.lapis.cloud.server.db

import network.lapis.cloud.server.testdb.TestDatabase

/** The V1.9.205 top-up parts on the normal (H2) test lane; see [StagingSeedPartsScenarios]. */
class StagingSeedPartsTest : StagingSeedPartsScenarios(TestDatabase.H2)
