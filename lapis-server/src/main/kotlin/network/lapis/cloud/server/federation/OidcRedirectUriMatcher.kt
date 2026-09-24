package network.lapis.cloud.server.federation

import java.net.URI

/**
 * Welle V1.8.1 MCP-Server -- `redirect_uri` matching with loopback port flexibility for public
 * (`token_endpoint_auth_method=none`) clients, needed because an MCP agent running as a local CLI
 * (e.g. Claude Desktop's stdio bridge) opens its own loopback HTTP listener on an OS-assigned
 * ephemeral port that cannot be known at registration time (RFC 8252 §7.3). A confidential client
 * keeps the pre-existing exact-match behaviour unchanged.
 */
internal object OidcRedirectUriMatcher {
    /**
     * `registered` and `presented` must both already be well-formed URIs. Exact match in every
     * case except: [allowLoopbackPortFlexibility] is `true` AND both URIs are loopback
     * ([isLoopbackRedirectUri]) AND scheme/host/path/query/fragment all match -- then the port is
     * ignored. A confidential client (`allowLoopbackPortFlexibility = false`) never gets this
     * relaxation, even if it happens to register a loopback URI.
     */
    fun matches(
        registered: String,
        presented: String,
        allowLoopbackPortFlexibility: Boolean,
    ): Boolean {
        if (registered == presented) return true
        if (!allowLoopbackPortFlexibility) return false
        val registeredUri = runCatching { URI(registered) }.getOrNull() ?: return false
        val presentedUri = runCatching { URI(presented) }.getOrNull() ?: return false
        if (!isLoopbackRedirectUri(registered) || !isLoopbackRedirectUri(presented)) return false
        return registeredUri.scheme == presentedUri.scheme &&
            registeredUri.host == presentedUri.host &&
            (registeredUri.path ?: "") == (presentedUri.path ?: "") &&
            registeredUri.query == presentedUri.query &&
            registeredUri.fragment == presentedUri.fragment
    }

    /**
     * `true` only for a literal `127.0.0.1` or `[::1]` host. **Never `"localhost"`** -- that is a
     * DNS name, not a loopback literal, and resolving it is an attacker-influenceable step (DNS
     * rebinding/hosts-file tampering) this codebase's `requireSafeFederationUrl` already refuses
     * to trust elsewhere for the exact same reason.
     */
    fun isLoopbackRedirectUri(uri: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        if (parsed.scheme != "http") return false
        return parsed.host == "127.0.0.1" || parsed.host == "::1" || parsed.host == "[::1]"
    }
}
