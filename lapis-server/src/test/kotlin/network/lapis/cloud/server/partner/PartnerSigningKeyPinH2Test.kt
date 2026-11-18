package network.lapis.cloud.server.partner

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartnerSigningKeyPinScenarios]. */
class PartnerSigningKeyPinH2Test : PartnerSigningKeyPinScenarios(TestDatabase.H2)
