// Hand-written per ADR-0016 Option B -- see V45__keycloak_login.sql.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * RP side: short-lived, single-use PKCE/state/nonce scratch state for one in-flight Keycloak
 * login attempt -- NO member FK (resolved to a member only after successful verification, see
 * `network.lapis.cloud.server.keycloak.KeycloakAccountLinker`). Mirrors `OidcRpLoginAttemptTable`
 * 1:1 in shape; a deliberately separate table because a Keycloak login attempt and a federated
 * guest login attempt are unrelated concepts with unrelated lifecycles.
 *
 * Only [stateHash] is ever stored -- the raw `state` value is never persisted, same "hash before
 * storing" contract `SessionTokens.hash` establishes throughout this codebase. Same for
 * [browserBindingHash] -- the raw browser-binding cookie value handed to the browser on `/start`
 * is never persisted, only its [network.lapis.cloud.server.security.SessionTokens.hash].
 *
 * **[browserBindingHash] (security-audit fix, V1.7.1b)** -- ties a given `state`/attempt to the
 * SPECIFIC browser that initiated it at `/start` (RFC 6749 §10.12 / OIDC Core §3.1.2.7). Without
 * this, an attacker could complete their OWN login at `/start`, capture the resulting
 * `/callback?code=...&state=...` URL instead of letting their browser follow it, and hand that URL
 * to a victim -- the victim's browser would otherwise redeem it and land in a session that is
 * actually the ATTACKER's account (login CSRF / session fixation). See
 * `network.lapis.cloud.server.routes.KeycloakAuthRoutes` `/start`/`/callback` handlers. Nullable
 * because an in-flight attempt row created by a pre-migration server build never got one -- such a
 * row simply fails the binding check on its next callback (forcing a fresh `/start`), which is the
 * correct fail-closed behavior for this 10-minute-TTL scratch table.
 */
public object KeycloakLoginAttemptTable : Table("keycloak_login_attempt") {
    public val id: Column<Uuid> = uuid("id")
    public val stateHash: Column<String> = varchar("state_hash", 64).uniqueIndex()
    public val codeVerifier: Column<String> = varchar("code_verifier", 128)
    public val nonce: Column<String> = varchar("nonce", 255)
    public val redirectUri: Column<String> = varchar("redirect_uri", 2048)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
    public val consumedAt: Column<LocalDateTime?> = datetime("consumed_at").nullable()
    public val browserBindingHash: Column<String?> = varchar("browser_binding_hash", 64).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
