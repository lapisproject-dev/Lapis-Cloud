package network.lapis.cloud.server.db

import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.shared.rpc.WeakPasswordException

/**
 * Pure decision core behind [StagingSeedData.seedIfEmpty] — split out for the exact reason
 * [network.lapis.cloud.server.security.AuthTestMode.evaluate] is split out: it takes its env
 * lookup as a parameter (`env: (String) -> String?`, same shape as `EmbedConfig`/`WebhookConfig`'s
 * `load(env = ...)`), so `StagingSeedConfigSafetyTest` can exercise every combination of the two
 * gates without touching the real, process-wide environment (which is immutable within one JVM
 * anyway).
 *
 * See `deploy/example/README.adoc` "Staging seed mechanism" for the four independent
 * locks that, together, are why this can safely run against a real Postgres deployment (unlike
 * [DevSeedData], which is H2-only) — this object is only the first of those four.
 */
internal sealed interface StagingSeedDecision {
    /** [StagingSeedConfig.ENV_STAGING_MODE] is not (exactly) `"true"` — the normal case everywhere except a deliberately configured staging instance. */
    data object Disabled : StagingSeedDecision

    /** Staging mode was requested but the seed password is missing or fails [PasswordPolicy]/reuse checks — the caller must fail fast, never silently fall back to [Disabled]. */
    data class Refused(
        val reason: String,
    ) : StagingSeedDecision

    data class Enabled(
        val seedPassword: String,
    ) : StagingSeedDecision
}

internal object StagingSeedConfig {
    const val ENV_STAGING_MODE: String = "LAPIS_STAGING_MODE"
    const val ENV_SEED_PASSWORD: String = "LAPIS_STAGING_SEED_PASSWORD"

    /** Never a real login target — used only so [decide] can refuse a seed password identical to the source-published [DevSeedData.DEMO_PASSWORD]. */
    private const val STAGING_POLICY_CHECK_EMAIL: String = StagingSeedData.STAGING_ADMIN_EMAIL

    fun decide(env: (String) -> String? = System::getenv): StagingSeedDecision {
        val modeValue = env(ENV_STAGING_MODE)
        // Case-sensitive, exact-literal comparison -- mirrors AuthTestMode.evaluate's
        // `testModeProperty == "true"`. "True"/"TRUE"/"1"/"yes" etc. are all treated as unset
        // rather than guessed at, so a typo in an .env file fails safe (Disabled), not open.
        if (modeValue != "true") return StagingSeedDecision.Disabled

        val password = env(ENV_SEED_PASSWORD)
        if (password.isNullOrBlank()) {
            return StagingSeedDecision.Refused(
                "$ENV_STAGING_MODE is \"true\" but $ENV_SEED_PASSWORD is not set (or blank) -- there is no " +
                    "default. Set a strong, unique password (e.g. `openssl rand -base64 24`) before starting " +
                    "this instance.",
            )
        }

        runCatching { PasswordPolicy.validate(newPassword = password, email = STAGING_POLICY_CHECK_EMAIL) }
            .onFailure { failure ->
                if (failure is WeakPasswordException) {
                    return StagingSeedDecision.Refused("$ENV_SEED_PASSWORD does not meet the password policy: ${failure.message}")
                }
                throw failure
            }

        if (password == DevSeedData.DEMO_PASSWORD) {
            return StagingSeedDecision.Refused(
                "$ENV_SEED_PASSWORD must not equal the source-published DevSeedData.DEMO_PASSWORD -- that " +
                    "password is public (it is in this repo's source code) and must never become a real login, " +
                    "not even on a staging instance.",
            )
        }

        return StagingSeedDecision.Enabled(seedPassword = password)
    }
}
