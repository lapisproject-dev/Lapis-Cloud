package network.lapis.cloud.server.keycloak

/**
 * Welle V1.7.1a -- operator configuration of the optional "Keycloak as external user management"
 * feature. **Default OFF.** See `03 Bereiche/Lapis Cloud/Keycloak Externe Benutzerverwaltung.md`
 * (vault) for the settled spec: Keycloak takes over authentication ONLY (login/password/2FA);
 * roles, membership status and the join contract stay entirely in the existing Lapis Cloud
 * database/logic. This is the *Relying Party* role -- the opposite of
 * `network.lapis.cloud.server.federation.OidcDiscoveryDocument`, where THIS server is itself the
 * Identity Provider for guests of other Lapis servers.
 *
 * Modelled directly on `network.lapis.cloud.server.ai.config.AiConfig`: [load] never throws and
 * never fails the startup -- a missing or broken profile only means "feature off"
 * ([isOperational] is `false`, the offending variable NAMES land in [invalid]). The loud
 * fail-fast-if-enabled-but-incomplete behaviour lives one layer up, in `KeycloakStartupCheck`
 * (mirroring `SmtpConfig`/`SmtpStartupCheck`'s split of concerns) -- this class only parses and
 * validates, it never decides whether to crash the process.
 *
 * [clientSecret] is exposed as a normal `val` here (unlike `AiConfig.apiKey`, which hides behind a
 * private backing field) because Kotlin data class equality/copy semantics are not used on this
 * class (it is a plain class, not a `data class`, exactly like `AiConfig`) -- the important
 * guarantee is [toString] never printing the real value, which it does not.
 */
