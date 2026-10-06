package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.62 -- the encounter WebView bridge scenarios on H2 (the normal `test` task). */
class MobileEncounterWebviewSessionTest : MobileEncounterWebviewSessionScenarios(TestDatabase.H2)
