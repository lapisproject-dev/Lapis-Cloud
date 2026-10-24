package network.lapis.cloud.server.events

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [EventRegistrationMessageScenarios]. */
class EventRegistrationMessageScenariosTest : EventRegistrationMessageScenarios(TestDatabase.H2)

/** Postgres lane of [EventRegistrationMessageScenarios] (the real Flyway chain up to V117, the real VARCHAR(500) and CHECK). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventRegistrationMessageScenariosPostgresTest : EventRegistrationMessageScenarios(TestDatabase.Postgres())
