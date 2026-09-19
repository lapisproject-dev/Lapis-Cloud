package network.lapis.cloud.server.ai.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val SECRET = "sk-very-secret-key-1234567890"

private fun load(vararg pairs: Pair<String, String>): AiConfig {
    val env = pairs.toMap()
    return AiConfig.load { env[it] }
}

private fun operational(vararg extra: Pair<String, String>): AiConfig =
    load(
        AiConfig.ENV_ENABLED to "true",
        AiConfig.ENV_PROVIDER to "anthropic",
        AiConfig.ENV_MODEL to "some-model",
        AiConfig.ENV_API_KEY to SECRET,
        *extra,
    )

class AiConfigTest :
    FunSpec({
        test("feature is off when nothing is set") {
            val config = load()
            config.enabled shouldBe false
            config.isOperational shouldBe false
            config.invalid shouldBe emptyList()
        }

        test("feature is on only for exactly true, case-insensitive and trimmed") {
            load(AiConfig.ENV_ENABLED to "true").enabled shouldBe true
            load(AiConfig.ENV_ENABLED to " TRUE ").enabled shouldBe true
            listOf("1", "yes", "on", "false", "truee", "").forEach {
                load(AiConfig.ENV_ENABLED to it).enabled shouldBe false
            }
        }

        test("a complete anthropic profile is operational and defaults the base URL") {
            val config = operational()
            config.isOperational shouldBe true
            config.provider shouldBe AiProviderKind.ANTHROPIC
            config.baseUrl shouldBe "https://api.anthropic.com"
            config.invalid shouldBe emptyList()
        }

        test("enabled but a single profile part missing keeps the feature off (four separate cases)") {
            val full =
                mapOf(
                    AiConfig.ENV_ENABLED to "true",
                    AiConfig.ENV_PROVIDER to "anthropic",
                    AiConfig.ENV_MODEL to "m",
                    AiConfig.ENV_API_KEY to SECRET,
                )
            listOf(AiConfig.ENV_PROVIDER, AiConfig.ENV_MODEL, AiConfig.ENV_API_KEY).forEach { missing ->
                val env = full - missing
                val config = AiConfig.load { env[it] }
                config.isOperational shouldBe false
                config.invalid shouldContain missing
            }
            // The fourth part, the base URL, is the OpenAI-compatible profile's explicit requirement.
            val openAi =
                load(
                    AiConfig.ENV_ENABLED to "true",
                    AiConfig.ENV_PROVIDER to "openai_compatible",
                    AiConfig.ENV_MODEL to "m",
                    AiConfig.ENV_API_KEY to SECRET,
                )
            openAi.isOperational shouldBe false
            openAi.invalid shouldContain AiConfig.ENV_BASE_URL
        }

        test("openai-compatible with an explicit https base URL is operational") {
            val config =
                load(
                    AiConfig.ENV_ENABLED to "true",
                    AiConfig.ENV_PROVIDER to "openai-compatible",
                    AiConfig.ENV_MODEL to "m",
                    AiConfig.ENV_API_KEY to SECRET,
                    AiConfig.ENV_BASE_URL to "https://api.mistral.ai/",
                )
            config.isOperational shouldBe true
            config.baseUrl shouldBe "https://api.mistral.ai"
        }

        test("an unknown provider name disables the feature and is recorded, without throwing") {
            val config =
                load(
                    AiConfig.ENV_ENABLED to "true",
                    AiConfig.ENV_PROVIDER to "skynet",
                    AiConfig.ENV_MODEL to "m",
                    AiConfig.ENV_API_KEY to SECRET,
                )
            config.isOperational shouldBe false
            config.provider shouldBe null
            config.invalid shouldContain AiConfig.ENV_PROVIDER
        }

        test("invalid numbers fall back to the default and are recorded") {
            val config =
                operational(
                    AiConfig.ENV_TOP_K to "abc",
                    AiConfig.ENV_RATE_MEMBER_HOUR to "0",
                    AiConfig.ENV_RATE_SERVER_DAY to "999999999",
                )
            config.topK shouldBe AiConfig.DEFAULT_TOP_K
            config.questionsPerMemberPerHour shouldBe AiConfig.DEFAULT_RATE_MEMBER_HOUR
            config.questionsPerServerPerDay shouldBe AiConfig.DEFAULT_RATE_SERVER_DAY
            config.invalid shouldContain AiConfig.ENV_TOP_K
            config.invalid shouldContain AiConfig.ENV_RATE_MEMBER_HOUR
            config.invalid shouldContain AiConfig.ENV_RATE_SERVER_DAY
        }

        test("valid numbers are taken over") {
            val config = operational(AiConfig.ENV_TOP_K to "4", AiConfig.ENV_RATE_MEMBER_HOUR to "3")
            config.topK shouldBe 4
            config.questionsPerMemberPerHour shouldBe 3
            config.invalid shouldNotContain AiConfig.ENV_TOP_K
        }

        test("design defaults match the specification") {
            val config = operational()
            config.maxCitations shouldBe 3
            config.maxExcerptChars shouldBe 350
            config.minQuestionChars shouldBe 8
            config.maxQuestionChars shouldBe 500
            config.maxToolCalls shouldBe 1
            config.questionsPerMemberPerHour shouldBe 10
            config.questionsPerServerPerDay shouldBe 200
            config.memberOptInDefault shouldBe false
        }

        test("a control character in the API key or model rejects the value") {
            val config = operational(AiConfig.ENV_API_KEY to "sk-abc\ndef")
            config.isOperational shouldBe false
            config.invalid shouldContain AiConfig.ENV_API_KEY
        }

        test("a bad base URL disables the feature") {
            val config = operational(AiConfig.ENV_BASE_URL to "http://api.anthropic.com")
            config.isOperational shouldBe false
            config.invalid shouldContain AiConfig.ENV_BASE_URL
        }

        test("load never throws for hostile input") {
            val config =
                load(
                    AiConfig.ENV_ENABLED to "true",
                    AiConfig.ENV_PROVIDER to "\u0000\u0001",
                    AiConfig.ENV_MODEL to "x".repeat(10_000),
                    AiConfig.ENV_API_KEY to "  ",
                    AiConfig.ENV_BASE_URL to "::::not a url::::",
                    AiConfig.ENV_TOP_K to "9".repeat(500),
                )
            config.isOperational shouldBe false
        }

        test("toString never contains the API key, not even partially") {
            val text = operational().toString()
            text shouldNotContain SECRET
            text shouldNotContain SECRET.take(8)
            text shouldContain "apiKey=***"
        }
    })
