package network.lapis.cloud.server.webhook

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import network.lapis.cloud.server.federation.SafeFederationTarget
import network.lapis.cloud.server.federation.isIpv6UniqueLocalAddress
import java.net.Inet4Address
import java.net.InetAddress

/** Design-Team decision D6 -- the four (and ONLY four) rejection reasons a caller ever sees. */
internal enum class WebhookUrlRejectionReason { NOT_HTTPS, MALFORMED, NOT_PUBLICLY_ROUTABLE, TOO_LONG }

internal sealed interface WebhookUrlCheck {
    data class Ok(
        val target: SafeFederationTarget,
    ) : WebhookUrlCheck

    data class Rejected(
        val reason: WebhookUrlRejectionReason,
    ) : WebhookUrlCheck
}

/** [checkWebhookUrl]'s length ceiling -- matches `webhook_endpoint.url VARCHAR(2048)`. */
private const val MAX_WEBHOOK_URL_LENGTH = 2048

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- SSRF guard for a webhook URL an ADMIN/BOARD member
 * supplies. Checks [raw] and returns the ONE pinned address on success, exactly the same
 * `SafeFederationTarget`/`FederationIpPinningPlugin` mechanism
 * `network.lapis.cloud.server.federation.FederationHttpClient` already established -- see
 * [webhookHttpClient] for the second consumer of that same plugin.
 *
 * **Deliberately NOT `requireSafeFederationUrl`, reused directly**: that function is HTTPS-hard
 * (does not support [allowInsecureHttp]'s dev-only opt-out), throws `IllegalArgumentException`
 * instead of returning a typed result, and -- critically -- ITS EXCEPTION MESSAGE INCLUDES THE
 * RESOLVED HOST/ADDRESS (`"...host resolves to a private/reserved address: ${url.host} -> $addr"`).
 * Design-Team decision D6 requires the OPPOSITE: never leak an IP, hostname, or DNS-resolution
 * detail back to the caller -- only which of the four fixed [WebhookUrlRejectionReason]s applied.
 * Reusing that function here would either require silently swallowing its message (fragile -- a
 * future edit to that message could reintroduce the leak unnoticed) or catching-and-discarding,
 * neither of which is as safe as this function never constructing the leaking string in the first
 * place.
 *
 * **What IS reused**: [isIpv6UniqueLocalAddress] (already `internal` in the federation package --
 * called directly, not copied) and [SafeFederationTarget]/`FederationIpPinningPlugin` (via
 * [webhookHttpClient]) -- both pure mechanics with no federation-specific semantics.
 *
 * **Check order**: length -> parse -> scheme -> DNS resolution -> address safety (ALL resolved
 * addresses must be safe, same "a resolver answering with one safe and one unsafe address is
 * itself untrustworthy" reasoning `requireSafeFederationUrl` KDoc gives) -> pin the first.
 *
 * **Address blocklist, widened beyond [network.lapis.cloud.server.federation.requireSafeFederationUrl]'s
 * own list** (S7/S9 in the plan's Stolperfallen list -- an outbound webhook URL is admin-supplied
 * but still worth defending in depth against a compromised/malicious admin account or a copy-paste
 * mistake pointing at internal infrastructure): loopback/link-local (`169.254.169.254` cloud
 * metadata included)/site-local (RFC 1918)/multicast/any-local/IPv6 ULA (`fc00::/7`) as before,
 * PLUS: IPv4-mapped-IPv6 (`::ffff:a.b.c.d`) is unwrapped to its embedded IPv4 address BEFORE the
 * safety check (otherwise `https://[::ffff:127.0.0.1]/x` would bypass every IPv4-specific
 * predicate), CGNAT (`100.64.0.0/10`), the two IANA "Reserved for future protocol assignments /
 * benchmarking / documentation" ranges most likely to appear in internal tooling
 * (`192.0.0.0/24`, `198.18.0.0/15`), the `240.0.0.0/4` reserved range including the
 * `255.255.255.255` broadcast address, and `0.0.0.0/8`.
 *
 * Called from `network.lapis.cloud.server.rpc.WebhookService.setWebhookUrl` (before EVERY save,
 * Design-Team decision D6) AND from [WebhookDeliverySender.sendOnce] (before EVERY delivery
 * attempt, poller AND test-event alike) -- the DNS answer for the same hostname can differ between
 * the moment a URL was saved and the moment a delivery actually fires (DNS rebinding across time,
 * not just across one connection).
 */
internal fun checkWebhookUrl(
    raw: String,
    allowInsecureHttp: Boolean,
): WebhookUrlCheck {
    if (raw.length > MAX_WEBHOOK_URL_LENGTH) return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.TOO_LONG)

    val url = runCatching { Url(raw) }.getOrElse { return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.MALFORMED) }
    if (url.host.isBlank()) return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.MALFORMED)

    val schemeOk =
        url.protocol == URLProtocol.HTTPS || (allowInsecureHttp && url.protocol == URLProtocol.HTTP)
    if (!schemeOk) return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_HTTPS)

    val addresses =
        runCatching { InetAddress.getAllByName(url.host) }.getOrNull()
            ?: return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)
    if (addresses.isEmpty()) return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)

    val allSafe = addresses.all { isPubliclyRoutable(it) }
    if (!allSafe) return WebhookUrlCheck.Rejected(WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE)

    return WebhookUrlCheck.Ok(SafeFederationTarget(originalHost = url.host, pinnedAddress = addresses[0]))
}

/** See [checkWebhookUrl] KDoc "Address blocklist" for the full rationale of every extra range beyond the federation guard's own list. */
private fun isPubliclyRoutable(addrIn: InetAddress): Boolean {
    val addr = unwrapIpv4MappedOrSelf(addrIn)
    if (addr.isLoopbackAddress ||
        addr.isLinkLocalAddress ||
        addr.isSiteLocalAddress ||
        addr.isMulticastAddress ||
        addr.isAnyLocalAddress ||
        isIpv6UniqueLocalAddress(addr)
    ) {
        return false
    }
    if (addr is Inet4Address) {
        val octets = addr.address.map { it.toInt() and 0xFF }
        val first = octets[0]
        val second = octets[1]
        // 0.0.0.0/8
        if (first == 0) return false
        // 100.64.0.0/10 (CGNAT)
        if (first == 100 && second in 64..127) return false
        // 192.0.0.0/24 (IETF protocol assignments)
        if (first == 192 && second == 0 && octets[2] == 0) return false
        // 198.18.0.0/15 (benchmarking)
        if (first == 198 && (second == 18 || second == 19)) return false
        // 240.0.0.0/4 (reserved, includes 255.255.255.255 broadcast)
        if (first >= 240) return false
    }
    return true
}

/** Unwraps an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) to its embedded [Inet4Address] -- see [checkWebhookUrl] KDoc. Returns [addr] unchanged for anything else. */
private fun unwrapIpv4MappedOrSelf(addr: InetAddress): InetAddress {
    val bytes = addr.address
    if (bytes.size != 16) return addr
    val isV4Mapped =
        (0..9).all { bytes[it] == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    if (!isV4Mapped) return addr
    val v4Bytes = bytes.copyOfRange(12, 16)
    return InetAddress.getByAddress(v4Bytes)
}
