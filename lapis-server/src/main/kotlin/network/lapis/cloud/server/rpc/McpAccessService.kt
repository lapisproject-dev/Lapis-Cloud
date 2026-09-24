package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.federation.OidcScopes
import network.lapis.cloud.server.mcp.auth.McpTokenRevoker
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.mcp.optin.McpMemberBlockStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.McpAccessStateDto
import network.lapis.cloud.shared.domain.McpConnectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMcpAccessService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" -- member-facing kill-switch
 * and connection list, see [IMcpAccessService] KDoc. Registered UNCONDITIONALLY in
 * `Application.module` -- see [DisabledMcpAccessService] for the stub served when MCP is off.
 *
 * **The switch always wins**: flipping [setMcpAccessAllowed] to `false` both records the block
 * ([McpMemberBlockStore.setBlocked]) AND immediately revokes every existing MCP token
 * ([McpTokenRevoker.revokeAllForMember]) -- a member who turns the switch off must not have an
 * agent connection silently keep working until its access token happens to expire. Turning access
 * OFF can therefore never itself be rejected by [writeRateLimiter] -- but it still *counts* toward
 * the limiter's window, exactly like turning it ON does. Only the ON direction is actually blocked
 * once the window's failure count is exhausted, so a fast ON/OFF/ON/OFF... toggle-storm still runs
 * into the ON-side block after the configured number of combined toggles within the window -- it
 * just can never be the OFF call itself that gets rejected (module-scoped, never a constructor
 * default -- the established "fresh-per-RPC-call would be empty every time" trap, see
 * `Application.kt` comments throughout).
 */
internal class McpAccessService(
    private val call: ApplicationCall,
    private val config: McpConfig,
    private val writeRateLimiter: LoginRateLimiter,
) : IMcpAccessService {
    override suspend fun getMcpAccessState(): McpAccessStateDto {
        val current = resolveCurrentMember(call)
        requireOrganizationMember(current.status)
        return transaction { stateFor(memberId = current.memberId) }
    }

    override suspend fun setMcpAccessAllowed(allowed: Boolean): McpAccessStateDto {
        val current = resolveCurrentMember(call)
        requireOrganizationMember(current.status)
        val rateKey = "mcp-access-switch:${current.memberId}"
        // The switch always wins (see class KDoc) -- so only the "turn access ON" direction can be
        // REJECTED by this limiter. Turning access OFF must never be blockable, or a member who
        // exhausted the window by toggling the switch (or just double-clicking a slow client) would
        // be unable to shut off an unwanted agent connection for up to the window's duration. Both
        // directions still RECORD a failure, though (no `reset` here) -- otherwise an attacker could
        // clear the counter on every OFF and toggle forever without ever tripping the ON-side block.
        if (allowed && !writeRateLimiter.checkAllowed(rateKey)) {
            throw ForbiddenException("Too many switch changes -- try again later")
        }
        writeRateLimiter.recordFailure(rateKey)
        return transaction {
            McpMemberBlockStore.setBlocked(memberId = current.memberId, blocked = !allowed)
            if (!allowed) McpTokenRevoker.revokeAllForMember(memberId = current.memberId)
            stateFor(memberId = current.memberId)
        }
    }

    override suspend fun revokeConnection(tokenId: String): McpAccessStateDto {
        val current = resolveCurrentMember(call)
        requireOrganizationMember(current.status)
        val parsedTokenId = runCatching { Uuid.parse(tokenId) }.getOrNull()
        return transaction {
            if (parsedTokenId != null) McpTokenRevoker.revokeOne(memberId = current.memberId, tokenId = parsedTokenId)
            stateFor(memberId = current.memberId)
        }
    }

    private fun requireOrganizationMember(status: MemberStatus) {
        if (status !in MemberStatusSets.ORGANIZATION_MEMBER) throw ForbiddenException()
    }

    private fun stateFor(memberId: Uuid): McpAccessStateDto {
        val connections =
            OidcIssuedTokenTable
                .selectAll()
                .where {
                    (OidcIssuedTokenTable.memberId eq memberId) and
                        (OidcIssuedTokenTable.scope eq OidcScopes.MCP_MEMBER_READ) and
                        OidcIssuedTokenTable.revokedAt.isNull()
                }.orderBy(OidcIssuedTokenTable.issuedAt, SortOrder.DESC)
                .map {
                    McpConnectionDto(
                        tokenId = it[OidcIssuedTokenTable.id].toString(),
                        connectionLabel = it[OidcIssuedTokenTable.connectionLabel] ?: "Unbenannte Verbindung",
                        grantedAt = it[OidcIssuedTokenTable.issuedAt],
                        lastUsedAt = it[OidcIssuedTokenTable.lastUsedAt],
                    )
                }
        return McpAccessStateDto(
            featureEnabled = config.isOperational,
            accessAllowed = !McpMemberBlockStore.isBlocked(memberId = memberId),
            connections = connections,
        )
    }
}
