package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URLEncoder

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Welle V1.9.122 -- every range of `updateTravelExpenseSettings` is validated BEFORE the database
 * (so a bad value is a 400, never a CHECK-constraint 500), the contact block is normalised, and the
 * audit entry never carries the contact's personal data.
 */
class TravelExpenseSettingsValidationTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelTier1Hours] = 8
                    it[travelTier2Hours] = 14
                    it[travelTier3Hours] = 24
                    it[travelTier1Cents] = 600
                    it[travelTier2Cents] = 1200
                    it[travelTier3Cents] = 2800
                    it[travelBreakfastPct] = 20
                    it[travelLunchPct] = 40
                    it[travelDinnerPct] = 40
                    it[travelPassengerCentsPerKm] = 2
                    it[travelMileageRatePerKm] = null
                    it[travelPerDiemRate] = null
                    it[travelContactName] = null
                    it[travelContactEmail] = null
                    it[travelContactPhone] = null
                    it[travelContactHint] = null
                }
            }
        }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        suspend fun HttpClient.settings(query: String): HttpResponse =
            post("/test/travelexpense/settings?$query") { header("X-Member-Id", ADMIN_ID) }

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                block()
            }

        test("the defaults are the PdV template and a valid full input is accepted") {
            withApp {
                client.settings("mileage=30").status shouldBe HttpStatusCode.OK
                val row = transaction { OrganizationSettingsTable.selectAll().single() }
                row[OrganizationSettingsTable.travelTier3Cents] shouldBe 2800
                row[OrganizationSettingsTable.travelMileageRatePerKm]?.compareTo(java.math.BigDecimal("0.30")) shouldBe 0
            }
        }

        test("tier hours must be strictly ascending within 1..24") {
            withApp {
                listOf("8,8,24", "14,8,24", "0,14,24", "8,14,25", "8,14", "8,14,24,30").forEach { hours ->
                    client.settings("hours=$hours").status shouldBe HttpStatusCode.BadRequest
                }
                client.settings("hours=1,2,3").status shouldBe HttpStatusCode.OK
                client.settings("hours=6,10,24").status shouldBe HttpStatusCode.OK
            }
        }

        test("tier amounts must be ascending and at most 100.00 EUR") {
            withApp {
                listOf("1200,600,2800", "-1,600,2800", "600,1200,10001", "600,1200").forEach { cents ->
                    client.settings("cents=$cents").status shouldBe HttpStatusCode.BadRequest
                }
                client.settings("cents=0,0,0").status shouldBe HttpStatusCode.OK
                client.settings("cents=600,1200,10000").status shouldBe HttpStatusCode.OK
            }
        }

        test("meal percentages must be 0..100, passenger rate 0..20 ct, mileage rate 1..100 ct") {
            withApp {
                client.settings("b=101").status shouldBe HttpStatusCode.BadRequest
                client.settings("l=-1").status shouldBe HttpStatusCode.BadRequest
                client.settings("d=101").status shouldBe HttpStatusCode.BadRequest
                client.settings("passenger=21").status shouldBe HttpStatusCode.BadRequest
                client.settings("mileage=0").status shouldBe HttpStatusCode.BadRequest
                client.settings("mileage=101").status shouldBe HttpStatusCode.BadRequest // > 1.00 EUR/km
                client.settings("b=0&l=100&d=100&passenger=20&mileage=100").status shouldBe HttpStatusCode.OK
                client.settings("perDiem=0").status shouldBe HttpStatusCode.BadRequest
                client.settings("perDiem=14.005").status shouldBe HttpStatusCode.BadRequest
                client.settings("perDiem=14.00").status shouldBe HttpStatusCode.OK
            }
        }

        test("contact: e-mail with CR/LF or garbage, phone with letters, control characters in the hint -> 400; blank -> null") {
            withApp {
                client.settings("email=${enc("a@b.de\r\nBcc: x@y.de")}").status shouldBe HttpStatusCode.BadRequest
                client.settings("email=${enc("kein-mail")}").status shouldBe HttpStatusCode.BadRequest
                client.settings("email=${enc("Name <a@b.de>")}").status shouldBe HttpStatusCode.BadRequest
                client.settings("phone=${enc("call me maybe")}").status shouldBe HttpStatusCode.BadRequest
                client.settings("hint=${enc("Zeile\u0007Glocke")}").status shouldBe HttpStatusCode.BadRequest
                client.settings("name=${"x".repeat(121)}").status shouldBe HttpStatusCode.BadRequest
                client.settings("hint=${"x".repeat(1001)}").status shouldBe HttpStatusCode.BadRequest

                client
                    .settings("name=${enc("   ")}&email=${enc(" ")}&phone=&hint=${enc("Zeile 1\nZeile 2")}")
                    .status shouldBe HttpStatusCode.OK
                val row = transaction { OrganizationSettingsTable.selectAll().single() }
                row[OrganizationSettingsTable.travelContactName] shouldBe null
                row[OrganizationSettingsTable.travelContactEmail] shouldBe null
                row[OrganizationSettingsTable.travelContactPhone] shouldBe null
                row[OrganizationSettingsTable.travelContactHint] shouldBe "Zeile 1\nZeile 2"
            }
        }

        test("an unknown or malformed account id is a 4xx, never a 500") {
            withApp {
                client.settings("advanceAccount=kein-uuid").status.value shouldBe 404
                client.settings("waiverAccount=${java.util.UUID.randomUUID()}").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("the audit entry carries numbers and booleans only -- never the contact's name, e-mail or phone") {
            withApp {
                client
                    .settings("name=${enc("Erika Mustermann")}&email=${enc("erika@example.org")}&phone=${enc("+49 531 123456")}&b=25")
                    .status shouldBe HttpStatusCode.OK
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                                    (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                            }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                            .first()
                    }
                val after = entry[AuditLogEntryTable.afterSnapshot]!!
                after shouldNotContain "Erika"
                after shouldNotContain "Mustermann"
                after shouldNotContain "example.org"
                after shouldNotContain "123456"
                after.contains("\"contactNameSet\":true") shouldBe true
                after.contains("\"breakfastPercent\":25") shouldBe true
            }
        }

        test("getTravelExpenseRates: a plain member sees the contact but no account ids and cannot edit; ADMIN can") {
            withApp {
                client.settings("name=${enc("Erika Mustermann")}&email=${enc("erika@example.org")}").status shouldBe HttpStatusCode.OK
                val boardInfo =
                    client.get(
                        "/test/travelexpense/ratesinfo",
                    ) { header("X-Member-Id", "00000000-0000-0000-0000-000000000002") }
                boardInfo.bodyAsText().split("|").let {
                    it[0] shouldBe "false"
                    it[1] shouldBe "Erika Mustermann"
                    it[3] shouldBe "-"
                    it[4] shouldBe "-"
                }
                client.get("/test/travelexpense/ratesinfo") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")[0] shouldBe "true"
            }
        }
    })
