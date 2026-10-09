package network.lapis.cloud.server.mail

/**
 * Welle V1.9.81 -- the hourly e-mail send budget (`LAPIS_MAIL_MAX_PER_HOUR`, `LAPIS_MAIL_RESERVE_PER_HOUR`).
 *
 * A shared mail provider (netcup: 250 mails per hour per mailbox) blocks the mailbox when the limit is exceeded. The budget counts
 * every mail handed to the relay in a sliding 3600 s window and keeps [Enabled.reservePerHour] of it for system mails: a mailing-list
 * send (lane BULK) may only use `max - reserve`, a system mail (lane SYSTEM) may use all of `max`.
 *
 * **Not forwarded by `deploy/example/docker-compose.yml` on purpose** (same posture as `LAPIS_KEYCLOAK_*`, `LAPIS_AI_*`, `LAPIS_MCP_*`):
 * an operator who wants the budget adds the two variables to the compose `environment:` block (see the deploy README). A tripwire
 * (`MailBudgetEnvNotForwardedInComposeTest`) pins that.
 *
 * Invalid values fail fast at startup and name ONLY the variable, never echoing the raw value.
 */
sealed interface MailBudgetConfig {
    /** No `LAPIS_MAIL_MAX_PER_HOUR`: no throttling, no slots, no lock -- the mail path behaves as before V1.9.81. */
    data object Disabled : MailBudgetConfig

    data class Enabled(
        val maxPerHour: Int,
        val reservePerHour: Int,
    ) : MailBudgetConfig {
        init {
            require(maxPerHour in MIN_MAX..MAX_MAX) { "maxPerHour out of range" }
            require(reservePerHour in 1 until maxPerHour) { "reservePerHour out of range" }
        }

        /** What a mailing-list send may use per hour. */
        val bulkPerHour: Int get() = maxPerHour - reservePerHour
    }

    companion object {
        const val ENV_MAX = "LAPIS_MAIL_MAX_PER_HOUR"
        const val ENV_RESERVE = "LAPIS_MAIL_RESERVE_PER_HOUR"
        const val MIN_MAX = 10
        const val MAX_MAX = 10_000

        /** Default reserve: `max(1, ceil(0.2 * max))`, in integer arithmetic. */
        fun defaultReserve(max: Int): Int = maxOf(1, (max + 4) / 5)

        fun load(env: (String) -> String? = System::getenv): MailBudgetConfig {
            val rawMax = env(ENV_MAX)?.trim().orEmpty()
            val rawReserve = env(ENV_RESERVE)?.trim().orEmpty()
            if (rawMax.isEmpty()) {
                check(rawReserve.isEmpty()) { "$ENV_RESERVE is set but $ENV_MAX is not -- the reserve only makes sense with a budget." }
                return Disabled
            }
            val max = rawMax.toIntOrNull()
            check(max != null && max in MIN_MAX..MAX_MAX) { "$ENV_MAX must be an integer between $MIN_MAX and $MAX_MAX." }
            val reserve =
                if (rawReserve.isEmpty()) {
                    defaultReserve(max)
                } else {
                    val parsed = rawReserve.toIntOrNull()
                    check(parsed != null && parsed in 1 until max) { "$ENV_RESERVE must be an integer between 1 and $ENV_MAX - 1." }
                    parsed
                }
            return Enabled(maxPerHour = max, reservePerHour = reserve)
        }
    }
}
