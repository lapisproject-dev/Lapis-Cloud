package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.243 -- the series scenarios on H2 (the normal `test` task). */
class PublicSeriesScenariosTest : PublicSeriesScenarios(TestDatabase.H2)
