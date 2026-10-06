package network.lapis.cloud.server.accounting.export

import network.lapis.cloud.server.testdb.TestDatabase

class AccountingExportIdempotencyTest : AccountingExportIdempotencyScenarios(TestDatabase.H2)
