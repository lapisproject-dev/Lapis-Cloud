package network.lapis.cloud.server.events

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [EventConferenceProvisioningScenarios] (real Flyway chain, real row locks, the 25P02 behaviour of a failed claim insert). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventConferenceProvisioningPostgresTest : EventConferenceProvisioningScenarios(TestDatabase.Postgres())
