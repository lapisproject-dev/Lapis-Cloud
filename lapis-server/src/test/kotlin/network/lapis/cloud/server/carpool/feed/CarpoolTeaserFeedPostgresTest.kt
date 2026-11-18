package network.lapis.cloud.server.carpool.feed

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolTeaserFeedScenarios]: the same assertions on the real engine (V160 on the full Flyway chain, ordering on DATE + UUID, the BIGINT counter). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolTeaserFeedPostgresTest : CarpoolTeaserFeedScenarios(TestDatabase.Postgres())
