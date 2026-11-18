package network.lapis.cloud.server.partg

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [PartgReviewerScenarios]. */
class PartgReviewerTest : PartgReviewerScenarios(TestDatabase.H2)
