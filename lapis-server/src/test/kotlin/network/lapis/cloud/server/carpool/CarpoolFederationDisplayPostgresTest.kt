package network.lapis.cloud.server.carpool

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolFederationDisplayScenarios]. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolFederationDisplayPostgresTest : CarpoolFederationDisplayScenarios(TestDatabase.Postgres())
