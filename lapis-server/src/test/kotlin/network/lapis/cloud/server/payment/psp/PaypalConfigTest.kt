package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val FIXTURE_CLIENT_ID = "AaBb0011-fixture-client-id-value"
private const val FIXTURE_CLIENT_SECRET = "EfGh2233-fixture-client-secret-value"
private const val FIXTURE_WEBHOOK_ID = "1AB23456CD789012E"

class PaypalConfigTest :
    FunSpec({
        test("no LAPIS_PAYPAL_* set -> NotConfigured") {
            val state = PaypalConfig.load { null }
            state shouldBe PaypalConfigState.NotConfigured
        }

        // Implementierungsplan §6.1 -- THE trap: the three shared LAPIS_PSP_* numeric knobs must
        // NEVER count as PayPal's own opt-in signal, or a Stripe-only deployment that sets
        // LAPIS_PSP_MAX_CHECKOUT_AMOUNT_EUR would suddenly report PaypalConfigState.Incomplete and
        // PaypalStartupCheck would throw on a perfectly healthy Stripe-only production instance.
        test("only LAPIS_PSP_* numeric knobs set (no LAPIS_PAYPAL_*) -> still NotConfigured") {
            val env =
                mapOf(
                    PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR to "5000.00",
                    PspConfig.ENV_WEBHOOK_TOLERANCE_SECONDS to "600",
                    PspConfig.ENV_CHECKOUT_TTL_MINUTES to "30",
                )
            val state = PaypalConfig.load { env[it] }
            state shouldBe PaypalConfigState.NotConfigured
        }

        test("all three required set + valid -> Configured") {
            val env =
                mapOf(
                    PaypalConfig.ENV_CLIENT_ID to FIXTURE_CLIENT_ID,
                    PaypalConfig.ENV_CLIENT_SECRET to FIXTURE_CLIENT_SECRET,
                    PaypalConfig.ENV_WEBHOOK_ID to FIXTURE_WEBHOOK_ID,
                )
            val state = PaypalConfig.load { env[it] }
            (state is PaypalConfigState.Configured) shouldBe true
        }

        test("only one of three required set -> Incomplete(missing = the other two)") {
            val env = mapOf(PaypalConfig.ENV_CLIENT_ID to FIXTURE_CLIENT_ID)
            val state = PaypalConfig.load { env[it] }
            (state is PaypalConfigState.Incomplete) shouldBe true
            (state as PaypalConfigState.Incomplete).missing shouldBe
                listOf(PaypalConfig.ENV_CLIENT_SECRET, PaypalConfig.ENV_WEBHOOK_ID)
        }

        test("malformed clientId/clientSecret/webhookId -> Incomplete(invalid = [...])") {
            val env =
                mapOf(
                    PaypalConfig.ENV_CLIENT_ID to "too short",
                    PaypalConfig.ENV_CLIENT_SECRET to "also too short",
                    PaypalConfig.ENV_WEBHOOK_ID to "not_uppercase_hex",
                )
            val state = PaypalConfig.load { env[it] }
            (state is PaypalConfigState.Incomplete) shouldBe true
            val invalid = (state as PaypalConfigState.Incomplete).invalid
            invalid shouldBe listOf(PaypalConfig.ENV_CLIENT_ID, PaypalConfig.ENV_CLIENT_SECRET, PaypalConfig.ENV_WEBHOOK_ID)
        }

        test("LAPIS_PAYPAL_API_BASE_URL: http://evil.example and https://attacker.example rejected; live/sandbox/loopback accepted") {
            fun stateFor(apiBaseUrl: String): PaypalConfigState {
                val env =
                    mapOf(
                        PaypalConfig.ENV_CLIENT_ID to FIXTURE_CLIENT_ID,
                        PaypalConfig.ENV_CLIENT_SECRET to FIXTURE_CLIENT_SECRET,
                        PaypalConfig.ENV_WEBHOOK_ID to FIXTURE_WEBHOOK_ID,
                        PaypalConfig.ENV_API_BASE_URL to apiBaseUrl,
                    )
                return PaypalConfig.load { env[it] }
            }

            (stateFor("http://evil.example") is PaypalConfigState.Incomplete) shouldBe true
            (stateFor("https://attacker.example") is PaypalConfigState.Incomplete) shouldBe true
            (stateFor(PaypalConfig.DEFAULT_API_BASE_URL) is PaypalConfigState.Configured) shouldBe true
            (stateFor(PaypalConfig.SANDBOX_API_BASE_URL) is PaypalConfigState.Configured) shouldBe true
            (stateFor("http://127.0.0.1:1234") is PaypalConfigState.Configured) shouldBe true
            (stateFor("http://localhost:1234") is PaypalConfigState.Configured) shouldBe true
        }

        test("numeric knobs out of range clamp, never throw") {
            fun configFor(
                tolerance: String? = null,
                maxAmount: String? = null,
                ttl: String? = null,
            ): PaypalConfig {
                val env =
                    mutableMapOf(
                        PaypalConfig.ENV_CLIENT_ID to FIXTURE_CLIENT_ID,
                        PaypalConfig.ENV_CLIENT_SECRET to FIXTURE_CLIENT_SECRET,
                        PaypalConfig.ENV_WEBHOOK_ID to FIXTURE_WEBHOOK_ID,
                    )
                tolerance?.let { env[PspConfig.ENV_WEBHOOK_TOLERANCE_SECONDS] = it }
                maxAmount?.let { env[PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR] = it }
                ttl?.let { env[PspConfig.ENV_CHECKOUT_TTL_MINUTES] = it }
                return (PaypalConfig.load { env[it] } as PaypalConfigState.Configured).config
            }

            configFor(tolerance = "1").webhookToleranceSeconds shouldBe PspConfig.MIN_WEBHOOK_TOLERANCE_SECONDS
            configFor(tolerance = "999999").webhookToleranceSeconds shouldBe PspConfig.MAX_WEBHOOK_TOLERANCE_SECONDS
            configFor(maxAmount = "0.01").maxCheckoutAmountEur shouldBe PspConfig.MIN_MAX_CHECKOUT_AMOUNT_EUR
            configFor(ttl = "999999").checkoutTtlMinutes shouldBe PspConfig.MAX_CHECKOUT_TTL_MINUTES
        }

        test("toString() contains neither the clientId's nor the clientSecret's nor the webhookId's value") {
            val env =
                mapOf(
                    PaypalConfig.ENV_CLIENT_ID to FIXTURE_CLIENT_ID,
                    PaypalConfig.ENV_CLIENT_SECRET to FIXTURE_CLIENT_SECRET,
                    PaypalConfig.ENV_WEBHOOK_ID to FIXTURE_WEBHOOK_ID,
                )
            val state = PaypalConfig.load { env[it] } as PaypalConfigState.Configured
            val rendered = state.config.toString()
            rendered shouldNotContain FIXTURE_CLIENT_ID
            rendered shouldNotContain FIXTURE_CLIENT_SECRET
            rendered shouldNotContain FIXTURE_WEBHOOK_ID
        }
    })
