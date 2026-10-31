package network.lapis.cloud.server.activism

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.246 -- the activism store scenarios on H2 (normal `test` task). */
class ActivismStoreH2Test : ActivismStoreScenarios(TestDatabase.H2)
