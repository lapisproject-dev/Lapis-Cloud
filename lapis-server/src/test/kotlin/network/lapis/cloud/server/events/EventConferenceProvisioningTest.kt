package network.lapis.cloud.server.events

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [EventConferenceProvisioningScenarios]. */
class EventConferenceProvisioningTest : EventConferenceProvisioningScenarios(TestDatabase.H2)
