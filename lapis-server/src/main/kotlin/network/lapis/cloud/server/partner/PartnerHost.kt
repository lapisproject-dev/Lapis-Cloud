package network.lapis.cloud.server.partner

import network.lapis.cloud.shared.domain.PartnerAddressRejectionCode
import java.net.IDN
import java.net.URI
import java.util.Locale

internal sealed interface PartnerAddressParse {
    data class Ok(
        val host: String,
    ) : PartnerAddressParse

    data class Rejected(
        val reason: PartnerAddressRejectionCode,
    ) : PartnerAddressParse
}

/**
 * V1.9.244 -- turns whatever an admin typed into the ONE canonical form of a partner: a lower-case ASCII (punycode) host name, https and port
 * 443 implied. Pure, no DNS, no network access. The SSRF guard on the resolved ADDRESSES is `checkWebhookUrl` in [PartnerFeedFetcher]; this
 * object only keeps obviously wrong input out early (and gives the admin a precise reason).
 *
 * Tolerant in what it accepts (a path, a query, a fragment, a trailing dot, a scheme-less host are dropped or added), strict in what it keeps.
 * The authority is parsed by hand (no `Url`/`URI` parser differential).
 *
 * **Every IPv4 spelling is an IP literal**: the LAST label of a host name must start with a letter (or be punycode `xn--`) -- that single rule
 * rejects `2130706433`, `0177.0.0.1`, `0x7f.0.0.1` and `127.1` alike, which legacy resolvers all read as 127.0.0.1.
 */
internal object PartnerHost {
    private const val MAX_INPUT_LENGTH = 2048
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private val LOCAL_SUFFIXES =
        listOf(".local", ".internal", ".lan", ".localhost", ".home.arpa", ".test", ".invalid", ".example")

    fun normalize(
        raw: String,
        ownHost: String?,
    ): PartnerAddressParse {
        val trimmed = raw.trim()
        if (trimmed.length > MAX_INPUT_LENGTH) return reject(PartnerAddressRejectionCode.TOO_LONG)
        if (trimmed.isEmpty() ||
            trimmed.any { it.isWhitespace() || it.isISOControl() }
        ) {
            return reject(PartnerAddressRejectionCode.MALFORMED)
        }
        val withScheme =
            when {
                "://" !in trimmed -> "https://$trimmed"
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> return reject(PartnerAddressRejectionCode.NOT_HTTPS)
            }
        // The authority is cut out by hand: java.net.URI refuses non-ASCII (IDN) host names, and a hand-written cut leaves no parser differential
        // between "what we check" and "what we connect to" -- the host that survives checkHost is the only thing ever used afterwards.
        val authority =
            withScheme
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        if (authority.isEmpty() || '\\' in authority) return reject(PartnerAddressRejectionCode.MALFORMED)
        if ('@' in authority) return reject(PartnerAddressRejectionCode.USERINFO)
        if (authority.startsWith("[")) return reject(PartnerAddressRejectionCode.IP_LITERAL)
        val colon = authority.lastIndexOf(':')
        val rawHost =
            if (colon < 0) {
                authority
            } else {
                val port = authority.substring(colon + 1)
                if (port.isEmpty() || !port.all { it in '0'..'9' } || port.length > 5) return reject(PartnerAddressRejectionCode.MALFORMED)
                if (port.toInt() != 443) return reject(PartnerAddressRejectionCode.PORT_NOT_ALLOWED)
                authority.substring(0, colon)
            }
        return checkHost(rawHost = rawHost, ownHost = ownHost)
    }

    /** Variant for blocklist patterns: the same host rules, but the "is this server itself" check is skipped. */
    fun normalizeHostOnly(raw: String): String? = (normalize(raw = raw, ownHost = null) as? PartnerAddressParse.Ok)?.host

    private fun checkHost(
        rawHost: String,
        ownHost: String?,
    ): PartnerAddressParse {
        if (rawHost.startsWith("[") || rawHost.contains(':') || rawHost.contains('%')) return reject(PartnerAddressRejectionCode.IP_LITERAL)
        val unDotted = rawHost.trimEnd('.')
        if (unDotted.isEmpty()) return reject(PartnerAddressRejectionCode.MALFORMED)
        val ascii =
            runCatching { IDN.toASCII(unDotted, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull()
                ?: return reject(PartnerAddressRejectionCode.MALFORMED)
        if (ascii.length > MAX_HOST_LENGTH) return reject(PartnerAddressRejectionCode.TOO_LONG)
        val labels = ascii.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_LENGTH }) return reject(PartnerAddressRejectionCode.MALFORMED)
        if (!ascii.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return reject(PartnerAddressRejectionCode.MALFORMED)
        val last = labels.last()
        if (!(last.first() in 'a'..'z')) return reject(PartnerAddressRejectionCode.IP_LITERAL)
        if (ascii == "localhost" || LOCAL_SUFFIXES.any { ascii.endsWith(it) }) return reject(PartnerAddressRejectionCode.LOCAL_NAME)
        if (labels.size < 2) return reject(PartnerAddressRejectionCode.NO_DOT)
        if (ownHost != null && ascii == ownHost) return reject(PartnerAddressRejectionCode.OWN_HOST)
        return PartnerAddressParse.Ok(ascii)
    }

    private fun reject(reason: PartnerAddressRejectionCode): PartnerAddressParse = PartnerAddressParse.Rejected(reason)

    /** The canonical host of this server, derived from the public base URL (`null` when it cannot be parsed). */
    fun ownHostOf(publicBaseUrl: String): String? =
        runCatching { URI(publicBaseUrl).host }.getOrNull()?.let { host ->
            runCatching { IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull()
        }
}
