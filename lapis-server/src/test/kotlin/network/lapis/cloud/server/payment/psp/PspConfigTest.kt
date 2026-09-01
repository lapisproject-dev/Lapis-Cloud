package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val FIXTURE_SECRET_KEY = "sk_test_fixture_value_never_logged"
private const val FIXTURE_WEBHOOK_SECRET = "whsec_fixture_value_never_logged"

class PspConfigTest :
    FunSpec({
        test("no LAPIS_STRIPE_* set -> NotConfigured") {
            val state = PspConfig.load { null }
            state shouldBe PspConfigState.NotConfigured
        }

        test("both set + valid -> Configured") {
            val env =
                mapOf(
                    PspConfig.ENV_SECRET_KEY to FIXTURE_SECRET_KEY,
                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET to FIXTURE_WEBHOOK_SECRET,
                )
            val state = PspConfig.load { env[it] }
            (state is PspConfigState.Configured) shouldBe true
        }

        test("only the secret key set -> Incomplete(missing = [ENV_WEBHOOK_SIGNING_SECRET])") {
            val env = mapOf(PspConfig.ENV_SECRET_KEY to FIXTURE_SECRET_KEY)
            val state = PspConfig.load { env[it] }
            (state is PspConfigState.Incomplete) shouldBe true
            (state as PspConfigState.Incomplete).missing shouldBe listOf(PspConfig.ENV_WEBHOOK_SIGNING_SECRET)
        }

        test("key not starting with sk_ -> Incomplete(invalid = [...])") {
            val env =
                mapOf(
                    PspConfig.ENV_SECRET_KEY to "not-a-stripe-key",
                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET to FIXTURE_WEBHOOK_SECRET,
                )
            val state = PspConfig.load { env[it] }
            (state is PspConfigState.Incomplete) shouldBe true
            (state as PspConfigState.Incomplete).invalid shouldBe listOf(PspConfig.ENV_SECRET_KEY)
        }

        test("LAPIS_STRIPE_API_BASE_URL=http://evil.example rejected, https and http://127.0.0.1 accepted") {
            fun stateFor(apiBaseUrl: String): PspConfigState {
                val env =
                    mapOf(
                        PspConfig.ENV_SECRET_KEY to FIXTURE_SECRET_KEY,
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET to FIXTURE_WEBHOOK_SECRET,
                        PspConfig.ENV_API_BASE_URL to apiBaseUrl,
                    )
                return PspConfig.load { env[it] }
            }

            (stateFor("http://evil.example") is PspConfigState.Incomplete) shouldBe true
            (stateFor("https://api.stripe.com") is PspConfigState.Configured) shouldBe true
            (stateFor("http://127.0.0.1:8443") is PspConfigState.Configured) shouldBe true
            (stateFor("http://localhost:8443") is PspConfigState.Configured) shouldBe true
        }

        test("numeric clamps at both bounds and on garbage input") {
            fun configFor(
                tolerance: String? = null,
                maxAmount: String? = null,
                ttl: String? = null,
            ): PspConfig {
                val env =
                    mutableMapOf(
                        PspConfig.ENV_SECRET_KEY to FIXTURE_SECRET_KEY,
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET to FIXTURE_WEBHOOK_SECRET,
                    )
                tolerance?.let { env[PspConfig.ENV_WEBHOOK_TOLERANCE_SECONDS] = it }
                maxAmount?.let { env[PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR] = it }
                ttl?.let { env[PspConfig.ENV_CHECKOUT_TTL_MINUTES] = it }
                return (PspConfig.load { env[it] } as PspConfigState.Configured).config
            }

            configFor(tolerance = "1").webhookToleranceSeconds shouldBe PspConfig.MIN_WEBHOOK_TOLERANCE_SECONDS
            configFor(tolerance = "999999").webhookToleranceSeconds shouldBe PspConfig.MAX_WEBHOOK_TOLERANCE_SECONDS
            configFor(tolerance = "garbage").webhookToleranceSeconds shouldBe PspConfig.DEFAULT_WEBHOOK_TOLERANCE_SECONDS

            configFor(maxAmount = "0.01").maxCheckoutAmountEur shouldBe PspConfig.MIN_MAX_CHECKOUT_AMOUNT_EUR
            configFor(maxAmount = "99999999").maxCheckoutAmountEur shouldBe PspConfig.MAX_MAX_CHECKOUT_AMOUNT_EUR
            configFor(maxAmount = "garbage").maxCheckoutAmountEur shouldBe PspConfig.DEFAULT_MAX_CHECKOUT_AMOUNT_EUR

            configFor(ttl = "1").checkoutTtlMinutes shouldBe PspConfig.MIN_CHECKOUT_TTL_MINUTES
            configFor(ttl = "999999").checkoutTtlMinutes shouldBe PspConfig.MAX_CHECKOUT_TTL_MINUTES
            configFor(ttl = "garbage").checkoutTtlMinutes shouldBe PspConfig.DEFAULT_CHECKOUT_TTL_MINUTES
        }

        test("toString() contains neither the secret key's nor the webhook secret's value") {
            val env =
                mapOf(
                    PspConfig.ENV_SECRET_KEY to FIXTURE_SECRET_KEY,
                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET to FIXTURE_WEBHOOK_SECRET,
                )
            val state = PspConfig.load { env[it] } as PspConfigState.Configured
            val rendered = state.config.toString()
            rendered shouldNotContain FIXTURE_SECRET_KEY
            rendered shouldNotContain FIXTURE_WEBHOOK_SECRET
        }
    })
