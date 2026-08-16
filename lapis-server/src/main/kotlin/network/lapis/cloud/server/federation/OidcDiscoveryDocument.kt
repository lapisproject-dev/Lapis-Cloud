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
     * Every scope this Issuer will ever grant. **Voting rights are deliberately never a scope
     * literal here, full stop** -- guests never get vote weight; that is enforced structurally by
     * [network.lapis.cloud.server.rpc.requireActiveMembership] and friends already excluding
     * [network.lapis.cloud.shared.domain.MemberStatus.GUEST], not by an OIDC scope grant/deny, so
     * there is no scope string for a malicious/misconfigured home server to even attempt to smuggle
     * a vote-weight claim through.
     */
    val ALL = listOf(OPENID, PROFILE_BASIC, MEMBERSHIP_STATUS, PZB_READ, PZB_COMMENT, PZB_POST_PAID)

    /** Always granted regardless of what the caller requests -- the minimum viable "who is this guest" scope set. */
    val ALWAYS_GRANTED = setOf(OPENID, PROFILE_BASIC, PZB_READ)
}

private val DISCOVERY_JSON = Json { encodeDefaults = true }

/**
 * Builds this server's own `GET /.well-known/openid-configuration` document -- a pure function of
 * [FederationConfig.publicBaseUrl], same derivation style as [FederationConfig.actorUri]/
 * `inboxUri`/`outboxUri`. This server's own OIDC issuer identifier IS [FederationConfig.publicBaseUrl]
 * itself (no separate `/federation/oidc` path segment in the issuer value -- the well-known
 * discovery path is what carries that prefix, per RFC 8414 /.well-known convention).
 */
object OidcDiscoveryDocument {
    fun build(): OidcDiscoveryDto {
        val base = FederationConfig.publicBaseUrl
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
            scopes_supported = OidcScopes.ALL,
            token_endpoint_auth_methods_supported = listOf("client_secret_post"),
            code_challenge_methods_supported = listOf("S256"),
            backchannel_logout_supported = true,
            backchannel_logout_session_supported = false,
        )
    }

    fun toJson(dto: OidcDiscoveryDto): String = DISCOVERY_JSON.encodeToString(OidcDiscoveryDto.serializer(), dto)
}
