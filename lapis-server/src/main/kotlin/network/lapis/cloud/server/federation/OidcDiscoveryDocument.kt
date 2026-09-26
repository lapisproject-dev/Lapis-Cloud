package network.lapis.cloud.server.federation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `GET /.well-known/openid-configuration` response shape (a strict subset of the full OIDC Discovery spec -- only the fields this server's own Issuer surface actually needs to advertise). */
@Serializable
data class OidcDiscoveryDto(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val jwks_uri: String,
    val registration_endpoint: String,
    val response_types_supported: List<String>,
    val grant_types_supported: List<String>,
    val subject_types_supported: List<String>,
    val id_token_signing_alg_values_supported: List<String>,
    val scopes_supported: List<String>,
    val token_endpoint_auth_methods_supported: List<String>,
    val code_challenge_methods_supported: List<String>,
    val backchannel_logout_supported: Boolean,
    val backchannel_logout_session_supported: Boolean,
)

/** Every OIDC scope this server ever grants a guest -- see [OidcScopes] KDoc "voting is never a scope". */
object OidcScopes {
    const val OPENID = "openid"
    const val PROFILE_BASIC = "profile_basic"
    const val MEMBERSHIP_STATUS = "membership_status"
    const val PZB_READ = "pzb:read"
    const val PZB_COMMENT = "pzb:comment"
    const val PZB_POST_PAID = "pzb:post_paid"

    /**
     * Welle V1.8.1 MCP-Server -- the read-only MCP scope, granting exactly the five lesend tools
     * in [network.lapis.cloud.server.mcp.tools.McpToolCatalog]. Never a stand-in for voting
     * rights, same doctrine as every guest-federation scope below.
     *
     * **Welle V1.8.2 amendment**: this is no longer necessarily the ONLY scope an MCP grant
     * carries -- [MCP_MEMBER_WRITE] may additionally be requested. What is still true, and MUST
     * remain true, is that an MCP `/authorize` request's scope set consists EXCLUSIVELY of MCP
     * scopes (never mixed with [OPENID]/[PROFILE_BASIC]/guest scopes), and [MCP_MEMBER_READ] is
     * REQUIRED whenever [MCP_MEMBER_WRITE] is requested -- writing without reading has no meaning
     * for either write tool, see `routes.OidcRoutes` MCP branch KDoc.
     */
    const val MCP_MEMBER_READ = "mcp:member_read"

    /**
     * Welle V1.8.2 "MCP-Server: Schreibwerkzeuge" -- the ADDITIONAL scope required for
     * `register_for_event`/`create_post_draft`. **Never granted alone** (see [MCP_MEMBER_READ]
     * KDoc) and **never retroactively implied by an existing [MCP_MEMBER_READ]-only token** -- a
     * token minted before this scope existed, or one whose holder never requested it, must never
     * gain write access. `McpTokenAuth` resolves this per-token from the ACTUAL stored scope
     * string, never as a blanket upgrade. See `routes.McpConsentPage` for the additional consent
     * block this scope requires.
     */
    const val MCP_MEMBER_WRITE = "mcp:member_write"

    /**
     * Every scope this Issuer will ever grant. **Voting rights are deliberately never a scope
     * literal here, full stop** -- guests never get vote weight; that is enforced structurally by
     * [network.lapis.cloud.server.rpc.requireActiveMembership] and friends already excluding
     * [network.lapis.cloud.shared.domain.MemberStatus.GUEST], not by an OIDC scope grant/deny, so
     * there is no scope string for a malicious/misconfigured home server to even attempt to smuggle
     * a vote-weight claim through. Neither [MCP_MEMBER_READ] nor [MCP_MEMBER_WRITE] is an
     * exception -- see `McpToolCatalog`'s write tools for the FULL set of side effects an MCP
     * grant can ever cause, none of them a vote.
     */
    val ALL = listOf(OPENID, PROFILE_BASIC, MEMBERSHIP_STATUS, PZB_READ, PZB_COMMENT, PZB_POST_PAID, MCP_MEMBER_READ, MCP_MEMBER_WRITE)

    /** Always granted regardless of what the caller requests -- the minimum viable "who is this guest" scope set. Never includes an MCP scope -- those are always requested explicitly, see [isMcpScopeSet]. */
    val ALWAYS_GRANTED = setOf(OPENID, PROFILE_BASIC, PZB_READ)

    /** The full family of MCP scopes -- see [isMcpScopeSet]. */
    private val MCP_SCOPES = setOf(MCP_MEMBER_READ, MCP_MEMBER_WRITE)

    /**
     * Welle V1.8.2 -- replaces the old `MCP_MEMBER_READ in requestedScopes && requestedScopes.size
     * == 1` "must be requested alone" check. `true` iff [scopes] is non-empty, consists EXCLUSIVELY
     * of MCP scopes (no mixing with any guest-federation scope), and contains [MCP_MEMBER_READ] --
     * a lone `mcp:member_write` request is rejected (see [MCP_MEMBER_WRITE] KDoc, "writing without
     * reading has no meaning"). Used by every call site that used to compare against the single
     * literal `MCP_MEMBER_READ` -- `routes.OidcRoutes`' GET/POST `/authorize` handlers, the
     * kill-switch re-check and refresh-TTL branch in `issueTokens`.
     */
    fun isMcpScopeSet(scopes: Set<String>): Boolean = scopes.isNotEmpty() && scopes.all { it in MCP_SCOPES } && MCP_MEMBER_READ in scopes
}

private val DISCOVERY_JSON = Json { encodeDefaults = true }

/**
 * Builds this server's own `GET /.well-known/openid-configuration` document -- a pure function of
 * [FederationConfig.publicBaseUrl], same derivation style as [FederationConfig.actorUri]/
 * `inboxUri`/`outboxUri`. This server's own OIDC issuer identifier IS [FederationConfig.publicBaseUrl]
 * itself (no separate `/federation/oidc` path segment in the issuer value -- the well-known
 * discovery path is what carries that prefix, per RFC 8414 /.well-known convention).
 *
 * **[build]'s `mcpEnabled` parameter** (Welle V1.8.1) -- defaults to `false` so every pre-existing
 * caller/test keeps seeing the pre-MCP document unchanged; `registerOidcRoutes` passes the real
 * `McpConfig.isOperational` value. When `false`, both MCP scopes are stripped from
 * `scopes_supported`, AND `"none"` is stripped from `token_endpoint_auth_methods_supported` -- an
 * operator with MCP off must not advertise a scope `/authorize` would then reject anyway, nor a
 * public-client (secret-free) auth method that exists solely for MCP agents and that
 * `POST /federation/oidc/register` now likewise refuses while MCP is off (see that handler).
 *
 * **[build]'s `mcpWriteEnabled` parameter** (Welle V1.8.2b) -- independent second switch, mirrors
 * `McpConfig.isWriteOperational`. When [mcpEnabled] is `true` but this is `false`,
 * [OidcScopes.MCP_MEMBER_WRITE] alone is stripped -- `mcp:member_read` stays advertised. Has no
 * effect at all when [mcpEnabled] is `false` (both MCP scopes are already gone).
 */
object OidcDiscoveryDocument {
    fun build(
        mcpEnabled: Boolean = false,
        mcpWriteEnabled: Boolean = false,
    ): OidcDiscoveryDto {
        val base = FederationConfig.publicBaseUrl
        val scopesSupported =
            OidcScopes.ALL
                .let { if (mcpEnabled) it else it - setOf(OidcScopes.MCP_MEMBER_READ, OidcScopes.MCP_MEMBER_WRITE) }
                .let { if (mcpEnabled && mcpWriteEnabled) it else it - setOf(OidcScopes.MCP_MEMBER_WRITE) }
        return OidcDiscoveryDto(
            issuer = base,
            authorization_endpoint = "$base/federation/oidc/authorize",
            token_endpoint = "$base/federation/oidc/token",
            jwks_uri = "$base/federation/oidc/jwks",
            registration_endpoint = "$base/federation/oidc/register",
            response_types_supported = listOf("code"),
            grant_types_supported = listOf("authorization_code", "refresh_token"),
            subject_types_supported = listOf("public"),
            id_token_signing_alg_values_supported = listOf("RS256"),
            scopes_supported = scopesSupported,
            token_endpoint_auth_methods_supported =
                if (mcpEnabled) listOf("client_secret_post", "none") else listOf("client_secret_post"),
            code_challenge_methods_supported = listOf("S256"),
            backchannel_logout_supported = true,
            backchannel_logout_session_supported = false,
        )
    }

    fun toJson(dto: OidcDiscoveryDto): String = DISCOVERY_JSON.encodeToString(OidcDiscoveryDto.serializer(), dto)
}
