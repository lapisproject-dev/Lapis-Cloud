package network.lapis.cloud.server.time

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * V1.9.38 -- [OrganizationTimeZone] against the real database engine as well as H2: the migrated column default, the cache refill
 * after an invalidation, and -- the point of the Postgres run -- a cache MISS inside an already open transaction (the provider opens a
 * `transaction {}` of its own, which must join the outer one and leave it usable). Postgres aborts a transaction after a failed
 * statement, so a provider that broke the outer transaction would show here and nowhere on H2.
 */
abstract class OrganizationTimeZoneScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        afterSpec { db.deactivate() }
        beforeTest {
            db.assertActive()
            OrganizationTimeZone.invalidate()
        }
        afterTest {
            transaction { OrganizationSettingsTable.update { it[timezone] = OrganizationTimeZoneRules.DEFAULT_ZONE_ID } }
            OrganizationTimeZone.invalidate()
        }

        test("the migrated default is Europe/Berlin and the provider reads it") {
            transaction { OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.timezone] } shouldBe "Europe/Berlin"
            OrganizationTimeZone.current().id shouldBe "Europe/Berlin"
        }

        test("after an invalidation the next call re-reads the database") {
            OrganizationTimeZone.current().id shouldBe "Europe/Berlin"
            transaction { OrganizationSettingsTable.update { it[timezone] = "Asia/Tbilisi" } }
            OrganizationTimeZone.invalidate()
            OrganizationTimeZone.current().id shouldBe "Asia/Tbilisi"
        }

        test("a cache miss inside an open transaction joins it and leaves it usable") {
            transaction {
                OrganizationTimeZone.invalidate()
                OrganizationTimeZone.current().id shouldBe "Europe/Berlin"
                // the outer transaction must still work after the provider's own transaction {} call
                OrganizationSettingsTable.selectAll().count() shouldBe 1L
                OrganizationSettingsTable.update { it[timezone] = "Asia/Tbilisi" }
                OrganizationTimeZone.invalidate()
                OrganizationTimeZone.current().id shouldBe "Asia/Tbilisi" // sees the uncommitted write of the SAME transaction
            }
            OrganizationTimeZone.invalidate()
            OrganizationTimeZone.current().id shouldBe "Asia/Tbilisi"
        }

        test("an invalid stored value falls back to Europe/Berlin without breaking the transaction") {
            transaction { OrganizationSettingsTable.update { it[timezone] = "Not/AZone" } }
            OrganizationTimeZone.invalidate()
            OrganizationTimeZone.current().id shouldBe "Europe/Berlin"
        }
    })

class OrganizationTimeZoneDbTest : OrganizationTimeZoneScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class OrganizationTimeZonePostgresTest : OrganizationTimeZoneScenarios(TestDatabase.Postgres())
