package network.lapis.cloud.server.gallery

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [SocialPostImageScenarios]: real row locks for the social post and its pictures. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class SocialPostImagePostgresTest : SocialPostImageScenarios(TestDatabase.Postgres())
