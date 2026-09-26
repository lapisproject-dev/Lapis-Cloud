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
    /**
     * Welle V1.8.2b -- the SECOND operator switch, additive on top of [enabled]: `mcp:member_write`
     * grants, the `tools/list` catalog entries for the two writing tools, and
     * `McpToolDispatcher.dispatch`'s own write-scope check all key off [isWriteOperational], never
     * this raw field alone. Default `false`, same "no startup abort" posture as [enabled] --
     * turning writing OFF never touches a member's existing drafts (they stay editable/releasable/
     * discardable), it only blocks NEW `register_for_event`/`create_post_draft` calls.
     */
    val writeEnabled: Boolean,
    val toolCallsPerTokenPerMinute: Int,
    val toolCallsPerMemberPerHour: Int,
    val toolCallsPerServerPerDay: Int,
    val maxRequestBytes: Int,
    val maxResponseBytes: Int,
    val toolTimeoutMs: Long,
    /** Welle V1.8.2 -- `register_for_event`'s own three-tier quota, additive on top of the global window above. */
    val eventRegistrationPerTokenPerHour: Int,
    val eventRegistrationPerMemberPerDay: Int,
    val eventRegistrationPerServerPerDay: Int,
    /** Welle V1.8.2 -- `create_post_draft`'s own three-tier quota, additive on top of the global window above. */
    val postDraftPerTokenPerHour: Int,
    val postDraftPerMemberPerDay: Int,
    val postDraftPerServerPerDay: Int,
    /** Names of the `LAPIS_MCP_*` variables whose value was rejected -- for startup logging only, never a reason to throw. */
    val invalid: List<String>,
) {
    val isOperational: Boolean get() = enabled

    /** Welle V1.8.2b -- a write switch without MCP itself is meaningless; never check [writeEnabled] alone. */
    val isWriteOperational: Boolean get() = enabled && writeEnabled

    override fun toString(): String = "McpConfig(enabled=$enabled, writeEnabled=$writeEnabled, invalid=$invalid)"

    companion object {
        const val ENV_ENABLED = "LAPIS_MCP_ENABLED"

        /** Welle V1.8.2b -- second operator switch, see [writeEnabled] KDoc. Default OFF like every other `LAPIS_MCP_*` variable. */
        const val ENV_WRITE_ENABLED = "LAPIS_MCP_WRITE_ENABLED"
        const val ENV_RATE_TOKEN_MINUTE = "LAPIS_MCP_RATE_PER_TOKEN_MINUTE"
        const val ENV_RATE_MEMBER_HOUR = "LAPIS_MCP_RATE_PER_MEMBER_HOUR"
        const val ENV_RATE_SERVER_DAY = "LAPIS_MCP_RATE_PER_SERVER_DAY"
        const val ENV_TOOL_TIMEOUT_MS = "LAPIS_MCP_TOOL_TIMEOUT_MS"

        // Welle V1.8.2 -- six write-tool quota variables, same "never fail startup" posture as
        // every variable above.
        const val ENV_WRITE_RATE_EVENT_TOKEN_HOUR = "LAPIS_MCP_WRITE_RATE_EVENT_TOKEN_HOUR"
        const val ENV_WRITE_RATE_EVENT_MEMBER_DAY = "LAPIS_MCP_WRITE_RATE_EVENT_MEMBER_DAY"
        const val ENV_WRITE_RATE_EVENT_SERVER_DAY = "LAPIS_MCP_WRITE_RATE_EVENT_SERVER_DAY"
        const val ENV_WRITE_RATE_DRAFT_TOKEN_HOUR = "LAPIS_MCP_WRITE_RATE_DRAFT_TOKEN_HOUR"
        const val ENV_WRITE_RATE_DRAFT_MEMBER_DAY = "LAPIS_MCP_WRITE_RATE_DRAFT_MEMBER_DAY"
        const val ENV_WRITE_RATE_DRAFT_SERVER_DAY = "LAPIS_MCP_WRITE_RATE_DRAFT_SERVER_DAY"

        const val DEFAULT_RATE_TOKEN_MINUTE = 20
        const val DEFAULT_RATE_MEMBER_HOUR = 300
        const val DEFAULT_RATE_SERVER_DAY = 20_000
        const val DEFAULT_MAX_REQUEST_BYTES = 16 * 1024
        const val DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024
        const val DEFAULT_TOOL_TIMEOUT_MS = 5_000L

        const val DEFAULT_WRITE_RATE_EVENT_TOKEN_HOUR = 5
        const val DEFAULT_WRITE_RATE_EVENT_MEMBER_DAY = 20
        const val DEFAULT_WRITE_RATE_EVENT_SERVER_DAY = 2_000
        const val DEFAULT_WRITE_RATE_DRAFT_TOKEN_HOUR = 3
        const val DEFAULT_WRITE_RATE_DRAFT_MEMBER_DAY = 10
        const val DEFAULT_WRITE_RATE_DRAFT_SERVER_DAY = 1_000

        /**
         * Pure string/number validation, no I/O. Never throws. With `LAPIS_MCP_ENABLED` unset or
         * not exactly `true`, every tuning variable still gets validated (unlike `AiConfig`, MCP
         * has no provider profile to short-circuit on) -- a bad tuning variable is reported even
         * while the feature itself is off, so an operator sees the problem before flipping it on.
         */
        fun load(env: (String) -> String? = System::getenv): McpConfig {
            val enabled = env(ENV_ENABLED)?.trim().equals("true", ignoreCase = true)
            // Boolean has no "invalid" shape (any non-"true" value simply means false) -- no
            // `invalid` entry for this variable, same as ENV_ENABLED itself.
            val writeEnabled = env(ENV_WRITE_ENABLED)?.trim().equals("true", ignoreCase = true)
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

            val eventTokenHour = intVar(ENV_WRITE_RATE_EVENT_TOKEN_HOUR, DEFAULT_WRITE_RATE_EVENT_TOKEN_HOUR, 1..100)
            val eventMemberDay = intVar(ENV_WRITE_RATE_EVENT_MEMBER_DAY, DEFAULT_WRITE_RATE_EVENT_MEMBER_DAY, 1..1_000)
            val eventServerDay = intVar(ENV_WRITE_RATE_EVENT_SERVER_DAY, DEFAULT_WRITE_RATE_EVENT_SERVER_DAY, 1..1_000_000)
            val draftTokenHour = intVar(ENV_WRITE_RATE_DRAFT_TOKEN_HOUR, DEFAULT_WRITE_RATE_DRAFT_TOKEN_HOUR, 1..100)
            val draftMemberDay = intVar(ENV_WRITE_RATE_DRAFT_MEMBER_DAY, DEFAULT_WRITE_RATE_DRAFT_MEMBER_DAY, 1..1_000)
            val draftServerDay = intVar(ENV_WRITE_RATE_DRAFT_SERVER_DAY, DEFAULT_WRITE_RATE_DRAFT_SERVER_DAY, 1..1_000_000)

            return McpConfig(
                enabled = enabled,
                writeEnabled = writeEnabled,
                toolCallsPerTokenPerMinute = rateToken,
                toolCallsPerMemberPerHour = rateMember,
                toolCallsPerServerPerDay = rateServer,
                maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                toolTimeoutMs = toolTimeout,
                eventRegistrationPerTokenPerHour = eventTokenHour,
                eventRegistrationPerMemberPerDay = eventMemberDay,
                eventRegistrationPerServerPerDay = eventServerDay,
                postDraftPerTokenPerHour = draftTokenHour,
                postDraftPerMemberPerDay = draftMemberDay,
                postDraftPerServerPerDay = draftServerDay,
                invalid = invalid.toList(),
            )
        }

        /** Used by every test/`Application.module` default that does not care about MCP -- feature off, nothing to validate. */
        fun disabled(): McpConfig =
            McpConfig(
                enabled = false,
                writeEnabled = false,
                toolCallsPerTokenPerMinute = DEFAULT_RATE_TOKEN_MINUTE,
                toolCallsPerMemberPerHour = DEFAULT_RATE_MEMBER_HOUR,
                toolCallsPerServerPerDay = DEFAULT_RATE_SERVER_DAY,
                maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES,
                maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
                toolTimeoutMs = DEFAULT_TOOL_TIMEOUT_MS,
                eventRegistrationPerTokenPerHour = DEFAULT_WRITE_RATE_EVENT_TOKEN_HOUR,
                eventRegistrationPerMemberPerDay = DEFAULT_WRITE_RATE_EVENT_MEMBER_DAY,
                eventRegistrationPerServerPerDay = DEFAULT_WRITE_RATE_EVENT_SERVER_DAY,
                postDraftPerTokenPerHour = DEFAULT_WRITE_RATE_DRAFT_TOKEN_HOUR,
                postDraftPerMemberPerDay = DEFAULT_WRITE_RATE_DRAFT_MEMBER_DAY,
                postDraftPerServerPerDay = DEFAULT_WRITE_RATE_DRAFT_SERVER_DAY,
                invalid = emptyList(),
            )
    }
}
