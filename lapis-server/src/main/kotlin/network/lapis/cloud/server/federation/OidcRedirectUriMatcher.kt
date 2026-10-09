package network.lapis.cloud.server.federation

import java.net.URI

/**
 * Welle V1.8.1 / V1.9.86 -- `redirect_uri` matching with loopback port flexibility for public
 * (`token_endpoint_auth_method=none`) clients, needed because an MCP agent running as a local CLI
 * (e.g. Claude Code, Claude Desktop's stdio bridge) opens its own loopback HTTP listener on an
 * OS-assigned ephemeral port that cannot be known at registration time (RFC 8252 §7.3). A
 * confidential client keeps the pre-existing exact-match behaviour unchanged.
 *
 * Accepted loopback hosts (exact, case-sensitive): `127.0.0.1`, `[::1]` and, since V1.9.86,
 * `localhost`. RFC 8252 §8.3 advises against `localhost` because name resolution may be
 * manipulated (hosts file, local resolver). It is accepted nevertheless because Claude Code and
 * many other local MCP clients register `http://localhost:<port>/callback`. The residual risk is
 * mitigated by mandatory S256 PKCE (a stolen code is useless without the verifier), a 60 s
 * single-use code, `resource` binding and a member-revocable consent; an attacker able to
 * manipulate name resolution on the member's machine is outside the threat model. `localhost` is
 * never treated as equivalent to `127.0.0.1` / `[::1]`. See `docs/architecture/mcp-server.adoc`.
 */
internal object OidcRedirectUriMatcher {
    internal const val LOCALHOST = "localhost"

    /** Exact, case-sensitive. Pinned by McpStructureTest tripwire. */
    internal val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "[::1]", LOCALHOST)

    private const val MAX_URI_LENGTH = 2048

    internal data class LoopbackRedirect(
        val host: String,
        val port: Int,
        val rawPath: String,
        val rawQuery: String?,
    )

    /** `null` = not an acceptable loopback redirect. */
    internal fun parseLoopback(uri: String): LoopbackRedirect? {
        if (uri.length > MAX_URI_LENGTH) return null
        if (uri.any { it.isWhitespace() || it.isISOControl() || it == '\\' || it == '#' }) return null
        if (!uri.startsWith("http://")) return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (parsed.isOpaque || parsed.scheme != "http") return null
        if (parsed.rawUserInfo != null || parsed.rawFragment != null) return null
        val host = parsed.host ?: return null
        if (host !in LOOPBACK_HOSTS) return null
        val authority = parsed.rawAuthority ?: return null
        val port = parsed.port
        if (authority != host && authority != "$host:$port") return null
        if (authority != host && port !in 1..65535) return null
        val rawPath = parsed.rawPath ?: ""
        if (rawPath.isNotEmpty() && !rawPath.startsWith("/")) return null
        return LoopbackRedirect(host = host, port = port, rawPath = rawPath, rawQuery = parsed.rawQuery)
    }

    fun isLoopbackRedirectUri(uri: String): Boolean = parseLoopback(uri) != null

    /**
     * Exact match in every case except: [allowLoopbackPortFlexibility] is `true` AND both URIs are
     * acceptable loopback redirects ([parseLoopback]) AND host/path/query are identical -- then
     * only the port may differ. A confidential client (`allowLoopbackPortFlexibility = false`)
     * never gets this relaxation, even if it happens to have a loopback URI registered.
     */
    fun matches(
        registered: String,
        presented: String,
        allowLoopbackPortFlexibility: Boolean,
    ): Boolean {
        if (registered == presented) return true
        if (!allowLoopbackPortFlexibility) return false
        val r = parseLoopback(registered) ?: return false
        val p = parseLoopback(presented) ?: return false
        return r.host == p.host && r.rawPath == p.rawPath && r.rawQuery == p.rawQuery
    }
}
