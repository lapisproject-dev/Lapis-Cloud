package network.lapis.cloud.server.carpool

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [CarpoolWithdrawalScenarios]: INSERT ... ON CONFLICT DO NOTHING, the lock order and the foreign key on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CarpoolWithdrawalPostgresTest : CarpoolWithdrawalScenarios(TestDatabase.Postgres())
