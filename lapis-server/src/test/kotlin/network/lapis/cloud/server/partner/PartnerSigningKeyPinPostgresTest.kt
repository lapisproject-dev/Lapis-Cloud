package network.lapis.cloud.server.partner

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase

/** Postgres lane of [PartnerSigningKeyPinScenarios]: V160 on the real engine, the BIGINT counter. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartnerSigningKeyPinPostgresTest : PartnerSigningKeyPinScenarios(TestDatabase.Postgres())
