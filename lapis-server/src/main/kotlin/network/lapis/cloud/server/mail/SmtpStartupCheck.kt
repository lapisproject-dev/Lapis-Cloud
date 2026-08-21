package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.federation.FederationConfig

private val logger = KotlinLogging.logger {}

/**
 * V1.2.3 Echter SMTP-Versand -- startup-time verification of [SmtpConfigState], mirroring
 * `network.lapis.cloud.server.economy.oracle.PriceOracleStartupCheck`'s "log inventory, warn on a
 * problem" shape, except for one deliberate difference: [verifyAndLog] **throws** on
 * [SmtpConfigState.Incomplete].
 *
 * **Fail-fast or only loud -- why fail-fast here.** Two existing precedents in this codebase
 * disagree with each other, and this class deliberately follows the second:
 * - `SepaConfig`/`OracleSourceConfig`: never fail-fast, because a **DB flag** (not an env var)
 *   gates the feature -- `load()` genuinely cannot know at startup whether the feature will ever
 *   be used.
 * - `ConferenceStreamingConfig`: fail-fast, because an **env var** (`LAPIS_STREAMING_ENABLED`) is
 *   the opt-in -- "opted in, but broken" is unambiguously an operator error there.
 *
 * SMTP falls into the second category: the presence of ANY `LAPIS_SMTP_*` variable IS the opt-in
 * (see [SmtpConfig] KDoc), so [SmtpConfigState.Incomplete] can only mean a genuine misconfiguration
 * (a typo in a port number, a forgotten password variable), never a legitimate half-state. A
 * silently-degrading password-reset mailer is exactly the "silent failure" this feature must not
 * have -- security-sensitive delivery either works, or the server refuses to start and says
 * precisely why.
 */
object SmtpStartupCheck {
    /**
     * Logs an informational message for [SmtpConfigState.NotConfigured]/[SmtpConfigState.Configured],
     * and throws [IllegalStateException] for [SmtpConfigState.Incomplete] -- see class KDoc
     * "Fail-fast or only loud". The thrown message names every missing/invalid variable by NAME
     * ONLY -- never a value, matching every other fail-fast check in this codebase (see
     * `ConferenceStreamingConfig.load`).
     */
    fun verifyAndLog(state: SmtpConfigState) {
        when (state) {
            is SmtpConfigState.NotConfigured ->
                logger.info {
                    "Kein SMTP konfiguriert (LAPIS_SMTP_* unset) -- Passwort-Reset-/Verifizierungsmails " +
                        "werden nur geloggt, nicht versendet."
                }

            is SmtpConfigState.Configured -> {
                logger.info {
                    "SMTP-Transport aktiv: host=${state.config.host} port=${state.config.port} " +
                        "from=${state.config.fromAddress} fromName=${state.config.fromDisplayName} " +
                        "security=${state.config.transportSecurity} (Benutzername/Passwort redigiert)."
                }
                if (!FederationConfig.publicBaseUrl.startsWith("https://")) {
                    logger.warn {
                        "SMTP ist aktiv, aber LAPIS_PUBLIC_BASE_URL ('${FederationConfig.publicBaseUrl}') " +
                            "ist nicht https:// -- versendete Mails enthalten damit unbrauchbare Links."
                    }
                }
            }

            is SmtpConfigState.Incomplete -> {
                val missingPart = if (state.missing.isEmpty()) "" else "fehlend: ${state.missing.joinToString(", ")}"
                val invalidPart = if (state.invalid.isEmpty()) "" else "ungültig: ${state.invalid.joinToString(", ")}"
                val detail = listOf(missingPart, invalidPart).filter { it.isNotBlank() }.joinToString("; ")
                error(
                    "SMTP-Konfiguration ist unvollständig ($detail) -- mindestens eine LAPIS_SMTP_*-" +
                        "Variable ist gesetzt, damit ist SMTP als aktiviert zu betrachten, aber die " +
                        "Konfiguration ist nicht vollständig/gültig. Entweder alle Pflichtwerte " +
                        "(LAPIS_SMTP_HOST/LAPIS_SMTP_USERNAME/LAPIS_SMTP_PASSWORD/LAPIS_SMTP_FROM_ADDRESS/" +
                        "LAPIS_SMTP_FROM_NAME) setzen oder gar keine LAPIS_SMTP_*-Variable setzen. " +
                        "Siehe SmtpConfig KDoc.",
                )
            }
        }
    }
}
