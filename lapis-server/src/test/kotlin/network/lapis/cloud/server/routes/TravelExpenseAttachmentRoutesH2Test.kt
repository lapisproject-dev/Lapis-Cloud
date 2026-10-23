package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.198 -- attachment route scenarios on H2 (normal `test` task). */
class TravelExpenseAttachmentRoutesH2Test : TravelExpenseAttachmentRoutesScenarios(TestDatabase.H2)
