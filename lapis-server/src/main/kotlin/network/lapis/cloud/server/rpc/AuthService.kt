package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.federation.OidcBackChannelLogoutNotifier
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.extractSessionToken
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.InvalidPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * V0.7.1 Authentifizierung -- self-service password management for an already-authenticated
 * member. See [IAuthService] KDoc for why login/logout live outside this RPC interface.
 */
class AuthService internal constructor(
    private val call: ApplicationCall,
    /**
     * V1.6.1: whether the optional AI assistance layer is operational on this server (feeds
     * [SessionInfoDto.aiAssistantEnabled]). Defaults to `false` -- feature off everywhere unless
     * `Application.kt` passes the real value.
     */
    private val aiAssistantEnabled: Boolean = false,
    /**
     * V1.7.2 sub-wave 2a -- default-constructs its own [KeycloakConfig.load] like
     * [network.lapis.cloud.server.rpc.RegistrationService]'s own `keycloakConfig` parameter does,
     * so existing call sites/tests that don't pass one keep working unchanged. Feeds
     * [SessionInfoDto.keycloakMode] (via `.enabled`, not `.isOperational` -- see that field's own
     * KDoc "why `.enabled` is safe": `KeycloakStartupCheck` fails the whole process at startup if
     * `enabled=true` but the configuration is incomplete, so by the time any request is served here
     * `enabled=true` already implies `isOperational=true`) and gates [changePassword] below (a
     * non-ADMIN member whose login is Keycloak-managed has no usable local password to change once
     * Keycloak mode is on -- same "reject a dead/confusing local-password path up front" reasoning
     * [RegistrationService]'s own `keycloakConfig.enabled` check already establishes for
     * self-registration).
     */
    private val keycloakConfig: KeycloakConfig = KeycloakConfig.load(),
    /**
     * Welle V1.8.2b -- mirrors `McpConfig.isOperational`/`.isWriteOperational` (feeds
     * [SessionInfoDto.mcpEnabled]/[SessionInfoDto.mcpWriteEnabled]). Same "defaults to false, every
     * pre-existing construction site stays source-compatible" idiom as [aiAssistantEnabled].
     */
    private val mcpEnabled: Boolean = false,
    private val mcpWriteEnabled: Boolean = false,
) : IAuthService {
    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) {
        val current = resolveCurrentMember(call)
        // V1.7.2 sub-wave 2a -- see constructor KDoc "keycloakConfig". ADMIN keeps the emergency
        // local-login path (see the vault spec's decision 3 "Notfall-Login für Admins bleibt
        // bestehen"), so ADMIN alone may still change a local password in Keycloak mode; every
        // other role is rejected before any password verification/hashing work.
        if (keycloakConfig.enabled && current.role != AccountRole.ADMIN) {
            throw ConflictException(
                "Local password changes are disabled while Keycloak login is active for this account -- " +
                    "manage your password through the external Keycloak identity provider instead.",
            )
        }
        val (storedHash, email) =
            transaction {
                (MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where { MemberTable.id eq current.memberId }
                    .single()
                    .let { it[AccountTable.passwordHash] to it[MemberTable.email] }
            }
        if (!PasswordHasher.verify(rawPassword = currentPassword, storedHash = storedHash)) throw InvalidPasswordException()
        PasswordPolicy.validate(newPassword = newPassword, email = email)
        val newHash = PasswordHasher.hash(newPassword)
        transaction {
            AccountTable.update({ AccountTable.memberId eq current.memberId }) {
                it[passwordHash] = newHash
            }
        }
        // Every OTHER session is invalidated; the caller's own current session (which just proved
        // knowledge of currentPassword) stays valid -- see IAuthService.changePassword KDoc.
        val ownRawToken = extractSessionToken(call)
        SessionStore.revokeAllForMember(memberId = current.memberId, exceptRawToken = ownRawToken)
        // V0.8.2 OIDC-Gastzugang-Federation: best-effort courtesy notification to every RP holding
        // a live grant for this member -- see OidcBackChannelLogoutNotifier KDoc "Deliberately
        // awaited inline". Never throws -- but IS awaited inline (not backgrounded), so this call
        // adds up to federationHttpClient's per-target timeout times the number of live RP grants
        // to this RPC's latency. The local revocation above has already succeeded by this point.
        OidcBackChannelLogoutNotifier.notifyAsync(current.memberId)
    }

    override suspend fun getSessionInfo(): SessionInfoDto {
        val current = resolveCurrentMember(call)
        // Left-join onto OidcGuestProfileTable (V0.8.4 Guest Badge): yields a non-null
        // homeserverUrl for a genuine GUEST member (always has a 1:1 profile row -- see
        // OidcGuestMemberStore.resolveOrCreateGuestMember) and null for a real, non-guest member
        // (no matching row) -- no separate `if (isGuest)` branch/query needed.
        val (displayName, homeserverUrl) =
            transaction {
                (MemberTable leftJoin OidcGuestProfileTable)
                    .selectAll()
                    .where { MemberTable.id eq current.memberId }
                    .single()
                    .let { it[MemberTable.displayName] to it.getOrNull(OidcGuestProfileTable.homeserverUrl) }
            }
        // Only a real, token-resolved session has a meaningful expiry -- the test-only trusted-
        // X-Member-Id fallback (see RequestContext.resolveCurrentMember KDoc) has no SessionTable
        // row at all, so expiresAt falls back to SessionStore.SESSION_TTL-from-now in that case
        // (a reasonable, harmless placeholder; that fallback path is structurally unreachable in
        // any real deployment, see AuthTestMode KDoc).
        val expiresAt =
            extractSessionToken(call)?.let { SessionStore.expiresAtOf(it) }
                ?: SessionStore.placeholderExpiry()
        return SessionInfoDto(
            memberId = current.memberId.toString(),
            displayName = displayName,
            role = current.role,
            expiresAt = expiresAt,
            isGuest = current.isGuest,
            homeserverUrl = homeserverUrl,
            status = current.status,
            aiAssistantEnabled = aiAssistantEnabled,
            keycloakMode = keycloakConfig.enabled,
            mcpEnabled = mcpEnabled,
            mcpWriteEnabled = mcpWriteEnabled,
        )
    }
}
