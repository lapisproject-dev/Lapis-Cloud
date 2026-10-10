package network.lapis.cloud.server.federation

import java.net.URLEncoder

/**
 * V1.9.99 -- the Relying Party side of guest federation: pure builders for the outgoing
 * `/authorize` URL and for the guest scope that gets persisted. Extracted from `OidcRoutes` so both
 * are unit-testable without the network hop (`/rp/login` fetches Discovery before it ever gets here).
 *
 * The RP asks for [OidcScopes.FEDERATION] only. MCP scopes are for MCP clients; the Issuer rejects
 * any request that mixes them with federation scopes, so asking for the Issuer's full scope set made every
 * guest login fail with HTTP 400.
 */
internal object OidcGuestAuthorizeRequest {
    /** Builds the RP's outgoing guest-federation authorize URL; scope = [OidcScopes.FEDERATION] only. */
    fun buildAuthorizeUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        nonce: String,
    ): String {
        val scope = OidcScopes.FEDERATION.joinToString(" ")
        return buildString {
            append(authorizationEndpoint)
            append("?response_type=code")
            append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
            append("&scope=").append(URLEncoder.encode(scope, "UTF-8"))
            append("&state=").append(URLEncoder.encode(state, "UTF-8"))
            append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
            append("&code_challenge_method=S256")
            append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
        }
    }

    /**
     * Home server's returned scope intersected with [OidcScopes.FEDERATION], de-duplicated, in
     * FEDERATION order; [OidcScopes.ALWAYS_GRANTED] if empty. Structurally bounded in length.
     */
    fun grantedScope(tokenResponseScope: String): String {
        val returned = tokenResponseScope.split(" ").filter { it.isNotBlank() }.toSet()
        return OidcScopes.FEDERATION
            .filter { it in returned }
            .joinToString(" ")
            .ifBlank { OidcScopes.ALWAYS_GRANTED.joinToString(" ") }
    }
}
