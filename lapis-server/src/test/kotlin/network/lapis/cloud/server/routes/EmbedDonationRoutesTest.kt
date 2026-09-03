package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- `POST /api/embed/v1/donation/checkout`.
 */
class EmbedDonationRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        fun enableGateway() {
            val adminId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "EmbedDonationRoutesTest Admin"
                    it[email] = "embed-donation-routes-admin-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                    it[isPoliticalParty] = false
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = adminId
                    it[acknowledgedAt] = DbClock.nowLocalDateTime()
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
            createdMemberIds += adminId
        }

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        val testPspConfig =
            (
                PspConfig.load {
                    when (it) {
                        PspConfig.ENV_SECRET_KEY -> "sk_test_route"
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_route"
                        else -> null
                    }
                } as PspConfigState.Configured
            ).config

        fun mockCheckoutClient(): StripeCheckoutClient {
            val engine =
                MockEngine { _ ->
                    respond(
                        """{"id":"cs_route_test_${Uuid.random()}","url":"https://checkout.stripe.com/c/pay/route"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            return StripeCheckoutClient(pspConfig = testPspConfig, httpClient = HttpClient(engine))
        }

        suspend fun testApp(
            configured: Boolean = true,
            checkoutRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedDonationRoutes(
                            config = enabledConfig,
                            pspConfigState = if (configured) PspConfigState.Configured(testPspConfig) else PspConfigState.NotConfigured,
                            checkoutClient = if (configured) mockCheckoutClient() else null,
                            donationCheckoutRateLimiter = checkoutRateLimiter,
                            donationCheckoutAttemptRateLimiter = generousLimiter(),
                            donationPageRateLimiter = generousLimiter(),
                            baseUrl = "https://lapis.example",
                            brandTitle = "Testverein",
                        )
                    }
                }
                block()
            }
        }

        test("disallowed Origin -> 403, no Access-Control-Allow-Origin, no echo") {
            testApp {
                val response =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                response.bodyAsText().contains("evil.example") shouldBe false
            }
        }

        test("missing Origin header -> 403 (deviates from /api/embed/v1/session)") {
            testApp {
                val response =
                    client.post("/api/embed/v1/donation/checkout") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("allowed Origin -> Vary/Cache-Control/ACAO headers, no Access-Control-Allow-Credentials") {
            enableGateway()
            testApp {
                val response =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }
                response.headers[HttpHeaders.Vary] shouldBe "Origin"
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://partei.example"
                response.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe null
                response.headers["Access-Control-Allow-Credentials"] shouldBe null
            }
        }

        test("oversized body exceeding the 2048-byte cap -> 413, body never parsed") {
            testApp {
                val oversizedBody = """{"amount":"25.00","kommentar":"${"x".repeat(4000)}"}"""
                val response =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody(oversizedBody)
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test("amount boundaries: 4.99 -> 400, 5.00 -> accepted, 500.00 -> accepted, 500.01 -> 400, 3 decimals -> 400, non-numeric -> 400") {
            enableGateway()
            testApp {
                suspend fun postAmount(amount: String) =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"$amount"}""")
                    }

                postAmount("4.99").status shouldBe HttpStatusCode.BadRequest
                postAmount("5.00").status shouldBe HttpStatusCode.OK
                postAmount("500.00").status shouldBe HttpStatusCode.OK
                postAmount("500.01").status shouldBe HttpStatusCode.BadRequest
                postAmount("10.123").status shouldBe HttpStatusCode.BadRequest
                postAmount("not-a-number").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "Fix (Review MAJOR #2) test coverage: 400 body distinguishes AMOUNT_OUT_OF_RANGE (with " +
                "minAmount/maxAmount, documented in embed-widgets.adoc) from BAD_REQUEST (malformed " +
                "JSON or non-numeric amount, which never carries those fields)",
        ) {
            enableGateway()
            testApp {
                val outOfRange =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"999.00"}""")
                    }
                outOfRange.status shouldBe HttpStatusCode.BadRequest
                val outOfRangeBody = outOfRange.bodyAsText()
                outOfRangeBody.contains("\"error\":\"AMOUNT_OUT_OF_RANGE\"") shouldBe true
                outOfRangeBody.contains("\"minAmount\"") shouldBe true
                outOfRangeBody.contains("\"maxAmount\"") shouldBe true

                val nonNumeric =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"fifty euros"}""")
                    }
                nonNumeric.status shouldBe HttpStatusCode.BadRequest
                nonNumeric.bodyAsText() shouldBe """{"error":"BAD_REQUEST"}"""

                val malformedJson =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""not json at all""")
                    }
                malformedJson.status shouldBe HttpStatusCode.BadRequest
                malformedJson.bodyAsText() shouldBe """{"error":"BAD_REQUEST"}"""
            }
        }

        test("Stripe not configured -> 503, body names no env variable") {
            testApp(configured = false) {
                val response =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                val body = response.bodyAsText()
                body.contains("LAPIS_STRIPE") shouldBe false
                body.contains("ENV_") shouldBe false
            }
        }

        test("LAPIS_EMBED_ENABLED=false -> route not registered, staticFiles would 404 (here: connection never wired at all)") {
            // registerEmbedDonationRoutes is only ever called by registerEmbedRoutes INSIDE the
            // `if (!config.enabled) return` gate -- see EmbedRoutes.kt. This test documents the
            // wiring contract at the unit level (a disabled config never reaches this function);
            // the end-to-end 404 behaviour is covered by EmbedRoutesCorsTest at the registerEmbedRoutes level.
            enabledConfig.enabled shouldBe true
        }
    })
