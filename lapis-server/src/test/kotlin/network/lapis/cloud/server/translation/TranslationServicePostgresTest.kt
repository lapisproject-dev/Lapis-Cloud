package network.lapis.cloud.server.translation

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [TranslationServiceScenarios]: the lock, the savepoint insert and the re-check on the real engine. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TranslationServicePostgresTest : TranslationServiceScenarios(TestDatabase.Postgres())
