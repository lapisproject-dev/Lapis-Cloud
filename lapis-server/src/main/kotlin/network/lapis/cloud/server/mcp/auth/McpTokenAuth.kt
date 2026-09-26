package network.lapis.cloud.server.mcp.auth

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.federation.OidcScopes
import network.lapis.cloud.server.mcp.optin.McpMemberBlockStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The MCP resource-server authentication path -- the piece this whole wave is actually about (see
 * §0 of the implementation plan: PKCE was never the gap, THIS was). **Cookies are never read
 * anywhere in this file** (`McpStructureTest` R2 pins it) -- an MCP agent presents a bearer token
 * it obtained through the `/federation/oidc/authorize` -> `/token` grant flow, never the caller's
 * own browser session.
 *
 * **Every failure -- missing header, malformed header, an API key, an unknown/expired/revoked
 * token, a wrong scope, a wrong `resource`, a non-member caller, a blocked member -- returns the
 * exact same [Resolution.Invalid], mapped by the caller to the exact same 401 body.** No branch of
 * this function may let a caller distinguish "token unknown" from "token belongs to a blocked
 * member" from "token was for the wrong resource" -- that distinction is an oracle an attacker
 * probing this endpoint must never get.
 */
internal object McpTokenAuth {
    sealed interface Resolution {
        data class Valid(
            val principal: McpPrincipal,
        ) : Resolution

        data object Invalid : Resolution
    }

    /**
     * Mirror-image of [network.lapis.cloud.server.security.extractSessionToken]'s own API-key
     * rejection -- kept as a literal constant here (not an import of
     * `network.lapis.cloud.server.security.ApiKeyStore`) so this file never imports anything under
     * `security/`, see `McpStructureTest` R1. MUST stay byte-identical to `ApiKeyStore
     * .API_KEY_TOKEN_PREFIX` -- cross-checked by `McpTokenAuthTest`. `internal`, not `private`, for
     * exactly that reason: the test lives in this same module/package and asserts equality directly
     * against `ApiKeyStore.API_KEY_TOKEN_PREFIX` rather than duplicating the literal a third time.
     */
    internal const val API_KEY_TOKEN_PREFIX = "lapis_"

    /** At most one `last_used_at` write per token per this window -- write-amplification guard, see [touchLastUsed]. */
    private val TOUCH_INTERVAL = 1.minutes

    private val lastTouch = ConcurrentHashMap<Uuid, kotlin.time.Instant>()

    fun resolve(
        call: ApplicationCall,
        expectedResource: String,
    ): Resolution {
        val rawToken = extractBearerToken(call) ?: return Resolution.Invalid
        if (rawToken.startsWith(API_KEY_TOKEN_PREFIX)) return Resolution.Invalid

        val hash = SessionTokens.hash(rawToken)
        // UTC, NOT DbClock's own system-zone default -- OidcRoutes.issueTokens stores
        // accessExpiresAt/refreshExpiresAt via its own UTC-based nowLocalDateTime() helper; a
        // system-zone "now" here would compare UTC-stored timestamps against system-local ones,
        // sporadically (and outside UTC, systematically) treating a fresh token as expired the
        // instant the local UTC offset is nonzero.
        val now = DbClock.nowLocalDateTime(zone = TimeZone.UTC)

        val resolved =
            transaction {
                val row =
                    OidcIssuedTokenTable
                        .selectAll()
                        .where {
                            (OidcIssuedTokenTable.accessTokenHash eq hash) and
                                OidcIssuedTokenTable.revokedAt.isNull() and
                                (OidcIssuedTokenTable.accessExpiresAt greater now)
                        }.singleOrNull() ?: return@transaction null

                // Welle V1.8.2 -- the stored scope is a space-joined SET (normalized by
                // OidcRoutes' consent handler), not a single literal any more: it may be
                // "mcp:member_read" or "mcp:member_read mcp:member_write". Set-based, not
                // exact-string, comparison -- see OidcScopes.isMcpScopeSet KDoc.
                val scopeSet = row[OidcIssuedTokenTable.scope].split(" ").filter { it.isNotBlank() }.toSet()
                if (!OidcScopes.isMcpScopeSet(scopeSet)) return@transaction null
                if (row[OidcIssuedTokenTable.resource] != expectedResource) return@transaction null

                val memberId = row[OidcIssuedTokenTable.memberId]
                val memberRow = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() ?: return@transaction null
                if (memberRow[MemberTable.status] !in MemberStatusSets.ORGANIZATION_MEMBER) return@transaction null
                if (McpMemberBlockStore.isBlocked(memberId = memberId)) return@transaction null

                McpPrincipal(
                    memberId = memberId,
                    tokenId = row[OidcIssuedTokenTable.id],
                    scope = row[OidcIssuedTokenTable.scope],
                    canWrite = McpScopes.MEMBER_WRITE in scopeSet,
                    connectionLabel = row[OidcIssuedTokenTable.connectionLabel] ?: "Unbekannter Agent",
                )
            } ?: return Resolution.Invalid

        touchLastUsed(tokenId = resolved.tokenId)
        return Resolution.Valid(principal = resolved)
    }

    /**
     * Bearer-only -- MCP never accepts the session cookie (see class KDoc). Case-insensitive
     * `"Bearer "` prefix, same `substring(7)` idiom as
     * [network.lapis.cloud.server.security.extractSessionToken] (not `removePrefix`, see that
     * function's own KDoc S15 for why the distinction matters).
     */
    private fun extractBearerToken(call: ApplicationCall): String? {
        val header = call.request.headers["Authorization"] ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        return header.substring(7).trim().ifBlank { null }
    }

    /**
     * Writes `last_used_at` at most once per [TOUCH_INTERVAL] per token -- a bounded, per-JVM
     * in-memory map (same opportunistic-eviction posture as every other in-memory limiter in this
     * codebase; see `AiQuestionRateLimiter` KDoc "scope cut"). Never on the hot authorization path
     * itself for a token touched within the last minute -- 20 calls/minute must not mean 20 writes.
     */
    private fun touchLastUsed(tokenId: Uuid) {
        val now = Clock.System.now()
        val last = lastTouch[tokenId]
        if (last != null && now - last < TOUCH_INTERVAL) return
        lastTouch[tokenId] = now
        // UTC, same as every other column of oidc_issued_token (see the resolve() comment above) --
        // a system-zone "now" here would make last_used_at drift against issued_at/*_expires_at/
        // revoked_at, which are all written in UTC.
        val nowLocal: LocalDateTime = DbClock.nowLocalDateTime(zone = TimeZone.UTC)
        transaction {
            OidcIssuedTokenTable.update({ OidcIssuedTokenTable.id eq tokenId }) {
                it[lastUsedAt] = nowLocal
            }
        }
        if (lastTouch.size > 50_000) {
            lastTouch.entries.removeIf { (_, seen) -> now - seen >= TOUCH_INTERVAL }
        }
    }
}
