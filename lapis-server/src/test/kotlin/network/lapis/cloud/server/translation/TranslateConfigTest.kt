package network.lapis.cloud.server.translation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.config.AiProviderKind
import network.lapis.cloud.server.ai.llm.AiProviderFactory
import network.lapis.cloud.server.ai.llm.LlmProviderProfile
import network.lapis.cloud.server.translation.config.TranslateConfig
import network.lapis.cloud.server.translation.config.TranslateStartupCheck

private fun cfg(vararg pairs: Pair<String, String>): TranslateConfig {
    val env = pairs.toMap()
    return TranslateConfig.load { env[it] }
}

private val XAI =
    arrayOf(
        TranslateConfig.ENV_ENABLED to "true",
        TranslateConfig.ENV_PROVIDER to "openai_compatible",
        TranslateConfig.ENV_MODEL to "some-model",
        TranslateConfig.ENV_API_KEY to "xai-secret-key-value",
        TranslateConfig.ENV_BASE_URL to "https://api.x.ai",
    )

class TranslateConfigTest :
    FunSpec({
        test("off by default, and nothing else is even looked at") {
            val c = cfg()
            c.enabled shouldBe false
            c.isOperational shouldBe false
            c.invalid.shouldBeEmpty()
            c.providerProfileOrNull() shouldBe null
        }

        test("enabled without a profile is not operational, names the variables, and does not throw") {
            val c = cfg(TranslateConfig.ENV_ENABLED to "true")
            c.isOperational shouldBe false
            c.invalid shouldContain TranslateConfig.ENV_PROVIDER
            c.invalid shouldContain TranslateConfig.ENV_MODEL
            c.invalid shouldContain TranslateConfig.ENV_API_KEY
        }

        test("a complete openai_compatible profile for https://api.x.ai is operational") {
            val c = cfg(*XAI)
            c.isOperational shouldBe true
            c.baseUrl shouldBe "https://api.x.ai"
            c.provider shouldBe AiProviderKind.OPENAI_COMPATIBLE
            c.invalid.shouldBeEmpty()
        }

        test("anthropic without a base URL takes the default; openai_compatible without one is invalid") {
            val a =
                cfg(
                    TranslateConfig.ENV_ENABLED to "true",
                    TranslateConfig.ENV_PROVIDER to "anthropic",
                    TranslateConfig.ENV_MODEL to "m",
                    TranslateConfig.ENV_API_KEY to "k",
                )
            a.isOperational shouldBe true
            a.baseUrl shouldBe "https://api.anthropic.com"
            val o = cfg(*XAI.filter { it.first != TranslateConfig.ENV_BASE_URL }.toTypedArray())
            o.isOperational shouldBe false
            o.invalid shouldContain TranslateConfig.ENV_BASE_URL
        }

        listOf(
            "http://api.x.ai",
            "https://10.0.0.1",
            "https://169.254.169.254",
            "https://127.0.0.1",
            "https://localhost",
            "https://[::1]",
            "https://2130706433",
            "https://u@h.example",
            "https://h.example/?q=1",
            "https://h.example/#f",
            "https://h.example/\u0001",
            "https://" + "a".repeat(250) + ".example",
        ).forEach { url ->
            test("the base URL '${url.take(40).replace('\u0001', '?')}' is rejected") {
                val c = cfg(*XAI.filter { it.first != TranslateConfig.ENV_BASE_URL }.toTypedArray(), TranslateConfig.ENV_BASE_URL to url)
                c.isOperational shouldBe false
                c.invalid shouldContain TranslateConfig.ENV_BASE_URL
            }
        }

        test("plain http over loopback only with the explicit switch") {
            val without =
                cfg(
                    *XAI.filter { it.first != TranslateConfig.ENV_BASE_URL }.toTypedArray(),
                    TranslateConfig.ENV_BASE_URL to "http://127.0.0.1:11434",
                )
            without.isOperational shouldBe false
            val with =
                cfg(
                    *XAI.filter { it.first != TranslateConfig.ENV_BASE_URL }.toTypedArray(),
                    TranslateConfig.ENV_BASE_URL to "http://127.0.0.1:11434",
                    TranslateConfig.ENV_ALLOW_PLAINTEXT_BASE_URL to "true",
                )
            with.isOperational shouldBe true
        }

        test("a control character in the key or model is rejected") {
            cfg(
                *XAI.filter { it.first != TranslateConfig.ENV_API_KEY }.toTypedArray(),
                TranslateConfig.ENV_API_KEY to "a\nb",
            ).isOperational shouldBe
                false
            cfg(
                *XAI.filter { it.first != TranslateConfig.ENV_MODEL }.toTypedArray(),
                TranslateConfig.ENV_MODEL to "m\u0000",
            ).isOperational shouldBe
                false
        }

        test("tuning: defaults, bounds, and a foreign value falls back and is named") {
            val d = cfg(*XAI)
            d.requestTimeoutMs shouldBe 30_000L
            d.waitTimeoutMs shouldBe 8_000L
            d.maxResponseBytes shouldBe 256 * 1024
            d.maxOutputTokens shouldBe 6_000
            d.maxSourceCharsPerContent shouldBe 12_000
            d.dailyCharBudget shouldBe 200_000
            d.requestsPerIpPerMinute shouldBe 20
            d.maxConcurrentCalls shouldBe 2
            val bad =
                cfg(
                    *XAI,
                    TranslateConfig.ENV_WAIT_MS to "10",
                    TranslateConfig.ENV_MAX_CONCURRENT to "99",
                    TranslateConfig.ENV_DAILY_CHAR_BUDGET to "lots",
                )
            bad.isOperational shouldBe true
            bad.waitTimeoutMs shouldBe 8_000L
            bad.maxConcurrentCalls shouldBe 2
            bad.dailyCharBudget shouldBe 200_000
            bad.invalid.toSet() shouldBe
                setOf(TranslateConfig.ENV_WAIT_MS, TranslateConfig.ENV_MAX_CONCURRENT, TranslateConfig.ENV_DAILY_CHAR_BUDGET)
            val ok = cfg(*XAI, TranslateConfig.ENV_WAIT_MS to "12000", TranslateConfig.ENV_MAX_CONCURRENT to "4")
            ok.waitTimeoutMs shouldBe 12_000L
            ok.maxConcurrentCalls shouldBe 4
        }

        test("toString never contains the key") {
            cfg(*XAI).toString() shouldNotContain "xai-secret-key-value"
            cfg(*XAI).providerProfileOrNull().toString() shouldNotContain "xai-secret-key-value"
        }

        test("separate from LAPIS_AI_*: an AI key alone does not enable translation, and the reverse") {
            val aiOnly =
                mapOf(
                    AiConfig.ENV_ENABLED to "true",
                    AiConfig.ENV_PROVIDER to "anthropic",
                    AiConfig.ENV_MODEL to "m",
                    AiConfig.ENV_API_KEY to "ai-key",
                )
            TranslateConfig.load { aiOnly[it] }.isOperational shouldBe false
            val translateOnly = XAI.toMap()
            AiConfig.load { translateOnly[it] }.isOperational shouldBe false
        }

        test("the startup warning for a /v1 suffix is detected") {
            TranslateStartupCheck.baseUrlEndsWithV1("https://api.x.ai/v1") shouldBe true
            TranslateStartupCheck.baseUrlEndsWithV1("https://api.x.ai/v1/") shouldBe true
            TranslateStartupCheck.baseUrlEndsWithV1("https://api.x.ai") shouldBe false
            // the config still accepts it (a warning, not a rejection)
            cfg(
                *XAI.filter { it.first != TranslateConfig.ENV_BASE_URL }.toTypedArray(),
                TranslateConfig.ENV_BASE_URL to "https://api.x.ai/v1",
            ).isOperational shouldBe
                true
        }

        test("the shared factory builds a client from a profile and from an AiConfig, and none for a non-operational AI config") {
            val profile = cfg(*XAI).providerProfileOrNull()
            (profile is LlmProviderProfile) shouldBe true
            (AiProviderFactory.create(profile!!) != null) shouldBe true
            AiProviderFactory.create(AiConfig.load { null }) shouldBe null
        }
    })
