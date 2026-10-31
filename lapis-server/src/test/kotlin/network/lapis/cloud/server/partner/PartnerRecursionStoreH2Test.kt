package network.lapis.cloud.server.partner

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.245 -- the discovery store scenarios on H2 (normal `test` task). */
class PartnerRecursionStoreH2Test : PartnerRecursionStoreScenarios(TestDatabase.H2)
