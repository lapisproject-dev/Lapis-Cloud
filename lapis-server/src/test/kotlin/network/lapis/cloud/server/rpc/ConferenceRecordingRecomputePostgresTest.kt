package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [ConferenceRecordingRecomputeScenarios] (real Flyway chain, real row locks). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceRecordingRecomputePostgresTest : ConferenceRecordingRecomputeScenarios(TestDatabase.Postgres())
