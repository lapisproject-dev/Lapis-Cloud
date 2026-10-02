package network.lapis.cloud.server.time

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.accounting.export.buildJournalExportRequest
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.38 -- the provider's views of "now" in the organization zone, and the export header time. */
class OrganizationTimeZoneProviderTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { TimeTestSupport.resetOrganizationZone() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("wallNow / today / dateOf follow the organization zone") {
            TimeTestSupport.withServerClock(instant = "2026-12-31T23:30:00Z") {
                OrganizationTimeZone.wallNow() shouldBe LocalDateTime(2027, 1, 1, 0, 30)
                OrganizationTimeZone.today() shouldBe LocalDate(2027, 1, 1)
                OrganizationTimeZone.dateOf(LocalDateTime(2026, 12, 31, 23, 30)) shouldBe LocalDate(2027, 1, 1)
                OrganizationTimeZone.dateOf(LocalDateTime(2026, 12, 31, 22, 59)) shouldBe LocalDate(2026, 12, 31)
            }
        }

        test("changing the zone changes all views (Tbilisi is UTC+4)") {
            TimeTestSupport.setOrganizationZone("Asia/Tbilisi")
            TimeTestSupport.withServerClock(instant = "2026-12-31T20:30:00Z") {
                OrganizationTimeZone.wallNow() shouldBe LocalDateTime(2027, 1, 1, 0, 30)
                OrganizationTimeZone.today() shouldBe LocalDate(2027, 1, 1)
            }
        }

        test("the DATEV/export header time is the organization wall-clock of the real clock (10:00Z is 12:00 in Berlin)") {
            TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                val request =
                    transaction {
                        buildJournalExportRequest(from = LocalDate(2026, 1, 1), to = LocalDate(2026, 12, 31), exportedBy = "test")
                    }
                request.generatedAt shouldBe LocalDateTime(2026, 7, 1, 12, 0)
            }
        }
    })
