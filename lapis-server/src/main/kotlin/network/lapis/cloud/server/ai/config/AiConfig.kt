package network.lapis.cloud.server.ai.config

/**
 * Welle V1.6.1 -- operator configuration of the optional AI assistance layer. **Default OFF.**
 *
 * Modelled on [network.lapis.cloud.server.branding.BrandConfig], NOT on `SmtpConfig`/`PspConfig`:
 * [load] never throws and never fails the startup -- a missing or broken AI configuration only means
 * "feature off" ([isOperational] is `false`, the offending variable NAMES land in [invalid]), it
 * must never stop the server. The feature is on only when `LAPIS_AI_ENABLED` is exactly `true`
 * **and** a complete provider profile (provider, model, API key, base URL) is present.
 *
 * The deploy compose files deliberately do NOT forward any `LAPIS_AI_*` variable (guarded by
 * `AiEnvNotForwardedInComposeTest`) -- an operator must add them to the `environment:` block on
 * purpose, and must have an Art. 28 GDPR processing agreement with the provider first.
 *
 * The API key is exposed only through [apiKey] (read by the provider client) and is never part of
 * [toString], a log line or an exception message.
 */
internal class AiConfig private constructor(
    val enabled: Boolean,
    val provider: AiProviderKind?,
    val model: String?,
    private val apiKeyValue: String?,
    val baseUrl: String?,
    val maxQuestionChars: Int,
    val minQuestionChars: Int,
    val topK: Int,
    val maxCitations: Int,
    val maxExcerptChars: Int,
    val maxToolCalls: Int,
    val maxPromptChars: Int,
    val maxOutputTokens: Int,
    val questionsPerMemberPerHour: Int,
    val questionsPerServerPerDay: Int,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val maxResponseBytes: Int,
    val memberOptInDefault: Boolean,
    /** Names of the `LAPIS_AI_*` variables whose value was rejected -- for startup logging only, never a reason to throw. */
    val invalid: List<String>,
) {
    /** Read only by the provider client. */
    val apiKey: String? get() = apiKeyValue

    val isOperational: Boolean
        get() = enabled && provider != null && !model.isNullOrBlank() && !apiKeyValue.isNullOrBlank() && baseUrl != null

    /** Redacted -- never the key, not even shortened or hashed. */
    override fun toString(): String =
        "AiConfig(enabled=$enabled, provider=$provider, model=$model, baseUrl=$baseUrl, " +
            "apiKey=${if (apiKeyValue.isNullOrBlank()) "unset" else "***"}, invalid=$invalid)"

    companion object {
        const val ENV_ENABLED = "LAPIS_AI_ENABLED"
        const val ENV_PROVIDER = "LAPIS_AI_PROVIDER"
        const val ENV_MODEL = "LAPIS_AI_MODEL"
        const val ENV_API_KEY = "LAPIS_AI_API_KEY"
        const val ENV_BASE_URL = "LAPIS_AI_BASE_URL"
        const val ENV_ALLOW_PLAINTEXT_BASE_URL = "LAPIS_AI_ALLOW_PLAINTEXT_BASE_URL"
        const val ENV_TOP_K = "LAPIS_AI_TOP_K"
        const val ENV_RATE_MEMBER_HOUR = "LAPIS_AI_RATE_PER_MEMBER_HOUR"
        const val ENV_RATE_SERVER_DAY = "LAPIS_AI_RATE_PER_SERVER_DAY"
        const val ENV_MAX_OUTPUT_TOKENS = "LAPIS_AI_MAX_OUTPUT_TOKENS"
        const val ENV_REQUEST_TIMEOUT_MS = "LAPIS_AI_REQUEST_TIMEOUT_MS"
        const val ENV_MAX_RESPONSE_BYTES = "LAPIS_AI_MAX_RESPONSE_BYTES"

        /**
         * REMOVED (security audit 2026-09-19, MAJOR M-1): this variable used to pre-consent every member
         * without any consent record (Art. 7 DSGVO evidence gap). It is deliberately no longer read --
         * setting it has no effect and only produces a startup warning; consent is a member's own,
         * recorded action. Kept as a constant so the warning can name it.
         */
        const val ENV_MEMBER_OPT_IN_DEFAULT = "LAPIS_AI_MEMBER_OPT_IN_DEFAULT"

        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_TOP_K = 6
        const val DEFAULT_RATE_MEMBER_HOUR = 10
        const val DEFAULT_RATE_SERVER_DAY = 200
        const val DEFAULT_MAX_OUTPUT_TOKENS = 700
        const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L
        const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024

        private const val MAX_MODEL_LENGTH = 120

        /**
         * Pure string/number validation, no I/O. Never throws. With `LAPIS_AI_ENABLED` unset or not
         * exactly `true` nothing else is even looked at (a disabled feature has no profile to validate).
         */
        fun load(env: (String) -> String? = System::getenv): AiConfig {
            val enabled = env(ENV_ENABLED)?.trim().equals("true", ignoreCase = true)
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

            if (!enabled) return disabled()

            val provider = parseProvider(raw = env(ENV_PROVIDER)?.trim(), invalid = invalid)
            val model = parseModel(raw = env(ENV_MODEL)?.trim(), invalid = invalid)
            val apiKey = parseApiKey(raw = env(ENV_API_KEY)?.trim(), invalid = invalid)
            val allowPlaintext = env(ENV_ALLOW_PLAINTEXT_BASE_URL)?.trim().equals("true", ignoreCase = true)
            val baseUrl =
                resolveBaseUrl(provider = provider, raw = env(ENV_BASE_URL)?.trim(), allowPlaintext = allowPlaintext, invalid = invalid)

            val topK = intVar(ENV_TOP_K, DEFAULT_TOP_K, 1..20)
            val rateMember = intVar(ENV_RATE_MEMBER_HOUR, DEFAULT_RATE_MEMBER_HOUR, 1..1_000)
            val rateServer = intVar(ENV_RATE_SERVER_DAY, DEFAULT_RATE_SERVER_DAY, 1..100_000)
            val maxOutput = intVar(ENV_MAX_OUTPUT_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS, 64..4_096)
            val requestTimeout = longVar(ENV_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS, 1_000L..300_000L)
            val maxResponse = intVar(ENV_MAX_RESPONSE_BYTES, DEFAULT_MAX_RESPONSE_BYTES, 4_096..4 * 1024 * 1024)
            // Ignored on purpose, see ENV_MEMBER_OPT_IN_DEFAULT. A set variable is reported, never honoured.
            if (!env(ENV_MEMBER_OPT_IN_DEFAULT).isNullOrBlank()) invalid.add(ENV_MEMBER_OPT_IN_DEFAULT)
            val optInDefault = false

            return AiConfig(
                enabled = true,
                provider = provider,
                model = model,
                apiKeyValue = apiKey,
                baseUrl = baseUrl,
                maxQuestionChars = 500,
                minQuestionChars = 8,
                topK = topK,
                maxCitations = 3,
                maxExcerptChars = 350,
                maxToolCalls = 1,
                maxPromptChars = 24_000,
                maxOutputTokens = maxOutput,
                questionsPerMemberPerHour = rateMember,
                questionsPerServerPerDay = rateServer,
                connectTimeoutMs = 5_000L,
                requestTimeoutMs = requestTimeout,
                maxResponseBytes = maxResponse,
                memberOptInDefault = optInDefault,
                invalid = invalid.toList(),
            )
        }

        private fun disabled(): AiConfig =
            AiConfig(
                enabled = false,
                provider = null,
                model = null,
                apiKeyValue = null,
                baseUrl = null,
                maxQuestionChars = 500,
                minQuestionChars = 8,
                topK = DEFAULT_TOP_K,
                maxCitations = 3,
                maxExcerptChars = 350,
                maxToolCalls = 1,
                maxPromptChars = 24_000,
                maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                questionsPerMemberPerHour = DEFAULT_RATE_MEMBER_HOUR,
                questionsPerServerPerDay = DEFAULT_RATE_SERVER_DAY,
                connectTimeoutMs = 5_000L,
                requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                memberOptInDefault = false,
                invalid = emptyList(),
            )

        private fun parseProvider(
            raw: String?,
            invalid: MutableList<String>,
        ): AiProviderKind? {
            if (raw.isNullOrEmpty()) {
                invalid += ENV_PROVIDER
                return null
            }
            val normalized = raw.uppercase().replace('-', '_')
            val parsed = AiProviderKind.entries.firstOrNull { it.name == normalized }
            if (parsed == null) invalid += ENV_PROVIDER
            return parsed
        }

        private fun parseModel(
            raw: String?,
            invalid: MutableList<String>,
        ): String? {
            if (raw.isNullOrEmpty()) {
                invalid += ENV_MODEL
                return null
            }
            if (raw.length > MAX_MODEL_LENGTH || raw.any { it.code < 0x20 || it.code == 0x7f }) {
                invalid += ENV_MODEL
                return null
            }
            return raw
        }

        private fun parseApiKey(
            raw: String?,
            invalid: MutableList<String>,
        ): String? {
            if (raw.isNullOrEmpty()) {
                invalid += ENV_API_KEY
                return null
            }
            // A header value containing a control character would be a header-injection vector.
            if (raw.any { it.code < 0x20 || it.code == 0x7f }) {
                invalid += ENV_API_KEY
                return null
            }
            return raw
        }

        private fun resolveBaseUrl(
            provider: AiProviderKind?,
            raw: String?,
            allowPlaintext: Boolean,
            invalid: MutableList<String>,
        ): String? {
            val candidate =
                when {
                    !raw.isNullOrEmpty() -> raw
                    provider == AiProviderKind.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
                    else -> {
                        // OPENAI_COMPATIBLE has no unambiguous default host, and an unknown provider
                        // has no profile at all: the variable is required in both cases.
                        if (provider != null) invalid += ENV_BASE_URL
                        return null
                    }
                }
            val validated = AiBaseUrlGuard.validateOrNull(raw = candidate, allowPlaintextLoopback = allowPlaintext)
            if (validated == null) invalid += ENV_BASE_URL
            return validated
        }

        /** Hard upper bound for the tool-call cap -- shared by the pipeline and the tests. */
        const val HARD_MAX_TOOL_CALLS: Int = 2
    }
}
