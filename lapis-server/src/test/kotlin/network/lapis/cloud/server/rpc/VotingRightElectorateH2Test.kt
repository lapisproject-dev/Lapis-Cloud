package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.298 -- the voting-right electorate scenarios on H2. */
class VotingRightElectorateH2Test : VotingRightElectorateScenarios(TestDatabase.H2)
