package network.lapis.cloud.server.keycloak

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * Syntactic/host validation for the operator-configured Keycloak `issuerUrl`.
 *
 * Modelled on `network.lapis.cloud.server.ai.config.AiBaseUrlGuard` -- **NOT** on
 * `network.lapis.cloud.server.federation.requireSafeFederationUrl`/`FederationHttpClient`, which is
 * deliberately hostile to private/loopback hosts (correct for the federation feature, where the
 * remote home server is an arbitrary, untrusted third party on the public internet). A self-hosted
 * Keycloak instance is normally reachable only from inside the operator's own network/Docker
 * Compose network, so this guard must be able to accept a private host -- but only when the
 * operator has explicitly opted in via [allowPrivateHost], exactly like `AiBaseUrlGuard`'s
 * `allowPlaintextLoopback` escape hatch for a local Ollama.
 *
 * Called once, from [KeycloakConfig.load] -- the normalized result is pinned into
 * [KeycloakConfig.issuerUrl] and never re-derived from user/request input afterwards.
 * `KeycloakHttpClient`/`KeycloakOidcMetadata` re-check every concrete request URL against this
 * pinned value before each call (defence in depth), exactly like `AiBaseUrlGuard.requireAllowedRequestUrl`.
 */
internal object KeycloakIssuerUrlGuard {
    /** Why [validate] rejected a candidate URL -- named reasons only, the raw value is never echoed back into logs/exceptions by callers. */
    enum class RejectionReason {
        EMPTY_OR_TOO_LONG,
        CONTROL_CHARACTER,
        DISALLOWED_QUERY_FRAGMENT_OR_USERINFO,
        UNPARSEABLE,
        SCHEME_NOT_ALLOWED,
        PRIVATE_HOST_NOT_ALLOWED,
    }

    sealed class Result {
        data class Valid(
            val normalizedUrl: String,
        ) : Result()

        data class Rejected(
            val reason: RejectionReason,
        ) : Result()
    }

    private const val MAX_ISSUER_URL_LENGTH = 200
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost")