internal class KeycloakConfig private constructor(
    val enabled: Boolean,
    val issuerUrl: String?,
    val clientId: String?,
    private val clientSecretValue: String?,
    val scopes: String,
    val requireVerifiedEmail: Boolean,
    val emergencyAdminLoginEnabled: Boolean,
    val allowPrivateIssuerHost: Boolean,
    val allowPlaintextIssuerUrl: Boolean,
    val rpInitiatedLogout: Boolean,
    val discoveryCacheSeconds: Int,
    val jwksCacheSeconds: Int,
    /** Names of the `LAPIS_KEYCLOAK_*` variables whose value was rejected/missing -- for startup logging only, never a reason to throw here. */
    val invalid: List<String>,
) {
    /** Read only by the token-exchange/back-channel HTTP calls (1b) -- never logged, never part of [toString]. */
    val clientSecret: String? get() = clientSecretValue

    val isOperational: Boolean
        get() = enabled && issuerUrl != null && !clientId.isNullOrBlank() && !clientSecretValue.isNullOrBlank()

    /** Redacted -- [clientSecretValue] never appears, not even shortened or hashed. */
    override fun toString(): String =
        "KeycloakConfig(enabled=$enabled, issuerUrl=$issuerUrl, clientId=$clientId, " +
            "clientSecret=${if (clientSecretValue.isNullOrBlank()) "unset" else "<redacted>"}, scopes=$scopes, " +
            "requireVerifiedEmail=$requireVerifiedEmail, emergencyAdminLoginEnabled=$emergencyAdminLoginEnabled, " +
            "allowPrivateIssuerHost=$allowPrivateIssuerHost, allowPlaintextIssuerUrl=$allowPlaintextIssuerUrl, " +
            "rpInitiatedLogout=$rpInitiatedLogout, discoveryCacheSeconds=$discoveryCacheSeconds, " +
            "jwksCacheSeconds=$jwksCacheSeconds, invalid=$invalid)"

    companion object {
        const val ENV_ENABLED = "LAPIS_KEYCLOAK_ENABLED"
        const val ENV_ISSUER_URL = "LAPIS_KEYCLOAK_ISSUER_URL"
        const val ENV_CLIENT_ID = "LAPIS_KEYCLOAK_CLIENT_ID"
        const val ENV_CLIENT_SECRET = "LAPIS_KEYCLOAK_CLIENT_SECRET"
        const val ENV_SCOPES = "LAPIS_KEYCLOAK_SCOPES"
        const val ENV_REQUIRE_VERIFIED_EMAIL = "LAPIS_KEYCLOAK_REQUIRE_VERIFIED_EMAIL"
        const val ENV_EMERGENCY_ADMIN_LOGIN_ENABLED = "LAPIS_KEYCLOAK_EMERGENCY_ADMIN_LOGIN_ENABLED"
        const val ENV_ALLOW_PRIVATE_ISSUER_HOST = "LAPIS_KEYCLOAK_ALLOW_PRIVATE_ISSUER_HOST"
        const val ENV_ALLOW_PLAINTEXT_ISSUER_URL = "LAPIS_KEYCLOAK_ALLOW_PLAINTEXT_ISSUER_URL"
        const val ENV_RP_INITIATED_LOGOUT = "LAPIS_KEYCLOAK_RP_INITIATED_LOGOUT"
        const val ENV_DISCOVERY_CACHE_SECONDS = "LAPIS_KEYCLOAK_DISCOVERY_CACHE_SECONDS"
        const val ENV_JWKS_CACHE_SECONDS = "LAPIS_KEYCLOAK_JWKS_CACHE_SECONDS"

        const val DEFAULT_SCOPES = "openid email profile"
        const val DEFAULT_DISCOVERY_CACHE_SECONDS = 3600
        const val DEFAULT_JWKS_CACHE_SECONDS = 300
        private val CACHE_SECONDS_RANGE = 60..86_400

        /**
         * Pure string validation, no I/O (the issuer URL is syntactically validated/normalized here
         * via [KeycloakIssuerUrlGuard] but never fetched -- discovery happens lazily in
         * `KeycloakOidcMetadata`). Never throws. With `LAPIS_KEYCLOAK_ENABLED` unset or not exactly
         * `true` nothing else is even looked at.
         */
        fun load(env: (String) -> String? = System::getenv): KeycloakConfig {
            val enabled = env(ENV_ENABLED)?.trim().equals("true", ignoreCase = true)
            val invalid = mutableListOf<String>()

            if (!enabled) return disabled()

            val allowPrivateIssuerHost = env(ENV_ALLOW_PRIVATE_ISSUER_HOST)?.trim().equals("true", ignoreCase = true)
            val allowPlaintextIssuerUrl = env(ENV_ALLOW_PLAINTEXT_ISSUER_URL)?.trim().equals("true", ignoreCase = true)

            val issuerUrl =
                resolveIssuerUrl(
                    raw = env(ENV_ISSUER_URL)?.trim(),
                    allowPrivateHost = allowPrivateIssuerHost,
                    allowPlaintext = allowPlaintextIssuerUrl,
                    invalid = invalid,
                )
            val clientId = parseHeaderSafeValue(name = ENV_CLIENT_ID, raw = env(ENV_CLIENT_ID)?.trim(), invalid = invalid)
            val clientSecret = parseHeaderSafeValue(name = ENV_CLIENT_SECRET, raw = env(ENV_CLIENT_SECRET)?.trim(), invalid = invalid)
            val scopes = resolveScopes(env(ENV_SCOPES)?.trim())

            val requireVerifiedEmail = env(ENV_REQUIRE_VERIFIED_EMAIL)?.trim()?.let { it.equals("true", ignoreCase = true) } ?: true
            val emergencyAdminLoginEnabled =
                env(ENV_EMERGENCY_ADMIN_LOGIN_ENABLED)?.trim()?.let { it.equals("true", ignoreCase = true) } ?: true
            val rpInitiatedLogout = env(ENV_RP_INITIATED_LOGOUT)?.trim()?.let { it.equals("true", ignoreCase = true) } ?: true

            val discoveryCacheSeconds =
                intVar(
                    env = env,
                    name = ENV_DISCOVERY_CACHE_SECONDS,
                    default = DEFAULT_DISCOVERY_CACHE_SECONDS,
                    range = CACHE_SECONDS_RANGE,
                    invalid = invalid,
                )
            val jwksCacheSeconds =
                intVar(
                    env = env,
                    name = ENV_JWKS_CACHE_SECONDS,
                    default = DEFAULT_JWKS_CACHE_SECONDS,
                    range = CACHE_SECONDS_RANGE,
                    invalid = invalid,
                )

            return KeycloakConfig(
                enabled = true,
                issuerUrl = issuerUrl,
                clientId = clientId,
                clientSecretValue = clientSecret,
                scopes = scopes,
                requireVerifiedEmail = requireVerifiedEmail,
                emergencyAdminLoginEnabled = emergencyAdminLoginEnabled,
                allowPrivateIssuerHost = allowPrivateIssuerHost,
                allowPlaintextIssuerUrl = allowPlaintextIssuerUrl,
                rpInitiatedLogout = rpInitiatedLogout,
                discoveryCacheSeconds = discoveryCacheSeconds,
                jwksCacheSeconds = jwksCacheSeconds,
                invalid = invalid.toList(),
            )
        }

        private fun disabled(): KeycloakConfig =
            KeycloakConfig(
                enabled = false,
                issuerUrl = null,
                clientId = null,
                clientSecretValue = null,
                scopes = DEFAULT_SCOPES,
                requireVerifiedEmail = true,
                emergencyAdminLoginEnabled = true,
                allowPrivateIssuerHost = false,
                allowPlaintextIssuerUrl = false,
                rpInitiatedLogout = true,
                discoveryCacheSeconds = DEFAULT_DISCOVERY_CACHE_SECONDS,
                jwksCacheSeconds = DEFAULT_JWKS_CACHE_SECONDS,
                invalid = emptyList(),
            )

        private fun intVar(
            env: (String) -> String?,
            name: String,
            default: Int,
            range: IntRange,
            invalid: MutableList<String>,
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

        /** Rejects an empty/missing value (required-when-enabled) and any control character (header-injection defense). */
        private fun parseHeaderSafeValue(
            name: String,
            raw: String?,
            invalid: MutableList<String>,
        ): String? {
            if (raw.isNullOrEmpty()) {
                invalid += name
                return null
            }
            if (raw.any { it.code < 0x20 || it.code == 0x7f }) {
                invalid += name
                return null
            }
            return raw
        }

        /** Default `"openid email profile"` if unset/blank; `openid` is force-added if the operator's own value omits it. */
        private fun resolveScopes(raw: String?): String {
            val base = raw?.takeUnless { it.isBlank() } ?: DEFAULT_SCOPES
            val tokens = base.split(Regex("\\s+")).filter { it.isNotBlank() }
            return if (tokens.any { it == "openid" }) tokens.joinToString(" ") else (listOf("openid") + tokens).joinToString(" ")
        }

        private fun resolveIssuerUrl(
            raw: String?,
            allowPrivateHost: Boolean,
            allowPlaintext: Boolean,
            invalid: MutableList<String>,
        ): String? {
            if (raw.isNullOrEmpty()) {
                invalid += ENV_ISSUER_URL
                return null
            }
            return when (
                val result =
                    KeycloakIssuerUrlGuard.validate(
                        raw = raw,
                        allowPrivateHost = allowPrivateHost,
                        allowPlaintext = allowPlaintext,
                    )
            ) {
                is KeycloakIssuerUrlGuard.Result.Valid -> result.normalizedUrl
                is KeycloakIssuerUrlGuard.Result.Rejected -> {
                    invalid += ENV_ISSUER_URL
                    null
                }
            }
        }
    }
}
