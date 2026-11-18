package network.lapis.cloud.server.carpool.feed

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolTeaserFeedWithdrawalScenarios]: ordering, the DATE/TIMESTAMP derivation and the cascade on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolTeaserFeedWithdrawalPostgresTest : CarpoolTeaserFeedWithdrawalScenarios(TestDatabase.Postgres())
