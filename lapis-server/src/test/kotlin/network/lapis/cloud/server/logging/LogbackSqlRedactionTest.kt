package network.lapis.cloud.server.logging

import network.lapis.cloud.server.testdb.TestDatabase

class LogbackSqlRedactionTest : LogbackSqlRedactionScenarios(TestDatabase.H2)
