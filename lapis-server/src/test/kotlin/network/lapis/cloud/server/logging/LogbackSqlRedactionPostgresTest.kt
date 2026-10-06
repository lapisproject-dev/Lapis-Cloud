package network.lapis.cloud.server.logging

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** The same scenarios with a real PSQLException and a real `Detail: Key (email)=(...)` line. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class LogbackSqlRedactionPostgresTest : LogbackSqlRedactionScenarios(TestDatabase.Postgres())
