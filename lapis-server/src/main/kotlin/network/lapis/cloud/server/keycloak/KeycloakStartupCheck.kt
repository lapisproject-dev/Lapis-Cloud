package network.lapis.cloud.server.keycloak

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.http.Url

/**
 * Startup-time verification of [KeycloakConfig] -- mirrors
 * `network.lapis.cloud.server.mail.SmtpStartupCheck`'s "log inventory, warn on a problem,
 * FAIL-FAST if enabled-but-broken" shape, for the same reason `SmtpStartupCheck` itself gives:
 * `LAPIS_KEYCLOAK_ENABLED=true` IS the opt-in (an env var, not a DB flag), so "enabled but not
 * [KeycloakConfig.isOperational]" can only mean a genuine misconfiguration (typo'd issuer URL,
 * forgotten client secret), never a legitimate half-state. Silently falling back to a broken/no
 * login path for every member would be exactly the kind of silent failure `SmtpStartupCheck`'s own
 * KDoc rules out for security-sensitive configuration -- either Keycloak login is fully wired, or
 * the server refuses to start and says precisely which variables are missing/invalid.
 *
 * The emergency-admin-login WARN (spec decision 3, vault "Keycloak Externe Benutzerverwaltung.md")
 * is deliberately loud and unconditional whenever an operator has disabled it -- there is no
 * technical guard against locking every admin out if Keycloak becomes unreachable once that
 * fallback is off, so this is a pure "make sure a human read this" log line, not a validation error.
 */
internal object KeycloakStartupCheck {
    /**
     * Logs an INFO line for a disabled or a complete/operational configuration (never the client
     * secret, only the issuer host), a WARN if [KeycloakConfig.emergencyAdminLoginEnabled] is
     * `false`, and **throws [IllegalStateException]** if [KeycloakConfig.enabled] is `true` but
     * [KeycloakConfig.isOperational] is `false` -- naming every entry of [KeycloakConfig.invalid] by
     * NAME ONLY, never a value, matching every other fail-fast check in this codebase (see
     * `SmtpStartupCheck`/`ConferenceStreamingConfig.load`).
     */
    fun verifyAndLog(
        config: KeycloakConfig,
        logger: KLogger,
    ) {
        if (!config.enabled) {
            // Quiet-when-off, matching SmtpConfigState.NotConfigured's own single INFO line.
            logger.info { "Kein Keycloak konfiguriert (LAPIS_KEYCLOAK_ENABLED != true) -- interner Login bleibt aktiv." }
            return
        }

        if (!config.isOperational) {
            error(
                "Keycloak-Konfiguration ist unvollstaendig (ungueltig/fehlend: ${config.invalid.joinToString(", ")}) -- " +
                    "LAPIS_KEYCLOAK_ENABLED=true, damit ist Keycloak-Login als aktiviert zu betrachten, aber die " +
                    "Konfiguration ist nicht vollstaendig/gueltig. Entweder alle Pflichtwerte " +
                    "(LAPIS_KEYCLOAK_ISSUER_URL/LAPIS_KEYCLOAK_CLIENT_ID/LAPIS_KEYCLOAK_CLIENT_SECRET) korrekt setzen " +
                    "oder LAPIS_KEYCLOAK_ENABLED weglassen/auf false setzen. Siehe KeycloakConfig KDoc.",
            )
        }

        val issuerHost = runCatching { Url(config.issuerUrl.orEmpty()).host }.getOrNull() ?: "?"
        logger.info {
            "Keycloak-Login aktiv: issuerHost=$issuerHost clientId=${config.clientId} scopes='${config.scopes}' " +
                "requireVerifiedEmail=${config.requireVerifiedEmail} rpInitiatedLogout=${config.rpInitiatedLogout} " +
                "(Client-Secret redigiert)."
        }

        if (!config.emergencyAdminLoginEnabled) {
            logger.warn {
                "LAPIS_KEYCLOAK_EMERGENCY_ADMIN_LOGIN_ENABLED=false -- es gibt KEINEN internen Login-Fallback " +
                    "fuer Admins mehr, wenn Keycloak nicht erreichbar ist. Ein Keycloak-Ausfall sperrt dann ALLE " +
                    "Mitglieder, inklusive Admins, vollstaendig aus. Nur setzen, wenn dieses Risiko bewusst " +
                    "akzeptiert wird."
            }
        }

        // Security-audit fix (MINOR 3b): loud WARN matching the emergencyAdminLoginEnabled pattern
        // above -- an operator turning this off means ANY member with an unverified-email Keycloak
        // identity can auto-link to a matching local member purely by email address, with no proof
        // they actually control that inbox. Escalated-role members (BOARD/TREASURER/ADMIN) are
        // always protected regardless of this setting (see KeycloakAccountLinker MINOR 3a fix), but
        // ordinary members are not.
        if (!config.requireVerifiedEmail) {
            logger.warn {
                "LAPIS_KEYCLOAK_REQUIRE_VERIFIED_EMAIL=false -- ein Keycloak-Login mit NICHT verifizierter " +
                    "E-Mail-Adresse kann sich weiterhin per E-Mail-Abgleich an ein passendes, noch unverknuepftes " +
                    "Mitgliedskonto binden (ausser bei BOARD/TREASURER/ADMIN-Rollen, die sind immer geschuetzt). " +
                    "Nur setzen, wenn dieses Risiko bewusst akzeptiert wird."
            }
        }
    }
}