    /**
     * Returns the normalized issuer URL (no trailing slash) or a [Result.Rejected] with the reason.
     * Never throws.
     *
     * - HTTPS is always allowed.
     * - Plain HTTP is allowed only for a loopback host AND only with [allowPlaintext] set (mirrors
     *   `AiBaseUrlGuard`'s local-Ollama escape hatch -- here for a Keycloak dev instance on
     *   `localhost`).
     * - A private/RFC1918/link-local/ULA/CGNAT host is allowed only with [allowPrivateHost] set --
     *   this is the opposite default from `AiBaseUrlGuard` (which never allows it at all), because a
     *   self-hosted Keycloak on the operator's own Docker network is the expected common case, not
     *   an attack.
     * - Control characters, `?`, `#`, `@`, and URLs over [MAX_ISSUER_URL_LENGTH] chars are rejected
     *   unconditionally.
     * - A trailing slash is normalized away.
     */
    fun validate(
        raw: String,
        allowPrivateHost: Boolean,
        allowPlaintext: Boolean,
    ): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ISSUER_URL_LENGTH) return Result.Rejected(RejectionReason.EMPTY_OR_TOO_LONG)
        if (trimmed.any { it.code < 0x21 || it.code == 0x7f }) return Result.Rejected(RejectionReason.CONTROL_CHARACTER)
        if (trimmed.contains('?') || trimmed.contains('#') || trimmed.contains('@')) {
            return Result.Rejected(RejectionReason.DISALLOWED_QUERY_FRAGMENT_OR_USERINFO)
        }
        val url = runCatching { Url(trimmed) }.getOrNull() ?: return Result.Rejected(RejectionReason.UNPARSEABLE)
        val host = normalizedHost(url) ?: return Result.Rejected(RejectionReason.UNPARSEABLE)
        val isLoopback = host in LOOPBACK_HOSTS || host.endsWith(".localhost")

        val schemeOk =
            when (url.protocol) {
                URLProtocol.HTTPS -> true
                URLProtocol.HTTP -> allowPlaintext && isLoopback
                else -> false
            }
        if (!schemeOk) return Result.Rejected(RejectionReason.SCHEME_NOT_ALLOWED)

        val loopbackException = url.protocol == URLProtocol.HTTP && allowPlaintext && isLoopback
        if (!loopbackException && !isLoopback && isPrivateHost(host) && !allowPrivateHost) {
            return Result.Rejected(RejectionReason.PRIVATE_HOST_NOT_ALLOWED)
        }
        // A loopback host reached over plain HTTP without the opt-in already failed schemeOk above;
        // a loopback host reached over HTTPS is always fine (same as AiBaseUrlGuard's own carve-out
        // logic would treat it if it allowed HTTPS loopback at all -- here we allow it unconditionally
        // since it is strictly safer than plaintext loopback).

        val hostPart = if (host.contains(':')) "[$host]" else host
        val portPart = if (url.port != url.protocol.defaultPort) ":${url.port}" else ""
        val path = url.encodedPath.trimEnd('/')
        return Result.Valid("${url.protocol.name}://$hostPart$portPart$path")
    }

    /** Convenience wrapper returning `null` on rejection -- used where only the normalized URL matters. */
    fun validateOrNull(
        raw: String,
        allowPrivateHost: Boolean,
        allowPlaintext: Boolean,
    ): String? = (validate(raw = raw, allowPrivateHost = allowPrivateHost, allowPlaintext = allowPlaintext) as? Result.Valid)?.normalizedUrl

    /**
     * Throws [IllegalArgumentException] unless [urlString] starts with the pinned [pinnedIssuerUrl]
     * and shares scheme, host and port with it. The message never contains the URL -- mirrors
     * `AiBaseUrlGuard.requireAllowedRequestUrl`.
     */
    fun requireAllowedRequestUrl(
        urlString: String,
        pinnedIssuerUrl: String,
    ) {
        val request = runCatching { Url(urlString) }.getOrNull()
        val pinned = runCatching { Url(pinnedIssuerUrl) }.getOrNull()
        require(request != null && pinned != null) { "Keycloak request URL is not parseable" }
        require(request.protocol == pinned.protocol) { "Keycloak request scheme differs from the pinned issuer URL" }
        require(request.host.equals(pinned.host, ignoreCase = true) && request.port == pinned.port) {
            "Keycloak request host differs from the pinned issuer URL"
        }
        require(urlString == pinnedIssuerUrl || urlString.startsWith("$pinnedIssuerUrl/")) {
            "Keycloak request path is outside the pinned issuer URL"
        }
    }

    private fun normalizedHost(url: Url): String? {
        val host = url.host.trim('[', ']').lowercase()
        return host.ifEmpty { null }
    }

    /** `true` for RFC1918/link-local/CGNAT/ULA/other non-loopback private hosts -- loopback is handled separately by the caller. */
    private fun isPrivateHost(host: String): Boolean {
        if (host.contains(':')) {
            // IPv6: only the well-known ULA prefix (fc00::/7, i.e. "fc"/"fd" leading hextet) is
            // recognized here -- any other IPv6 literal is treated as public (this guard's job is to
            // gate the common self-hosted-Keycloak case, not to be a general-purpose IP classifier).
            val firstHextet = host.substringBefore(':').lowercase()
            return firstHextet.startsWith("fc") || firstHextet.startsWith("fd")
        }
        val octets = host.split('.')
        val isIpv4Literal = octets.size == 4 && octets.all { part -> part.isNotEmpty() && part.all { it.isDigit() } && part.length <= 3 }
        if (!isIpv4Literal) return false
        val a = octets[0].toIntOrNull() ?: return false
        val b = octets[1].toIntOrNull() ?: return false
        return a == 10 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
}
