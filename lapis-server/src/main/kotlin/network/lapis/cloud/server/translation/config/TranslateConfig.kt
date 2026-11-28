package network.lapis.cloud.server.translation.config

import network.lapis.cloud.server.ai.config.AiProviderKind
import network.lapis.cloud.server.ai.config.ProviderProfileParsing
import network.lapis.cloud.server.ai.llm.LlmProviderProfile
import network.lapis.cloud.server.config.SecretEnv

/**
 * Welle V1.9.342 -- operator configuration of the just-in-time translation of public content. **Default OFF.**
 *
 * Same posture as [network.lapis.cloud.server.ai.config.AiConfig]: [load] never throws and never fails the startup; a missing or broken
 * configuration only means "feature off" ([isOperational] is `false`, the offending variable NAMES land in [invalid]). The feature is on only
 * when `LAPIS_TRANSLATE_ENABLED` is exactly `true` **and** a complete provider profile (provider, model, API key, base URL) is present. It has
 * its OWN profile (`LAPIS_TRANSLATE_*`), independent of `LAPIS_AI_*`: a key set for the statute Q&A never enables translation, and vice versa.
 *
 * The deploy compose files deliberately do NOT forward any `LAPIS_TRANSLATE_*` variable (guarded by `TranslationStructureTest`) -- an operator
 * must add them on purpose, and must have a processing agreement (Art. 28 GDPR) with the provider first. The text of a public event leaves the
 * server for that provider.
 *
 * The API key is exposed only through [apiKey] and is never part of [toString], a log line or an exception message.
 */
