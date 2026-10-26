package network.lapis.cloud.server.gallery

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [AttachedImageScenarios]: the unique index (owner, position) is not deferrable here, the races use real row locks.
 * */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AttachedImagePostgresTest : AttachedImageScenarios(TestDatabase.Postgres())
