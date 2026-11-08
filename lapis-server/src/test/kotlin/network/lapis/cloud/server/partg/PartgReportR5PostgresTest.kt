package network.lapis.cloud.server.partg

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartgReportR5Scenarios]: real row locks, deadlock detection, the savepoint on the unique violation, the CHECKs on the real dialect. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReportR5PostgresTest : PartgReportR5Scenarios(TestDatabase.Postgres())
