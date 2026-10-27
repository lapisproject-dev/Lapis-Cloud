package network.lapis.cloud.server.routes

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.232 -- the mailing-attachment route scenarios (incl. the row-lock races) against a real PostgreSQL (the `postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MailingAttachmentRoutesPostgresTest : MailingAttachmentRoutesScenarios(TestDatabase.Postgres())
