package network.lapis.cloud.server.carpool.pull

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolReceivedScenarios]: V162 on the full Flyway chain, the real constraints and foreign keys, the locking reads. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolReceivedPostgresTest : CarpoolReceivedScenarios(TestDatabase.Postgres())
