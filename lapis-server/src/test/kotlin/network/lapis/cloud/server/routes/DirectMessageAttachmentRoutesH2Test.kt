package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.233 -- direct-message attachment route scenarios on H2 (normal `test` task). */
class DirectMessageAttachmentRoutesH2Test : DirectMessageAttachmentRoutesScenarios(TestDatabase.H2)
