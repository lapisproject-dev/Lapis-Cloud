package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.239 -- the Protokoll draft scenarios on H2 (normal `test` task). */
class MeetingProtocolDraftH2Test : MeetingProtocolDraftScenarios(TestDatabase.H2)
