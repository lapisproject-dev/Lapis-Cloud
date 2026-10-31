package network.lapis.cloud.server.partner

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.244 -- the store scenarios on H2 (normal `test` task). */
class PartnerServerStoreH2Test : PartnerServerStoreScenarios(TestDatabase.H2)
