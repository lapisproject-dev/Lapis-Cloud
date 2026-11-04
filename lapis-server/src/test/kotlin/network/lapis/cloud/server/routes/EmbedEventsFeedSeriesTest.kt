package network.lapis.cloud.server.routes

import network.lapis.cloud.server.testdb.TestDatabase

/** V1.9.278 -- the embed-feed series scenarios on H2 (the normal `test` task). */
class EmbedEventsFeedSeriesTest : EmbedEventsFeedSeriesScenarios(TestDatabase.H2)
