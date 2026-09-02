package network.lapis.cloud.server.embed

import java.net.IDN
import java.net.URI

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- the security-critical core of the whole
 * welle. Canonicalizes and matches the `Origin` values a partner website's embedded widget
 * script and the popup login page are allowed to run from.
 *
 * **Only [java.net.URI] parsing is used here, never `startsWith`/`contains`/regex substring
 * matching** -- a substring check would accept a suffix attack (`https://partei.example.evil.com`
 * "contains" `https://partei.example`) or a prefix attack (`https://evilpartei.example`). Every
 * comparison in [isAllowed]/[canonicalize] runs the SAME [canonicalizeOrigin] on both sides and
 * compares the canonical forms for exact equality -- there is no other comparison operator
 * anywhere in this class.
 *
 * **The value returned to a caller is always the STORED, canonical allowlist entry, never the raw
 * request-supplied string.** This matters for [EmbedCors] (which echoes the canonical form back
 * as `Access-Control-Allow-Origin`) and [network.lapis.cloud.server.routes.EmbedHtml] (which
 * echoes only the canonical entry's host, never the raw `?origin=` query parameter) -- a request
 * can never inject anything into a response header/body through this allowlist, because nothing
 * request-supplied is ever echoed unmodified.
 */
class EmbedOriginAllowlist private constructor(
    /** Canonical forms, in first-mention order, without duplicates. */
    val canonicalOrigins: List<String>,
) {
    /** `true` iff [rawOrigin] canonicalizes to one of [canonicalOrigins]. */
    fun isAllowed(rawOrigin: String?): Boolean = canonicalize(rawOrigin) != null

    /** The canonical allowlist entry matching [rawOrigin], or `null` if it does not canonicalize to any entry. Never returns [rawOrigin] itself. */
    fun canonicalize(rawOrigin: String?): String? {
        if (rawOrigin == null) return null
        val canonical = canonicalizeOrigin(raw = rawOrigin, allowInsecure = true) ?: return null
        // allowInsecure=true above is deliberate: canonicalization here only needs to reach the
        // SAME canonical form the allowlist itself was built with -- whether http:// was ever
        // actually accepted into canonicalOrigins already happened once, at parse() time, gated by
        // the real allowInsecure flag. Re-gating scheme here would just reject an https-only
        // allowlist entry's own http:// request-origin mismatch a second, redundant time; it can
        // never let anything extra through, because the comparison below only matches entries that
        // are ALREADY in canonicalOrigins.
        return canonicalOrigins.firstOrNull { it == canonical }
    }

    fun isEmpty(): Boolean = canonicalOrigins.isEmpty()

    fun isNotEmpty(): Boolean = canonicalOrigins.isNotEmpty()

    val size: Int get() = canonicalOrigins.size

    override fun toString(): String = "EmbedOriginAllowlist(canonicalOrigins=$canonicalOrigins)"

    companion object {
        val EMPTY = EmbedOriginAllowlist(emptyList())

        /** Ceiling on the number of distinct origins a single deployment may allowlist -- an operator error, not a legitimate use case, beyond this. */
        const val MAX_ORIGINS = 32

        /** `253` (max DNS name length) + `16` headroom for `scheme://` + `:port`. */
        const val MAX_ORIGIN_LENGTH = 253 + 16

        data class ParseResult(
            val allowlist: EmbedOriginAllowlist,
            /** Raw, untouched (not canonicalized) entries the parser rejected -- see [EmbedConfig] KDoc for why every entry here is fail-fast. */
            val rejected: List<String>,
        )

        /**
         * Splits [raw] on `,`, trims each element, silently drops empty elements (a trailing comma
         * is not an operator error worth failing the server start over), canonicalizes every
         * remaining element via [canonicalizeOrigin], and deduplicates while preserving
         * first-mention order. An element that fails canonicalization, or that would push the
         * distinct-origin count past [MAX_ORIGINS], is collected into [ParseResult.rejected]
         * (unmodified, as originally written) rather than silently dropped.
         */
        fun parse(
            raw: String?,
            allowInsecure: Boolean,
        ): ParseResult {
            if (raw.isNullOrBlank()) return ParseResult(allowlist = EMPTY, rejected = emptyList())
            val elements = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val canonical = LinkedHashSet<String>()
            val rejected = mutableListOf<String>()
            for (element in elements) {
                val entry = canonicalizeOrigin(raw = element, allowInsecure = allowInsecure)
                if (entry == null) {
                    rejected += element
                    continue
                }
                if (entry in canonical) continue
                if (canonical.size >= MAX_ORIGINS) {
                    rejected += element
                    continue
                }
                canonical += entry
            }
            return ParseResult(allowlist = EmbedOriginAllowlist(canonical.toList()), rejected = rejected)
        }

        /**
         * Parses [raw] as a `java.net.URI` and returns its canonical `scheme://host[:port]` form,
         * or `null` if [raw] fails ANY of the checks below. Used both for configuration parsing
         * ([parse]) and for canonicalizing a request's `Origin` header ([canonicalize]) -- the SAME
         * function for both sides is what makes the equality comparison in [canonicalize]
         * meaningful.
         *
         * Rejection rules, all of them mandatory:
         * - unparsable as a `URI` ([runCatching]).
         * - scheme is not exactly `http`/`https` (case-insensitive on input); `http` is rejected
         *   unless [allowInsecure].
         * - host is missing/blank.
         * - host, after [IDN.toASCII] (browsers send Punycode for a non-ASCII hostname) and
         *   lowercasing, contains anything outside `[a-z0-9.-]` or a bracketed IPv6 literal
         *   (`java.net.URI` already requires the brackets for IPv6, so a lowercased bracketed
         *   literal is accepted as-is once its bracket-stripped content is hex/colon-only).
         * - a userinfo component is present (`https://user@host` is rejected outright).
         * - the path is anything other than empty or `/`, or a query/fragment is present.
         * - the raw origin, after trimming, is blank, `"null"` (browsers send this literal string
         *   as `Origin` for `data:`/`file:`/sandboxed-iframe requests), or `"*"`.
         * - any character `< 0x20`, `0x7F`, `\r`, or `\n` is present anywhere in the raw string
         *   (response-splitting hardening -- this value is later echoed as a response header).
         * - the raw string exceeds [MAX_ORIGIN_LENGTH].
         *
         * The default port for the resolved scheme (443 for https, 80 for http) is stripped; any
         * other port is preserved.
         */
        internal fun canonicalizeOrigin(
            raw: String,
            allowInsecure: Boolean,
        ): String? {
            if (raw.length > MAX_ORIGIN_LENGTH) return null
            if (raw.any { it.code < 0x20 || it.code == 0x7F }) return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed == "*") return null

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && !(scheme == "http" && allowInsecure)) return null

            val path = uri.rawPath.orEmpty()
            if (path.isNotEmpty() && path != "/") return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null

            // java.net.URI parses a non-ASCII (raw Unicode) host as a NULL `host` -- it only
            // recognizes the ASCII "reg-name"/IPv4/bracketed-IPv6 grammar as a server-based
            // authority, per RFC 2396. Browsers always send Punycode in a real Origin header, so
            // this only matters for a hand-typed .env value -- but rather than reject it outright,
            // fall back to parsing `rawAuthority` by hand ([userinfo@]host[:port]) and let
            // IDN.toASCII + isValidCanonicalHost below be the actual gatekeeper either way: garbage
            // that is not a legitimate hostname is rejected there regardless of which path found it.
            val (rawHost, port, hasUserInfo) =
                if (uri.host != null) {
                    Triple(uri.host, uri.port, uri.rawUserInfo != null)
                } else {
                    val authority = uri.rawAuthority ?: return null
                    val atIndex = authority.lastIndexOf('@')
                    val hostAndPort = if (atIndex >= 0) authority.substring(atIndex + 1) else authority
                    val colonIndex = hostAndPort.lastIndexOf(':')
                    val portDigits = if (colonIndex >= 0) hostAndPort.substring(colonIndex + 1) else ""
                    if (colonIndex >= 0 && portDigits.isNotEmpty() && portDigits.all { it.isDigit() }) {
                        Triple(hostAndPort.substring(0, colonIndex), portDigits.toInt(), atIndex >= 0)
                    } else if (colonIndex >= 0) {
                        // a colon present but not followed by pure digits -- not a valid port, reject.
                        return null
                    } else {
                        Triple(hostAndPort, -1, atIndex >= 0)
                    }
                }
            if (hasUserInfo) return null
            if (rawHost.isBlank()) return null
            val asciiHost = runCatching { IDN.toASCII(rawHost) }.getOrNull() ?: return null
            val host = asciiHost.lowercase()
            if (!isValidCanonicalHost(host)) return null

            val defaultPort = if (scheme == "https") 443 else 80
            val portSuffix = if (port == -1 || port == defaultPort) "" else ":$port"

            return "$scheme://$host$portSuffix"
        }

        /** Bracketed IPv6 literal (`[...]`, hex/colon content only) or a plain `[a-z0-9.-]+` DNS-style/IPv4 host. */
        private fun isValidCanonicalHost(host: String): Boolean {
            if (host.startsWith("[") && host.endsWith("]")) {
                val inner = host.substring(1, host.length - 1)
                return inner.isNotEmpty() && inner.all { it.isDigit() || it in 'a'..'f' || it == ':' }
            }
            return host.all { it.isDigit() || it in 'a'..'z' || it == '.' || it == '-' }
        }
    }
}
