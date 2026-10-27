package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.232 -- mailing-attachment route scenarios on H2 (normal `test` task). */
class MailingAttachmentRoutesH2Test : MailingAttachmentRoutesScenarios(TestDatabase.H2)
