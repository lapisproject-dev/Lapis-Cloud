package network.lapis.cloud.server.member

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.60 -- H2 compatibility smoke test of the member row lock sites (no concurrency; the real proofs run on PostgreSQL). */
class MemberRowLockH2Test : MemberRowLockScenarios(TestDatabase.H2)
