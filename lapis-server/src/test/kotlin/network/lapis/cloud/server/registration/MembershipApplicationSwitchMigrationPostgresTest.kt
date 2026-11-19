package network.lapis.cloud.server.registration

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [MembershipApplicationSwitchMigrationScenarios]: the column, its NOT NULL and the repeatable file on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MembershipApplicationSwitchMigrationPostgresTest : MembershipApplicationSwitchMigrationScenarios(TestDatabase.Postgres())
