package network.lapis.cloud.server.accounting.export

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AccountingExportConcurrencyPostgresTest : AccountingExportConcurrencyScenarios(TestDatabase.Postgres())
