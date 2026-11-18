package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgReviewerScenarios]: V158 on PostgreSQL 17, the row lock under parallel tries, the foreign keys and the CHECKs on the real dialect. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReviewerPostgresTest : PartgReviewerScenarios(TestDatabase.Postgres())
