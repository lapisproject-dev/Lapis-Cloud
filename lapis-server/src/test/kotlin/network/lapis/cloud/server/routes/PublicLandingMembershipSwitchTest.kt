package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.315 -- the hero call to action of `GET /` follows the switch "Mitglied werden": on = the application form, off (or Keycloak mode) =
 * the login. The page cache is per route registration, so the switch is set BEFORE the first request of each app.
 */
class PublicLandingMembershipSwitchTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun setEnabled(value: Boolean) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[membershipApplicationEnabled] = value
                }
            }

        afterTest { setEnabled(true) }

        suspend fun landing(keycloakEnabled: Boolean = false): String {
            var body = ""
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    routing {
                        registerPublicLandingRoutes(
                            readRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                            navAvailability = PublicNavAvailabilityProvider(),
                            keycloakEnabled = keycloakEnabled,
                        )
                    }
                }
                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                body = response.bodyAsText()
            }
            return body
        }

        test("switch on: the hero offers the application form") {
            setEnabled(true)
            landing() shouldContain "#/register"
        }

        test("switch off: no register link anywhere on the page, the hero offers the login") {
            setEnabled(false)
            val body = landing()
            body shouldNotContain "#/register"
            body shouldContain "cta cta-primary"
            body shouldContain "#/login"
        }

        test("Keycloak mode: closed like the switch off, even with the switch on") {
            setEnabled(true)
            landing(keycloakEnabled = true) shouldNotContain "#/register"
        }
    })
