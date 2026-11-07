package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartyReportEnterpriseServiceScenarios]: real row locks and the CHECKs on the real dialect. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartyReportEnterpriseServicePostgresTest : PartyReportEnterpriseServiceScenarios(TestDatabase.Postgres())
