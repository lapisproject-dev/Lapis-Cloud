package network.lapis.cloud.server.ai.config

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * SSRF guard for the operator-configured LLM base URL.
 *
 * The base URL comes **only** from `LAPIS_AI_BASE_URL`/the provider default, is validated once at
 * startup by [validateOrNull], and is pinned in a `val` -- it is never derived from user input or
 * document content. [requireAllowedRequestUrl] re-checks every concrete request URL against the
 * pinned base immediately before the HTTP call (defence in depth against a future edit that builds
 * a URL from untrusted data).
 *
 * **Allowlist OR explicitly configured URL.** [KNOWN_PROVIDER_HOSTS] lists the hosts this layer is
 * tested against, but an operator may point the client at any other HTTPS endpoint (an EU-hosted or
 * self-hosted gateway) by setting `LAPIS_AI_BASE_URL` explicitly -- blocking that would defeat the
 * point of a provider-independent abstraction. Such a host only draws an INFO hint at startup.
 *
 * **No DNS resolution here** (unlike `OutboundUrlGuard`, which resolves per request for webhook
 * targets): the URL is operator configuration validated once and pinned, so there is no
 * attacker-controlled name that could be re-pointed between check and use. Literal private/loopback/
 * link-local addresses and `localhost` names are rejected; the single exception is plain-HTTP
 * loopback for a local Ollama, which needs the explicit `LAPIS_AI_ALLOW_PLAINTEXT_BASE_URL=true`.
 */
internal object AiBaseUrlGuard {
    /** Hosts this layer is documented/tested against; any other HTTPS host is allowed but flagged at startup. */
    val KNOWN_PROVIDER_HOSTS: Set<String> =
        setOf("api.anthropic.com", "api.openai.com", "api.mistral.ai", "openrouter.ai")

    private const val MAX_BASE_URL_LENGTH = 200
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost")

    /**
     * Returns the normalized base URL (no trailing slash) or `null` if [raw] is not acceptable. Never throws.
     */
    fun validateOrNull(
        raw: String,
        allowPlaintextLoopback: Boolean,
    ): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_BASE_URL_LENGTH) return null
        if (trimmed.any { it.code < 0x21 || it.code == 0x7f }) return null // control chars and whitespace
        if (trimmed.contains('?') || trimmed.contains('#') || trimmed.contains('@')) return null
        val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
        val host = normalizedHost(url) ?: return null
        val isLoopback = host in LOOPBACK_HOSTS
        val schemeOk =
            when (url.protocol) {
                URLProtocol.HTTPS -> true
                URLProtocol.HTTP -> allowPlaintextLoopback && isLoopback
                else -> false
            }
        if (!schemeOk) return null
        val loopbackException = url.protocol == URLProtocol.HTTP && allowPlaintextLoopback && isLoopback
        if (!loopbackException && isBlockedHost(host)) return null
        val hostPart = if (host.contains(':')) "[$host]" else host
        val portPart = if (url.port != url.protocol.defaultPort) ":${url.port}" else ""
        val path = url.encodedPath.trimEnd('/')
        return "${url.protocol.name}://$hostPart$portPart$path"
    }

    /** `true` iff [normalizedBaseUrl]'s host is one of [KNOWN_PROVIDER_HOSTS]. */
    fun isKnownProviderHost(normalizedBaseUrl: String): Boolean =
        runCatching { Url(normalizedBaseUrl).host }.getOrNull() in KNOWN_PROVIDER_HOSTS

    /** Host part only (no scheme/path/query) -- the one URL fragment that is safe to log. */
    fun hostOf(normalizedBaseUrl: String): String? = runCatching { Url(normalizedBaseUrl).host }.getOrNull()

    /**
     * Throws [IllegalArgumentException] unless [urlString] starts with the pinned [pinnedBaseUrl]
     * and shares scheme, host and port with it. The message never contains the URL.
     */
    fun requireAllowedRequestUrl(
        urlString: String,
        pinnedBaseUrl: String,
    ) {
        val request = runCatching { Url(urlString) }.getOrNull()
        val pinned = runCatching { Url(pinnedBaseUrl) }.getOrNull()
        require(request != null && pinned != null) { "AI request URL is not parseable" }
        require(request.protocol == pinned.protocol) { "AI request scheme differs from the pinned base URL" }
        require(request.host.equals(pinned.host, ignoreCase = true) && request.port == pinned.port) {
            "AI request host differs from the pinned base URL"
        }
        require(urlString.startsWith("$pinnedBaseUrl/")) { "AI request path is outside the pinned base URL" }
    }

    private fun normalizedHost(url: Url): String? {
        val host = url.host.trim('[', ']').lowercase()
        return host.ifEmpty { null }
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        // IPv6 literals: rejected wholesale (loopback exception is handled by the caller).
        if (host.contains(':')) return true
        val octets = host.split('.')
        val isIpv4Literal = octets.size == 4 && octets.all { part -> part.isNotEmpty() && part.all { it.isDigit() } && part.length <= 3 }
        // Numeric-looking hosts that are not a plain dotted quad ("2130706433", "0x7f.1", "1.2.3") are
        // legacy IP notations some resolvers accept -- rejected outright rather than interpreted.
        val numericLike = host.all { it.isDigit() || it == '.' } || host.startsWith("0x")
        if (!isIpv4Literal) return numericLike
        val a = octets[0].toInt()
        val b = octets[1].toInt()
        return a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
}
