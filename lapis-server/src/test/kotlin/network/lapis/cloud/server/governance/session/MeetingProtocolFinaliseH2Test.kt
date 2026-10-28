package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.240 -- the finalising scenarios on H2 (normal `test` task). */
class MeetingProtocolFinaliseH2Test : MeetingProtocolFinaliseScenarios(TestDatabase.H2)
