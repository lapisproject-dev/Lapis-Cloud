package network.lapis.cloud.server.articles

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class McpArticleDraftStorePostgresTest : McpArticleDraftStoreScenarios(TestDatabase.Postgres())
