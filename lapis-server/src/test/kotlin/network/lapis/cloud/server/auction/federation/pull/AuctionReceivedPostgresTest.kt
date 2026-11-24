package network.lapis.cloud.server.auction.federation.pull

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [AuctionReceivedScenarios]: the real constraints and foreign keys, the locking reads. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AuctionReceivedPostgresTest : AuctionReceivedScenarios(TestDatabase.Postgres())
