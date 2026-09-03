package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
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
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- Falle 4 (siehe
 * Wellen-Plan §8): ein CORS-Preflight darf nie das 3/Stunde-Checkout-Budget verbrennen, und ein
 * abgelehnter (429) Versuch darf keine Zeile hinterlassen.
 */
class EmbedRateLimitTest :
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
                val rows =
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where {
                            PaymentCheckoutSessionTable.providerSessionId like
                                "cs_ratelimit_%"
                        }.toList()
                val sessionIds = rows.map { it[PaymentCheckoutSessionTable.id] }
                val donorIds = rows.mapNotNull { it[PaymentCheckoutSessionTable.externalDonorId] }
                if (sessionIds.isNotEmpty()) PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.id inList sessionIds }
                if (donorIds.isNotEmpty()) ExternalDonorTable.deleteWhere { ExternalDonorTable.id inList donorIds }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun enableGateway() {
            val adminId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "EmbedRateLimitTest Admin"
                    it[email] = "embed-ratelimit-admin-${Uuid.random()}@example.org"
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
                        PspConfig.ENV_SECRET_KEY -> "sk_test_ratelimit"
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_ratelimit"
                        else -> null
                    }
                } as PspConfigState.Configured
            ).config

        fun mockCheckoutClient(): StripeCheckoutClient {
            val engine =
                MockEngine { _ ->
                    respond(
                        """{"id":"cs_ratelimit_${Uuid.random()}","url":"https://checkout.stripe.com/c/pay/ratelimit"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            return StripeCheckoutClient(pspConfig = testPspConfig, httpClient = HttpClient(engine))
        }

        test("three checkouts from the same IP succeed, the fourth is 429 with Retry-After, and no row is left by the rejected attempt") {
            enableGateway()
            testApplication {
                application {
                    routing {
                        registerEmbedDonationRoutes(
                            config = enabledConfig,
                            pspConfigState = PspConfigState.Configured(testPspConfig),
                            checkoutClient = mockCheckoutClient(),
                            donationCheckoutRateLimiter =
                                FederationInboxRateLimiter(
                                    maxRequests = 3,
                                    window = 1.hours,
                                    maxTrackedKeys = 50_000,
                                ),
                            donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            baseUrl = "https://lapis.example",
                            brandTitle = "Testverein",
                        )
                    }
                }

                suspend fun postCheckout() =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }

                val countBefore =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where {
                                PaymentCheckoutSessionTable.providerSessionId like
                                    "cs_ratelimit_%"
                            }.count()
                    }

                postCheckout().status shouldBe HttpStatusCode.OK
                postCheckout().status shouldBe HttpStatusCode.OK
                postCheckout().status shouldBe HttpStatusCode.OK
                val fourth = postCheckout()
                fourth.status shouldBe HttpStatusCode.TooManyRequests
                (fourth.headers[HttpHeaders.RetryAfter] != null) shouldBe true

                val countAfter =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where {
                                PaymentCheckoutSessionTable.providerSessionId like
                                    "cs_ratelimit_%"
                            }.count()
                    }
                (countAfter - countBefore) shouldBe 3L
            }
        }

        test(
            "Fix (Review MAJOR #4): pre-Stripe rejections (amount out of range) do not consume the " +
                "strict 3/hour checkout budget -- all three genuine attempts still succeed afterwards",
        ) {
            enableGateway()
            testApplication {
                application {
                    routing {
                        registerEmbedDonationRoutes(
                            config = enabledConfig,
                            pspConfigState = PspConfigState.Configured(testPspConfig),
                            checkoutClient = mockCheckoutClient(),
                            donationCheckoutRateLimiter =
                                FederationInboxRateLimiter(
                                    maxRequests = 3,
                                    window = 1.hours,
                                    maxTrackedKeys = 50_000,
                                ),
                            donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            baseUrl = "https://lapis.example",
                            brandTitle = "Testverein",
                        )
                    }
                }

                suspend fun postAmount(amount: String) =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"$amount"}""")
                    }

                // Five pre-Stripe rejections (structurally above the 500 EUR ceiling) -- none of
                // these may touch the strict 3/hour Stripe-call budget.
                repeat(5) { postAmount("999.00").status shouldBe HttpStatusCode.BadRequest }

                postAmount("25.00").status shouldBe HttpStatusCode.OK
                postAmount("25.00").status shouldBe HttpStatusCode.OK
                postAmount("25.00").status shouldBe HttpStatusCode.OK
                postAmount("25.00").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("ten OPTIONS preflights do not consume the checkout budget -- a POST still succeeds afterwards") {
            enableGateway()
            testApplication {
                application {
                    routing {
                        registerEmbedDonationRoutes(
                            config = enabledConfig,
                            pspConfigState = PspConfigState.Configured(testPspConfig),
                            checkoutClient = mockCheckoutClient(),
                            donationCheckoutRateLimiter =
                                FederationInboxRateLimiter(
                                    maxRequests = 3,
                                    window = 1.hours,
                                    maxTrackedKeys = 50_000,
                                ),
                            donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            baseUrl = "https://lapis.example",
                            brandTitle = "Testverein",
                        )
                    }
                }

                repeat(10) {
                    val preflight =
                        client.options("/api/embed/v1/donation/checkout") {
                            header(HttpHeaders.Origin, "https://partei.example")
                            header(HttpHeaders.AccessControlRequestMethod, "POST")
                        }
                    preflight.status shouldBe HttpStatusCode.NoContent
                }

                val response =
                    client.post("/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
