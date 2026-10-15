package network.lapis.cloud.server.agreementtexts

import network.lapis.cloud.server.testdb.TestDatabase

class AgreementTextPublishConcurrencyTest : AgreementTextPublishConcurrencyScenarios(TestDatabase.H2)
