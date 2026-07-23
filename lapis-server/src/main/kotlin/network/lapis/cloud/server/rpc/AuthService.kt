package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.extractSessionToken
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.SessionInfoDto
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
class AuthService(
    private val call: ApplicationCall,
) : IAuthService {
    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) {
        val current = resolveCurrentMember(call)
        val (storedHash, email) =
            transaction {
                (MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where { MemberTable.id eq current.memberId }
                    .single()
                    .let { it[AccountTable.passwordHash] to it[MemberTable.email] }
            }
        if (!PasswordHasher.verify(currentPassword, storedHash)) throw InvalidPasswordException()
        PasswordPolicy.validate(newPassword, email)
        val newHash = PasswordHasher.hash(newPassword)
        transaction {
            AccountTable.update({ AccountTable.memberId eq current.memberId }) {
                it[passwordHash] = newHash
            }
        }
        // Every OTHER session is invalidated; the caller's own current session (which just proved
        // knowledge of currentPassword) stays valid -- see IAuthService.changePassword KDoc.
        val ownRawToken = extractSessionToken(call)
        SessionStore.revokeAllForMember(current.memberId, exceptRawToken = ownRawToken)
    }

    override suspend fun getSessionInfo(): SessionInfoDto {
        val current = resolveCurrentMember(call)
        val displayName =
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq current.memberId }.single()[MemberTable.displayName]
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
        )
    }
}