internal class TranslateConfig private constructor(
    val enabled: Boolean,
    val provider: AiProviderKind?,
    val model: String?,
    private val apiKeyValue: String?,
    val baseUrl: String?,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
    /** How long a request waits for a translation job before it is answered with the original (the job keeps running). */
    val waitTimeoutMs: Long,
    val maxResponseBytes: Int,
    val maxOutputTokens: Int,
    val maxSourceCharsPerContent: Int,
    val dailyCharBudget: Int,
    /** Wired in T2 (public API); built and tested in T1. */
    val requestsPerIpPerMinute: Int,
    val maxConcurrentCalls: Int,
    /** Names of the `LAPIS_TRANSLATE_*` variables whose value was rejected -- for startup logging only. */
    val invalid: List<String>,
) {
    /** Read only by the provider client. */
    val apiKey: String? get() = apiKeyValue

    val isOperational: Boolean
        get() = enabled && provider != null && !model.isNullOrBlank() && !apiKeyValue.isNullOrBlank() && baseUrl != null

    /** The profile for the shared LLM client factory; `null` when the feature is not operational. */
    fun providerProfileOrNull(): LlmProviderProfile? {
        if (!isOperational) return null
        return LlmProviderProfile(
            provider = provider ?: return null,
            model = model ?: return null,
            apiKeyValue = apiKeyValue ?: return null,
            baseUrl = baseUrl ?: return null,
            connectTimeoutMs = connectTimeoutMs,
            requestTimeoutMs = requestTimeoutMs,
            maxResponseBytes = maxResponseBytes,
        )
    }

    /** Redacted -- never the key, not even shortened or hashed. */
    override fun toString(): String =
        "TranslateConfig(enabled=$enabled, provider=$provider, model=$model, baseUrl=$baseUrl, " +
            "apiKey=${if (apiKeyValue.isNullOrBlank()) "unset" else "***"}, invalid=$invalid)"

    companion object {
        const val ENV_ENABLED = "LAPIS_TRANSLATE_ENABLED"
        const val ENV_PROVIDER = "LAPIS_TRANSLATE_PROVIDER"
        const val ENV_MODEL = "LAPIS_TRANSLATE_MODEL"
        const val ENV_API_KEY = "LAPIS_TRANSLATE_API_KEY"
        const val ENV_BASE_URL = "LAPIS_TRANSLATE_BASE_URL"
        const val ENV_ALLOW_PLAINTEXT_BASE_URL = "LAPIS_TRANSLATE_ALLOW_PLAINTEXT_BASE_URL"
        const val ENV_REQUEST_TIMEOUT_MS = "LAPIS_TRANSLATE_REQUEST_TIMEOUT_MS"
        const val ENV_WAIT_MS = "LAPIS_TRANSLATE_WAIT_MS"
        const val ENV_MAX_RESPONSE_BYTES = "LAPIS_TRANSLATE_MAX_RESPONSE_BYTES"
        const val ENV_MAX_OUTPUT_TOKENS = "LAPIS_TRANSLATE_MAX_OUTPUT_TOKENS"
        const val ENV_MAX_SOURCE_CHARS = "LAPIS_TRANSLATE_MAX_SOURCE_CHARS"
        const val ENV_DAILY_CHAR_BUDGET = "LAPIS_TRANSLATE_DAILY_CHAR_BUDGET"
        const val ENV_RATE_PER_IP_MINUTE = "LAPIS_TRANSLATE_RATE_PER_IP_MINUTE"
        const val ENV_MAX_CONCURRENT = "LAPIS_TRANSLATE_MAX_CONCURRENT"

        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        const val DEFAULT_WAIT_MS = 8_000L
        const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024
        const val DEFAULT_MAX_OUTPUT_TOKENS = 6_000
        const val DEFAULT_MAX_SOURCE_CHARS = 12_000
        const val DEFAULT_DAILY_CHAR_BUDGET = 200_000
        const val DEFAULT_RATE_PER_IP_MINUTE = 20
        const val DEFAULT_MAX_CONCURRENT = 2
        private const val CONNECT_TIMEOUT_MS = 5_000L

        /**
         * Pure string/number validation, no I/O. Never throws. With `LAPIS_TRANSLATE_ENABLED` unset or not exactly `true` nothing else is
         * even looked at.
         */
        fun load(env: (String) -> String? = SecretEnv::get): TranslateConfig {
            val enabled = env(ENV_ENABLED)?.trim().equals("true", ignoreCase = true)
            if (!enabled) return disabled()
            val invalid = mutableListOf<String>()

            fun intVar(
                name: String,
                default: Int,
                range: IntRange,
            ): Int {
                val raw = env(name)?.trim()?.takeUnless { it.isEmpty() } ?: return default
                val parsed = raw.toIntOrNull()
                return if (parsed != null && parsed in range) {
                    parsed
                } else {
                    invalid += name
                    default
                }
            }

            fun longVar(
                name: String,
                default: Long,
                range: LongRange,
            ): Long {
                val raw = env(name)?.trim()?.takeUnless { it.isEmpty() } ?: return default
                val parsed = raw.toLongOrNull()
                return if (parsed != null && parsed in range) {
                    parsed
                } else {
                    invalid += name
                    default
                }
            }

            val provider = ProviderProfileParsing.parseProvider(raw = env(ENV_PROVIDER)?.trim(), envName = ENV_PROVIDER, invalid = invalid)
            val model = ProviderProfileParsing.parseModel(raw = env(ENV_MODEL)?.trim(), envName = ENV_MODEL, invalid = invalid)
            val apiKey = ProviderProfileParsing.parseApiKey(raw = env(ENV_API_KEY)?.trim(), envName = ENV_API_KEY, invalid = invalid)
            val allowPlaintext = env(ENV_ALLOW_PLAINTEXT_BASE_URL)?.trim().equals("true", ignoreCase = true)
            val baseUrl =
                ProviderProfileParsing.resolveBaseUrl(
                    provider = provider,
                    raw = env(ENV_BASE_URL)?.trim(),
                    allowPlaintext = allowPlaintext,
                    envName = ENV_BASE_URL,
                    invalid = invalid,
                )

            return TranslateConfig(
                enabled = true,
                provider = provider,
                model = model,
                apiKeyValue = apiKey,
                baseUrl = baseUrl,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                requestTimeoutMs = longVar(ENV_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS, 1_000L..120_000L),
                waitTimeoutMs = longVar(ENV_WAIT_MS, DEFAULT_WAIT_MS, 500L..30_000L),
                maxResponseBytes = intVar(ENV_MAX_RESPONSE_BYTES, DEFAULT_MAX_RESPONSE_BYTES, 4_096..4 * 1024 * 1024),
                maxOutputTokens = intVar(ENV_MAX_OUTPUT_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS, 256..16_384),
                maxSourceCharsPerContent = intVar(ENV_MAX_SOURCE_CHARS, DEFAULT_MAX_SOURCE_CHARS, 100..50_000),
                dailyCharBudget = intVar(ENV_DAILY_CHAR_BUDGET, DEFAULT_DAILY_CHAR_BUDGET, 1_000..50_000_000),
                requestsPerIpPerMinute = intVar(ENV_RATE_PER_IP_MINUTE, DEFAULT_RATE_PER_IP_MINUTE, 1..1_000),
                maxConcurrentCalls = intVar(ENV_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT, 1..8),
                invalid = invalid.toList(),
            )
        }

        /** The feature-off configuration (also what [load] returns while `LAPIS_TRANSLATE_ENABLED` is not `true`). */
        fun disabled(): TranslateConfig =
            TranslateConfig(
                enabled = false,
                provider = null,
                model = null,
                apiKeyValue = null,
                baseUrl = null,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
                waitTimeoutMs = DEFAULT_WAIT_MS,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                maxSourceCharsPerContent = DEFAULT_MAX_SOURCE_CHARS,
                dailyCharBudget = DEFAULT_DAILY_CHAR_BUDGET,
                requestsPerIpPerMinute = DEFAULT_RATE_PER_IP_MINUTE,
                maxConcurrentCalls = DEFAULT_MAX_CONCURRENT,
                invalid = emptyList(),
            )

        /**
         * Test/dev helper: an operational configuration for [provider]/[baseUrl] with explicit tuning. Not a production path -- production
         * always goes through [load]. [apiKey] is whatever the test wants to prove never leaks.
         */
        internal fun operationalForTest(
            apiKey: String = "test-key",
            provider: AiProviderKind = AiProviderKind.OPENAI_COMPATIBLE,
            model: String = "test-model",
            baseUrl: String = "https://api.example.com",
            waitTimeoutMs: Long = DEFAULT_WAIT_MS,
            maxSourceCharsPerContent: Int = DEFAULT_MAX_SOURCE_CHARS,
            dailyCharBudget: Int = DEFAULT_DAILY_CHAR_BUDGET,
            maxConcurrentCalls: Int = DEFAULT_MAX_CONCURRENT,
        ): TranslateConfig =
            TranslateConfig(
                enabled = true,
                provider = provider,
                model = model,
                apiKeyValue = apiKey,
                baseUrl = baseUrl,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
                waitTimeoutMs = waitTimeoutMs,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                maxSourceCharsPerContent = maxSourceCharsPerContent,
                dailyCharBudget = dailyCharBudget,
                requestsPerIpPerMinute = DEFAULT_RATE_PER_IP_MINUTE,
                maxConcurrentCalls = maxConcurrentCalls,
                invalid = emptyList(),
            )
    }
}
