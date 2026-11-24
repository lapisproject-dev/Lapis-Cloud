package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [AuctionPartnerHintsScenarios]. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AuctionPartnerHintsPostgresTest : AuctionPartnerHintsScenarios(TestDatabase.Postgres())
