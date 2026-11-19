package network.lapis.cloud.server.registration

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [MembershipApplicationSwitchMigrationScenarios]. */
class MembershipApplicationSwitchMigrationTest : MembershipApplicationSwitchMigrationScenarios(TestDatabase.H2)
