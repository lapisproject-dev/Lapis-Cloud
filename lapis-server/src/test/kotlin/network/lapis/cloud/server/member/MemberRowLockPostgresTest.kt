package network.lapis.cloud.server.member

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.60 -- the member row lock scenarios against a real PostgreSQL. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberRowLockPostgresTest : MemberRowLockScenarios(TestDatabase.Postgres())
