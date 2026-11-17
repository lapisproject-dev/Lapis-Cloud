package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KLogger
import network.lapis.cloud.server.config.SecretEnv
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.shared.domain.ExternalMemberIdRules

/**
 * Welle V1.9.307 -- instance configuration of the external member id. Deliberately NOT part of [KeycloakConfig]: an instance without Keycloak
 * (PdV) uses the source too, and a disabled [KeycloakConfig] does not validate everything.
 *
 * - `LAPIS_KEYCLOAK_EXTERNAL_ID_SOURCE` -- the label of the external system; unset = the feature is off (the card stays invisible). The
 *   `KEYCLOAK` prefix is historical (decision of the design team) and does not mean Keycloak is required.
 * - `LAPIS_KEYCLOAK_EXTERNAL_ID_CLAIM` -- top-level ID-token claim that carries the id; unset = no claim sync.
 * - `LAPIS_REQUIRE_EXTERNAL_ID_FOR_VOTING` -- `true`/`false`; a member without an id then has no voting right. A value that is neither is a
 *   startup error and counts as `true` (fail closed, like `LAPIS_KEYCLOAK_REQUIRE_VERIFIED_NAME`).
 *
 * None of the values is a secret; the startup log may show them.
 */
class ExternalMemberIdConfig private constructor(
    val source: String?,
    val claim: String?,
    val requireForVoting: Boolean,
    /** Names (never values) of the variables whose value was rejected. */
    val invalid: Set<String>,
    /** The raw claim variable was set (so the "claim without source" check works even when the name itself was invalid). */
    private val claimSet: Boolean,
    private val sourceSet: Boolean,
) {
    fun votingPolicy(): VotingRightPolicy = VotingRightPolicy(requireExternalMemberId = requireForVoting)

    /** The feature is usable: a valid source is configured. */
    val enabled: Boolean get() = source != null && ENV_SOURCE !in invalid

    /**
     * Throws [IllegalStateException] naming reason codes and variable NAMES (never a value) for an unusable combination; logs a warning when the
     * claim is configured but Keycloak is off. Independent of `LAPIS_KEYCLOAK_ENABLED`.
     */
    fun verifyAndLog(
        logger: KLogger,
        keycloakEnabled: Boolean,
    ) {
        if (ENV_SOURCE in
            invalid
        ) {
            error(
                "EXTERNAL_ID_SOURCE_INVALID: $ENV_SOURCE hat einen ungueltigen Wert (hoechstens 32 Zeichen: Buchstaben, Ziffern, Leerzeichen, . _ -).",
            )
        }
        if (ENV_CLAIM in invalid) error("EXTERNAL_ID_CLAIM_INVALID: $ENV_CLAIM ist kein einfacher Top-Level-Claim-Name.")
        if (ENV_REQUIRE_FOR_VOTING in invalid) error("EXTERNAL_ID_REQUIRE_INVALID: $ENV_REQUIRE_FOR_VOTING muss true oder false sein.")
        if (claimSet && !sourceSet) error("EXTERNAL_ID_CLAIM_WITHOUT_SOURCE: $ENV_CLAIM ist gesetzt, $ENV_SOURCE aber nicht.")
        if (requireForVoting && !sourceSet) {
            error(
                "EXTERNAL_ID_REQUIRE_WITHOUT_SOURCE: $ENV_REQUIRE_FOR_VOTING=true braucht $ENV_SOURCE -- sonst koennte niemand eine ID erhalten.",
            )
        }
        if (claim != null && !keycloakEnabled) {
            logger.warn {
                "EXTERNAL_ID_CLAIM_IGNORED_KEYCLOAK_DISABLED: $ENV_CLAIM ist gesetzt, aber Keycloak ist nicht aktiv -- der Claim wird nicht ausgewertet."
            }
        }
        if (enabled) {
            logger.info { "Externe Mitglieds-ID: source=$source claim=${claim ?: "<keiner>"} requireForVoting=$requireForVoting" }
        }
    }

    companion object {
        const val ENV_SOURCE = "LAPIS_KEYCLOAK_EXTERNAL_ID_SOURCE"
        const val ENV_CLAIM = "LAPIS_KEYCLOAK_EXTERNAL_ID_CLAIM"
        const val ENV_REQUIRE_FOR_VOTING = "LAPIS_REQUIRE_EXTERNAL_ID_FOR_VOTING"

        fun load(env: (String) -> String? = SecretEnv::get): ExternalMemberIdConfig {
            val invalid = linkedSetOf<String>()
            val rawSource = env(ENV_SOURCE)?.trim().orEmpty()
            val rawClaim = env(ENV_CLAIM)?.trim().orEmpty()
            val rawRequire = env(ENV_REQUIRE_FOR_VOTING)?.trim().orEmpty()

            var source: String? = null
            if (rawSource.isNotEmpty()) {
                if (ExternalMemberIdRules.isValidSource(rawSource)) source = rawSource else invalid += ENV_SOURCE
            }
            var claim: String? = null
            if (rawClaim.isNotEmpty()) {
                if (KeycloakConfig.PROVISION_CLAIM_PATTERN.matches(rawClaim)) claim = rawClaim else invalid += ENV_CLAIM
            }
            val require =
                when {
                    rawRequire.isEmpty() -> false
                    rawRequire.equals("true", ignoreCase = true) -> true
                    rawRequire.equals("false", ignoreCase = true) -> false
                    else -> {
                        invalid += ENV_REQUIRE_FOR_VOTING
                        true
                    }
                }
            return ExternalMemberIdConfig(
                source = source,
                claim = claim,
                requireForVoting = require,
                invalid = invalid,
                claimSet = rawClaim.isNotEmpty(),
                sourceSet = rawSource.isNotEmpty(),
            )
        }

        /** Feature off. Tests only. */
        val DISABLED: ExternalMemberIdConfig =
            ExternalMemberIdConfig(
                source = null,
                claim = null,
                requireForVoting = false,
                invalid = emptySet(),
                claimSet = false,
                sourceSet = false,
            )

        /** A fixed configuration. Tests only. */
        fun forTest(
            source: String?,
            claim: String? = null,
            requireForVoting: Boolean = false,
        ): ExternalMemberIdConfig =
            ExternalMemberIdConfig(
                source = source,
                claim = claim,
                requireForVoting = requireForVoting,
                invalid = emptySet(),
                claimSet = claim != null,
                sourceSet = source != null,
            )
    }
}
