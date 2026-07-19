package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [OrganizationSettingsService] end to end -- same "throwaway routes calling the
 * service class directly" house style as [ServiceIntegrationTest]. Reuses [DevSeedData]'s fixed
 * demo member/account ids (no member-specific state is involved here, unlike
 * [AccountingServiceTest]'s own freshly-created members). Restores the seeded row's original
 * fields in `afterTest` so tests remain order-independent regardless of which runs first.
 */
class OrganizationSettingsServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[name] = "Verein/Partei (bitte in Organisationseinstellungen konfigurieren)"
                    it[street] = null
                    it[postalCode] = null
                    it[city] = null
                    it[country] = null
                    it[bankIban] = null
                    it[bankBic] = null
                    it[taxExemptionAuthority] = null
                    it[taxExemptionDate] = null
                    it[isPoliticalParty] = false
                }
            }
        }

        test("TREASURER/BOARD/ADMIN can read the seeded row; MEMBER is forbidden; unauthenticated is rejected") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val asTreasurer = client.get("/test/get") { header("X-Member-Id", TREASURER_ID) }
                asTreasurer.status shouldBe HttpStatusCode.OK
                asTreasurer.bodyAsText().split(":")[0] shouldBe ORGANIZATION_SETTINGS_ID.toString()

                val asBoard = client.get("/test/get") { header("X-Member-Id", BOARD_ID) }
                asBoard.status shouldBe HttpStatusCode.OK

                val asAdmin = client.get("/test/get") { header("X-Member-Id", ADMIN_ID) }
                asAdmin.status shouldBe HttpStatusCode.OK

                val asMember = client.get("/test/get") { header("X-Member-Id", MEMBER_ID) }
                asMember.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.get("/test/get")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("ADMIN can update every field; a subsequent get reflects it; BOARD/TREASURER are forbidden to write") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val forbiddenBoard =
                    client.post("/test/update?name=X") { header("X-Member-Id", BOARD_ID) }
                forbiddenBoard.status shouldBe HttpStatusCode.Forbidden

                val forbiddenTreasurer =
                    client.post("/test/update?name=X") { header("X-Member-Id", TREASURER_ID) }
                forbiddenTreasurer.status shouldBe HttpStatusCode.Forbidden

                val updated =
                    client.post(
                        "/test/update?name=Testverein%20e.V.&street=Musterstrasse%201&postalCode=38100" +
                            "&city=Braunschweig&country=Deutschland&bankIban=DE02120300000000202051&bankBic=BYLADEM1001" +
                            "&taxExemptionAuthority=Finanzamt%20Braunschweig&taxExemptionDate=2025-01-15",
                    ) { header("X-Member-Id", ADMIN_ID) }
                updated.status shouldBe HttpStatusCode.OK
                updated.bodyAsText() shouldBe "Testverein e.V.:Musterstrasse 1:Finanzamt Braunschweig:2025-01-15"

                val getAfterUpdate = client.get("/test/get") { header("X-Member-Id", TREASURER_ID) }
                getAfterUpdate.bodyAsText() shouldBe
                    "$ORGANIZATION_SETTINGS_ID:Testverein e.V.:Musterstrasse 1:DE02120300000000202051"
            }
        }

        test("isPoliticalParty defaults to false and round-trips true through update -> get") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerOrgSettingsTestRoutes() }
                }

                val beforeUpdate = client.get("/test/get-is-political-party") { header("X-Member-Id", TREASURER_ID) }
                beforeUpdate.bodyAsText() shouldBe "false"

                client.post("/test/update?name=Partei%20X&isPoliticalParty=true") { header("X-Member-Id", ADMIN_ID) }

                val afterUpdate = client.get("/test/get-is-political-party") { header("X-Member-Id", TREASURER_ID) }
                afterUpdate.bodyAsText() shouldBe "true"
            }
        }
    })

/** Shared throwaway routes for [OrganizationSettingsServiceTest] -- mirrors [AccountingServiceTest]'s own idiom. */
private fun Route.registerOrgSettingsTestRoutes() {
    get("/test/get") {
        val service = OrganizationSettingsService(call)
        val dto = service.getOrganizationSettings()
        call.respondText("${dto.id}:${dto.name}:${dto.street}:${dto.bankIban}")
    }
    get("/test/get-is-political-party") {
        val service = OrganizationSettingsService(call)
        call.respondText(service.getOrganizationSettings().isPoliticalParty.toString())
    }
    post("/test/update") {
        val service = OrganizationSettingsService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateOrganizationSettings(
                OrganizationSettingsInput(
                    name = q["name"]!!,
                    street = q["street"],
                    postalCode = q["postalCode"],
                    city = q["city"],
                    country = q["country"],
                    bankIban = q["bankIban"],
                    bankBic = q["bankBic"],
                    taxExemptionAuthority = q["taxExemptionAuthority"],
                    taxExemptionDate = q["taxExemptionDate"]?.let { LocalDate.parse(it) },
                    isPoliticalParty = q["isPoliticalParty"]?.toBoolean() ?: false,
                ),
            )
        call.respondText("${dto.name}:${dto.street}:${dto.taxExemptionAuthority}:${dto.taxExemptionDate}")
    }
}
