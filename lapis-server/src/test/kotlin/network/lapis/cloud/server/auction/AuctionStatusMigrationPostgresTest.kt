package network.lapis.cloud.server.auction

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AuctionStatusMigrationPostgresTest : AuctionStatusMigrationScenarios(TestDatabase.Postgres())
