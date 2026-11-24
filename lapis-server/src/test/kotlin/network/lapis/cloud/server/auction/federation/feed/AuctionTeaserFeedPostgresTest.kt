package network.lapis.cloud.server.auction.federation.feed

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [AuctionTeaserFeedScenarios]. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AuctionTeaserFeedPostgresTest : AuctionTeaserFeedScenarios(TestDatabase.Postgres())
