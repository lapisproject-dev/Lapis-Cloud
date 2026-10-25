package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [MemberAdminLastLoginScenarios]. */
class MemberAdminLastLoginRpcTest : MemberAdminLastLoginScenarios(TestDatabase.H2)

/** Postgres lane: real NULLS FIRST/LAST emission and the real Flyway chain up to V118. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberAdminLastLoginPostgresTest : MemberAdminLastLoginScenarios(TestDatabase.Postgres())
