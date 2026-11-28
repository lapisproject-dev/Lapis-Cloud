package network.lapis.cloud.server.translation

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [TranslationServiceScenarios]. */
class TranslationServiceTest : TranslationServiceScenarios(TestDatabase.H2)
