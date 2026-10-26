package network.lapis.cloud.server.gallery

import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [AttachedImageScenarios]. */
class AttachedImageTest : AttachedImageScenarios(TestDatabase.H2)
