package network.lapis.cloud.server.auction.federation

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [AuctionFederationStoreScenarios]: V169 on the full Flyway chain. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AuctionFederationStorePostgresTest : AuctionFederationStoreScenarios(TestDatabase.Postgres())
