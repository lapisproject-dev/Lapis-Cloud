package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

private val logger = KotlinLogging.logger {}

/** Result of [PauseFeedTargetPolicy.check]. */
internal sealed interface PauseFeedTargetCheck {
    data class Ok(
        val url: String,
    ) : PauseFeedTargetCheck

    data class Rejected(
        val reason: RejectReason,
    ) : PauseFeedTargetCheck
}

/** Why a target gets no pause feed. Logged WITHOUT the URL. */
internal enum class RejectReason { SCHEME, FORBIDDEN_CHARACTER, UNRESOLVABLE, PRIVATE_ADDRESS, TLS_UNVERIFIABLE }

/** Test seam: resolves ALL addresses of a host. The default is [InetAddress.getAllByName]. */
internal fun interface HostResolver {
    fun resolveAll(host: String): List<InetAddress>

    companion object {
        val SYSTEM = HostResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/**
 * V1.9.255 -- decides whether the SERVER may open a connection to a stream target for the pause feed.
 *
 * Unlike the egress start (where the LiveKit egress container opens the connection, so a private target such as an Owncast in the own network
 * is fine), the pause feed is opened by the server container itself, which sits in the internal Docker network next to the database, LiveKit and
 * Redis. A target that resolves to a loopback, link-local (cloud metadata), multicast, "any", unique-local, RFC 1918 or carrier-grade-NAT
 * address therefore gets NO feed. EVERY resolved address must be public: a name that resolves to a public and a private address is rejected.
 *
 * Known remaining risk (documented): ffmpeg resolves the name again when it connects (DNS rebinding between this check and the connect).
 *
 * The URL is a command line argument (never a shell), but `|`, `[`, `]`, `\`, whitespace and control characters are refused because they are
 * syntax of the `tee` muxer's slave list.
 */
internal class PauseFeedTargetPolicy(
    private val resolver: HostResolver = HostResolver.SYSTEM,
    private val allowRtmps: Boolean,
) {
    fun check(plaintextUrl: String): PauseFeedTargetCheck {
        if (plaintextUrl.any { it in FORBIDDEN_CHARS || it.isWhitespace() || it.isISOControl() }) {
            return PauseFeedTargetCheck.Rejected(RejectReason.FORBIDDEN_CHARACTER)
        }
        val uri = runCatching { URI(plaintextUrl) }.getOrNull() ?: return PauseFeedTargetCheck.Rejected(RejectReason.FORBIDDEN_CHARACTER)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "rtmp" && scheme != "rtmps") return PauseFeedTargetCheck.Rejected(RejectReason.SCHEME)
        if (scheme == "rtmps" && !allowRtmps) return PauseFeedTargetCheck.Rejected(RejectReason.TLS_UNVERIFIABLE)
        val host =
            uri.host
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.takeIf { it.isNotBlank() }
                ?: return PauseFeedTargetCheck.Rejected(RejectReason.UNRESOLVABLE)
        if (isAmbiguousNumericHost(host)) return PauseFeedTargetCheck.Rejected(RejectReason.FORBIDDEN_CHARACTER)
        val addresses =
            try {
                resolver.resolveAll(host)
            } catch (_: UnknownHostException) {
                return PauseFeedTargetCheck.Rejected(RejectReason.UNRESOLVABLE)
            } catch (e: Exception) {
                logger.debug { "pause feed: target resolution failed (${e::class.simpleName})" }
                return PauseFeedTargetCheck.Rejected(RejectReason.UNRESOLVABLE)
            }
        if (addresses.isEmpty()) return PauseFeedTargetCheck.Rejected(RejectReason.UNRESOLVABLE)
        if (addresses.any { isNonPublic(it) }) return PauseFeedTargetCheck.Rejected(RejectReason.PRIVATE_ADDRESS)
        return PauseFeedTargetCheck.Ok(plaintextUrl)
    }

    companion object {
        private val FORBIDDEN_CHARS = setOf('|', '[', ']', '\\')

        private val CANONICAL_IPV4 =
            Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
        private val NUMERIC_LABEL = Regex("""^(0[xX][0-9a-fA-F]*|\d+)$""")

        /**
         * `true` for a host that glibc `inet_aton` (what ffmpeg uses) would read as an IPv4 address but that is not a strict canonical dotted quad:
         * octal (`0177.0.0.1`), hex (`0x7f.1`), short (`127.1`) and single-integer (`2130706433`) forms. Java reads these differently (leading
         * zeros as decimal) and would check a different address than ffmpeg connects to. By the `inet_aton` rule a host is numeric when its LAST
         * label is numeric. IPv6 literals (colon) are left to the resolver.
         */
        fun isAmbiguousNumericHost(host: String): Boolean {
            if (host.contains(':')) return false
            val last = host.trimEnd('.').substringAfterLast('.')
            if (!NUMERIC_LABEL.matches(last)) return false
            return !CANONICAL_IPV4.matches(host)
        }

        /** `true` for every address the server must not connect to. */
        fun isNonPublic(address: InetAddress): Boolean {
            if (address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isMulticastAddress ||
                address.isAnyLocalAddress
            ) {
                return true
            }
            if (address.isSiteLocalAddress) return true
            val b = address.address.map { it.toInt() and 0xFF }
            return when (address) {
                is Inet4Address ->
                    b[0] == 0 ||
                        (b[0] == 100 && b[1] in 64..127) ||
                        (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
                        (b[0] == 198 && b[1] in 18..19) ||
                        b[0] >= 240
                is Inet6Address -> (b[0] and 0xFE) == 0xFC || (b[0] == 0xFE && (b[1] and 0xC0) == 0xC0)
                else -> true
            }
        }
    }
}
