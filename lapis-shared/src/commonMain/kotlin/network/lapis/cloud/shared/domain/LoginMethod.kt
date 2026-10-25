package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * Welle V1.9.207 -- how a member last signed in. Append-only, never reorder or rename: persisted by name in
 * `account.last_login_method` (VARCHAR(32), CHECK-constrained in V118).
 */
@Serializable
enum class LoginMethod { PASSWORD, KEYCLOAK, OIDC_FEDERATION }
