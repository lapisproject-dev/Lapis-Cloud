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
    /**
     * Welle V1.9.73 -- `LAPIS_KEYCLOAK_AUTO_PROVISION`: create a member on the first login when the verified ID token carries
     * the required group and no member matches. **Default OFF.** See [KeycloakMemberProvisioner].
     */
    val autoProvision: Boolean,
    /** Top-level ID-token claim that carries the groups (default `groups`). Nested paths such as `realm_access.roles` are not supported. */
    val provisionClaim: String,
    /** The one group whose members may be provisioned. Required when [autoProvision] is on. */
    val provisionGroup: String?,
    /** Maximum number of just-in-time creations per hour (counted in the database, per instance). */
    val provisionRatePerHour: Int,
    /** Welle V1.9.73 -- `LAPIS_KEYCLOAK_SYNC_PROFILE`: take name (and, under strict rules, address) over from the ID token at login. Default OFF. */
    val syncProfile: Boolean,
    /**
     * Names of `LAPIS_KEYCLOAK_AUTO_PROVISION` / `LAPIS_KEYCLOAK_SYNC_PROFILE` that are switched ON while `LAPIS_KEYCLOAK_ENABLED` is
     * not -- a misconfiguration the startup check refuses (the operator believes provisioning is active, it silently is not).
     */
    val orphanedOptions: List<String>,
    /** Names of the `LAPIS_KEYCLOAK_*` variables whose value was rejected/missing -- for startup logging only, never a reason to throw here. */
    val invalid: List<String>,
) {
    /** Read only by the token-exchange/back-channel HTTP calls (1b) -- never logged, never part of [toString]. */
    val clientSecret: String? get() = clientSecretValue

    val isOperational: Boolean
        get() =
            enabled &&
                issuerUrl != null &&
                !clientId.isNullOrBlank() &&
                !clientSecretValue.isNullOrBlank() &&
                (!autoProvision || provisionGroup != null) &&
                ENV_PROVISION_CLAIM !in invalid &&
                ENV_PROVISION_GROUP !in invalid &&
                ENV_PROVISION_RATE_PER_HOUR !in invalid

    /** Redacted -- [clientSecretValue] never appears, not even shortened or hashed. */
    override fun toString(): String =
        "KeycloakConfig(enabled=$enabled, issuerUrl=$issuerUrl, clientId=$clientId, " +
            "clientSecret=${if (clientSecretValue.isNullOrBlank()) "unset" else "<redacted>"}, scopes=$scopes, " +
            "requireVerifiedEmail=$requireVerifiedEmail, emergencyAdminLoginEnabled=$emergencyAdminLoginEnabled, " +
            "allowPrivateIssuerHost=$allowPrivateIssuerHost, allowPlaintextIssuerUrl=$allowPlaintextIssuerUrl, " +
            "rpInitiatedLogout=$rpInitiatedLogout, discoveryCacheSeconds=$discoveryCacheSeconds, " +
            "jwksCacheSeconds=$jwksCacheSeconds, autoProvision=$autoProvision, provisionClaim=$provisionClaim, " +
            "provisionGroup=$provisionGroup, provisionRatePerHour=$provisionRatePerHour, syncProfile=$syncProfile, " +
            "orphanedOptions=$orphanedOptions, invalid=$invalid)"

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
        const val ENV_AUTO_PROVISION = "LAPIS_KEYCLOAK_AUTO_PROVISION"
        const val ENV_PROVISION_CLAIM = "LAPIS_KEYCLOAK_PROVISION_CLAIM"
        const val ENV_PROVISION_GROUP = "LAPIS_KEYCLOAK_PROVISION_GROUP"
        const val ENV_PROVISION_RATE_PER_HOUR = "LAPIS_KEYCLOAK_PROVISION_RATE_PER_HOUR"
        const val ENV_SYNC_PROFILE = "LAPIS_KEYCLOAK_SYNC_PROFILE"

        const val DEFAULT_PROVISION_CLAIM = "groups"
        const val DEFAULT_PROVISION_RATE_PER_HOUR = 10
        private val PROVISION_RATE_RANGE = 1..1000
        private const val PROVISION_GROUP_MAX_LENGTH = 255

        /** A top-level claim name. A dot is rejected on purpose: nested paths are not supported. */
        private val PROVISION_CLAIM_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_:-]{0,63}$")

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

            // Read BEFORE the early return below, so "switched on, but ENABLED missing" is detectable by the startup check.
            val autoProvision = env(ENV_AUTO_PROVISION)?.trim().equals("true", ignoreCase = true)
            val syncProfile = env(ENV_SYNC_PROFILE)?.trim().equals("true", ignoreCase = true)

            if (!enabled) {
                return disabled(
                    orphanedOptions =
                        listOfNotNull(
                            ENV_AUTO_PROVISION.takeIf { autoProvision },
                            ENV_SYNC_PROFILE.takeIf { syncProfile },
                        ),
                )
            }

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

            val provisionClaim = resolveProvisionClaim(raw = env(ENV_PROVISION_CLAIM)?.trim(), invalid = invalid)
            val provisionGroup = resolveProvisionGroup(raw = env(ENV_PROVISION_GROUP)?.trim(), required = autoProvision, invalid = invalid)
            val provisionRatePerHour =
                intVar(
                    env = env,
                    name = ENV_PROVISION_RATE_PER_HOUR,
                    default = DEFAULT_PROVISION_RATE_PER_HOUR,
                    range = PROVISION_RATE_RANGE,
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
                autoProvision = autoProvision,
                provisionClaim = provisionClaim,
                provisionGroup = provisionGroup,
                provisionRatePerHour = provisionRatePerHour,
                syncProfile = syncProfile,
                orphanedOptions = emptyList(),
                invalid = invalid.toList(),
            )
        }

        private fun disabled(orphanedOptions: List<String> = emptyList()): KeycloakConfig =
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
                autoProvision = false,
                provisionClaim = DEFAULT_PROVISION_CLAIM,
                provisionGroup = null,
                provisionRatePerHour = DEFAULT_PROVISION_RATE_PER_HOUR,
                syncProfile = false,
                orphanedOptions = orphanedOptions,
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

        /** Unset/blank -> `groups`. A value that is not a plain top-level claim name is rejected (the variable NAME lands in [invalid]). */
        private fun resolveProvisionClaim(
            raw: String?,
            invalid: MutableList<String>,
        ): String {
            if (raw.isNullOrEmpty()) return DEFAULT_PROVISION_CLAIM
            if (!PROVISION_CLAIM_PATTERN.matches(raw)) {
                invalid += ENV_PROVISION_CLAIM
                return DEFAULT_PROVISION_CLAIM
            }
            return raw
        }

        /**
         * Trimmed, non-empty, no control character, at most 255 characters, at most ONE leading `/` and no further `/`
         * (a nested group path is not supported). Required while [required] (auto-provisioning is on).
         */
        private fun resolveProvisionGroup(
            raw: String?,
            required: Boolean,
            invalid: MutableList<String>,
        ): String? {
            if (raw.isNullOrEmpty()) {
                if (required) invalid += ENV_PROVISION_GROUP
                return null
            }
            val bare = raw.removePrefix("/")
            val valid =
                raw.length <= PROVISION_GROUP_MAX_LENGTH &&
                    bare.isNotEmpty() &&
                    '/' !in bare &&
                    raw.none { it.code < 0x20 || it.code == 0x7f }
            if (!valid) {
                invalid += ENV_PROVISION_GROUP
                return null
            }
            return raw
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
