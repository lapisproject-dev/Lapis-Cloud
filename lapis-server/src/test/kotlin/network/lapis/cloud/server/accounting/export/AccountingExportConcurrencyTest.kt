package network.lapis.cloud.server.accounting.export

import network.lapis.cloud.server.testdb.TestDatabase

class AccountingExportConcurrencyTest : AccountingExportConcurrencyScenarios(TestDatabase.H2)
