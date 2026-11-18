package network.lapis.cloud.server.carpool.feed

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [CarpoolTeaserFeedScenarios]. */
class CarpoolTeaserFeedTest : CarpoolTeaserFeedScenarios(TestDatabase.H2)
