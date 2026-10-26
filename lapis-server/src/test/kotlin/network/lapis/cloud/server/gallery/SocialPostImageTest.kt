package network.lapis.cloud.server.gallery

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [SocialPostImageScenarios]. */
class SocialPostImageTest : SocialPostImageScenarios(TestDatabase.H2)
