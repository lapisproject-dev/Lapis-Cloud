package network.lapis.cloud.server.partner

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.245 -- the V131 -> V132 upgrade on H2 (normal `test` task). */
class PartnerRecursionMigrationH2Test : PartnerRecursionMigrationScenarios(TestDatabase.H2)
