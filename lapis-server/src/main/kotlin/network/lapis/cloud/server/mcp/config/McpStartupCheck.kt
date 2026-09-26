package network.lapis.cloud.server.mcp.config

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Startup logging for [McpConfig], same "log inventory, never fail" shape as `AiStartupCheck`. */
internal object McpStartupCheck {
    fun log(config: McpConfig) {
        if (!config.enabled) {
            if (config.invalid.isNotEmpty()) {
                logger.warn {
                    "MCP-Server: aus, aber ungültige Tuning-Variable(n) (${config.invalid.joinToString(
                        ", ",
                    )}) -- Standardwerte würden verwendet, falls das Feature eingeschaltet wird."
                }
            }
            logger.info { "MCP-Server: aus (LAPIS_MCP_ENABLED nicht auf true gesetzt)." }
            return
        }
        if (config.invalid.isNotEmpty()) {
            logger.warn {
                "MCP-Server: aktiv, aber ungültige Tuning-Variable(n) (${config.invalid.joinToString(
                    ", ",
                )}) -- Standardwert wird verwendet."
            }
        }
        logger.info {
            "MCP-Server: aktiv, rateLimits=token:${config.toolCallsPerTokenPerMinute}/min " +
                "member:${config.toolCallsPerMemberPerHour}/h server:${config.toolCallsPerServerPerDay}/d, " +
                "toolTimeoutMs=${config.toolTimeoutMs}, writeEnabled=${config.writeEnabled}."
        }
    }
}
