package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.199 -- organisation-document route scenarios on H2 (normal `test` task). */
class TravelExpenseDocumentRoutesH2Test : TravelExpenseDocumentRoutesScenarios(TestDatabase.H2)
