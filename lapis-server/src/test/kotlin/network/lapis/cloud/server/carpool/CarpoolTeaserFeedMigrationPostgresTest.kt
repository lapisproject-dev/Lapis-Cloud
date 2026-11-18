package network.lapis.cloud.server.carpool

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolTeaserFeedMigrationScenarios]: the defaults and the widened audit CHECK on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolTeaserFeedMigrationPostgresTest : CarpoolTeaserFeedMigrationScenarios(TestDatabase.Postgres())
