package network.lapis.cloud.server.events

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** H2 lane of [EventRegistrationEmbedOriginScenarios]. */
class EventRegistrationEmbedOriginScenariosTest : EventRegistrationEmbedOriginScenarios(TestDatabase.H2)

/** Postgres lane of [EventRegistrationEmbedOriginScenarios] (the real Flyway chain up to V137, the real VARCHAR(255) and CHECK). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventRegistrationEmbedOriginScenariosPostgresTest : EventRegistrationEmbedOriginScenarios(TestDatabase.Postgres())
