package network.lapis.cloud.server.bulletin

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.297 -- the bulletin-board scenarios on H2 (normal `test` task). */
class NoticeBoardH2Test : NoticeBoardScenarios(TestDatabase.H2)
