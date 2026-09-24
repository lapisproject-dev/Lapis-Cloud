package network.lapis.cloud.server.mcp.config

/**
 * Welle V1.8.1 -- operator configuration of the optional MCP resource-server layer. **Default
 * OFF.** Modelled directly on `network.lapis.cloud.server.ai.config.AiConfig`: [load] never
 * throws and never fails startup -- a missing or broken configuration only ever means "feature
 * off" ([isOperational] `false`), never a startup abort. Every rejected tuning variable's NAME
 * lands in [invalid] for `McpStartupCheck` to log; the value itself is never logged.
 *
 * The deploy compose files deliberately do NOT forward any `LAPIS_MCP_*` variable -- same posture
 * as `LAPIS_AI_*`, see `AiConfig` KDoc -- an operator must add it to the `environment:` block on
 * purpose.
 */
internal class McpConfig private constructor(
    val enabled: Boolean,
    val toolCallsPerTokenPerMinute: Int,
    val toolCallsPerMemberPerHour: Int,
    val toolCallsPerServerPerDay: Int,
    val maxRequestBytes: Int,
    val maxResponseBytes: Int,
    val toolTimeoutMs: Long,
    /** Names of the `LAPIS_MCP_*` variables whose value was rejected -- for startup logging only, never a reason to throw. */
    val invalid: List<String>,
) {
    val isOperational: Boolean get() = enabled

    override fun toString(): String = "McpConfig(enabled=$enabled, invalid=$invalid)"

    companion object {
        const val ENV_ENABLED = "LAPIS_MCP_ENABLED"
        const val ENV_RATE_TOKEN_MINUTE = "LAPIS_MCP_RATE_PER_TOKEN_MINUTE"
        const val ENV_RATE_MEMBER_HOUR = "LAPIS_MCP_RATE_PER_MEMBER_HOUR"
        const val ENV_RATE_SERVER_DAY = "LAPIS_MCP_RATE_PER_SERVER_DAY"
        const val ENV_TOOL_TIMEOUT_MS = "LAPIS_MCP_TOOL_TIMEOUT_MS"

        const val DEFAULT_RATE_TOKEN_MINUTE = 20
        const val DEFAULT_RATE_MEMBER_HOUR = 300
        const val DEFAULT_RATE_SERVER_DAY = 20_000
        const val DEFAULT_MAX_REQUEST_BYTES = 16 * 1024
        const val DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024
        const val DEFAULT_TOOL_TIMEOUT_MS = 5_000L

        /**
         * Pure string/number validation, no I/O. Never throws. With `LAPIS_MCP_ENABLED` unset or
         * not exactly `true`, every tuning variable still gets validated (unlike `AiConfig`, MCP
         * has no provider profile to short-circuit on) -- a bad tuning variable is reported even
         * while the feature itself is off, so an operator sees the problem before flipping it on.
         */
        fun load(env: (String) -> String? = System::getenv): McpConfig {
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

            val rateToken = intVar(ENV_RATE_TOKEN_MINUTE, DEFAULT_RATE_TOKEN_MINUTE, 1..1_000)
            val rateMember = intVar(ENV_RATE_MEMBER_HOUR, DEFAULT_RATE_MEMBER_HOUR, 1..100_000)
            val rateServer = intVar(ENV_RATE_SERVER_DAY, DEFAULT_RATE_SERVER_DAY, 1..10_000_000)
            val toolTimeout = longVar(ENV_TOOL_TIMEOUT_MS, DEFAULT_TOOL_TIMEOUT_MS, 500L..60_000L)

            return McpConfig(
                enabled = enabled,
                toolCallsPerTokenPerMinute = rateToken,
                toolCallsPerMemberPerHour = rateMember,
                toolCallsPerServerPerDay = rateServer,
                maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                toolTimeoutMs = toolTimeout,
                invalid = invalid.toList(),
            )
        }

        /** Used by every test/`Application.module` default that does not care about MCP -- feature off, nothing to validate. */
        fun disabled(): McpConfig =
            McpConfig(
                enabled = false,
                toolCallsPerTokenPerMinute = DEFAULT_RATE_TOKEN_MINUTE,
                toolCallsPerMemberPerHour = DEFAULT_RATE_MEMBER_HOUR,
                toolCallsPerServerPerDay = DEFAULT_RATE_SERVER_DAY,
                maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                toolTimeoutMs = DEFAULT_TOOL_TIMEOUT_MS,
                invalid = emptyList(),
            )
    }
}
