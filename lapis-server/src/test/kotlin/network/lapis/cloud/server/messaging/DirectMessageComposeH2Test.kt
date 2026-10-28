package network.lapis.cloud.server.messaging

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.237 -- "Neue Nachricht" schema and ordering scenarios on H2 (normal `test` task). */
class DirectMessageComposeH2Test : DirectMessageComposeScenarios(TestDatabase.H2)
