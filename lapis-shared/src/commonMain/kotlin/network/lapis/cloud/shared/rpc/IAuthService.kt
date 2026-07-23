package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.SessionInfoDto

/**
 * V0.7.1 Authentifizierung -- self-service password management for an ALREADY-authenticated
 * member. The login/logout handshake itself is deliberately NOT part of this RPC interface: it
 * must be reachable BEFORE any session exists (the exact opposite of every other RPC service,
 * which resolves the caller via `resolveCurrentMember` as its first step), so it lives as a
 * dedicated HTTP route pair instead -- see
 * `network.lapis.cloud.server.routes.registerAuthRoutes` KDoc. Same "large/differently-shaped
 * payload gets a dedicated HTTP route, not RPC" reasoning
 * `network.lapis.cloud.server.routes.registerDocumentRoutes`/`registerDsgvoRoutes` already
 * establish for their own domains -- here the differentiator is authentication *order*, not
 * payload size.
 *
 * **Scope-cuts (V0.7.1, see `22-session.kuml.kts` file header)**: no OIDC login (V0.8 Federation --
 * `account.oidc_subject` stays reserved/unused, but [changePassword]'s
 * credential-verification/session-issuance split is deliberately kept separate so an OIDC path
 * can later mint sessions via the same [network.lapis.cloud.server.security.SessionStore], without
 * this wave needing to build that out now).
 *
 * **V0.7.2 delivered** (both were flagged as deferred here, and turned out NOT to need outbound
 * email infrastructure after all -- see `network.lapis.cloud.server.mail.PasswordResetMailer`
 * KDoc for why): the "forgot password" flow now lives as a dedicated HTTP route pair
 * (`/api/auth/password-reset/request`/`/api/auth/password-reset/confirm`, see
 * `network.lapis.cloud.server.routes.registerAuthRoutes`), for the exact same "reachable before
 * any session exists" reason login/logout do. Admin-created accounts (an admin-set temporary
 * password, not a reset flow) are covered by
 * `network.lapis.cloud.shared.rpc.IRegistrationService.createMemberDirect` -- still no dedicated
 * "admin resets an EXISTING member's forgotten password" endpoint; that member can always use the
 * self-service password-reset route instead.
 */
@RpcService
interface IAuthService {
    /**
     * Role: any authenticated member, self-service only -- changes the CALLER's own password.
     * [currentPassword] must match the caller's existing hash ([InvalidPasswordException] if not);
     * [newPassword] must satisfy the server-side strength policy ([WeakPasswordException] if not,
     * see `network.lapis.cloud.server.security.PasswordPolicy`). On success, every OTHER live
     * session belonging to this member is revoked (the caller's own current session stays valid)
     * -- see `network.lapis.cloud.server.security.SessionStore.revokeAllForMember`.
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    )

    /** Role: any authenticated member -- "whoami" for the currently resolved session. */
    suspend fun getSessionInfo(): SessionInfoDto
}
