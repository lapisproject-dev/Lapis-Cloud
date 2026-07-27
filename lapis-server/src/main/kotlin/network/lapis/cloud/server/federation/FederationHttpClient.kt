package network.lapis.cloud.server.federation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.net.Inet6Address
import java.net.InetAddress

/** Hard cap on how many bytes of a remote federation HTTP response body are ever read into memory -- see [readCappedFederationBodyOrNull]. */
internal const val MAX_FEDERATION_RESPONSE_BYTES = 64 * 1024

/**
 * SSRF guard for fetching a URL NAMED BY UNTRUSTED INPUT -- either an ADMIN-supplied remote actor
 * URI ([network.lapis.cloud.shared.rpc.IFederationService.initiateFollow]) or the actor URI a
 * self-declared remote server's own inbound `keyId` points at (`FederationRoutes`' inbox handler).
 *
 * **Deliberately a different mechanism from [network.lapis.cloud.server.economy.oracle.OracleHttpClient]'s
 * `requireAllowlistedHttpsUrl`** -- that guard is a FIXED hostname allowlist, correct only because
 * the price-oracle's fetchable host set is small and known at compile time. Federation targets are
 * inherently open-ended (the whole point is fetching a URL named by another organization's admin,
 * or self-declared by an unauthenticated remote server) -- an allowlist of hosts is structurally
 * impossible here. Reused instead: the *shape* of the guard (called immediately before every
 * request, HTTPS-only, [federationHttpClient]'s own `followRedirects = false`/bounded timeouts/
 * capped response read). Changed: this resolves DNS and rejects private/reserved *addresses*, not
 * hostnames -- loopback/link-local/site-local(RFC 1918)/multicast/any-local, the same checklist
 * this project's standing SSRF security-audit item (CLAUDE.md) already names.
 *
 * **KNOWN RESIDUAL RISK (flagged, not silently accepted)**: this check-then-connect has a
 * DNS-rebinding TOCTOU gap -- a host could resolve to a public IP at check-time and to
 * `127.0.0.1` at actual-connect-time. Closing this fully requires pinning the checked IP for the
 * actual HTTP connection (a custom Ktor CIO DNS resolver/connector) -- out of scope for this
 * wave's Grundgerüst; tracked as an open design question.
 *
 * **Live adversarial-test fix**: [Inet6Address.isSiteLocalAddress] only recognizes the DEPRECATED
 * `fec0::/10` IPv6 site-local range (RFC 3879, obsoleted in 2004) -- it does NOT cover the range
 * that actually replaced it, `fc00::/7` Unique Local Addresses (RFC 4193), which is the IPv6
 * equivalent of RFC 1918 private space and the range most container platforms (Docker, Kubernetes)
 * assign internal IPv6 addresses from. Confirmed live during the V0.8.1 attack pass:
 * `requireSafeFederationUrl("https://[fd00::1]/...")` did NOT throw before [isIpv6UniqueLocalAddress]
 * was added below -- an attacker-declared `keyId`/actor URI pointing at an IPv6 ULA address would
 * have been fetched, not refused, on any deployment with IPv6 ULA-addressed internal
 * infrastructure. Checked as an explicit extra condition since the JDK provides no built-in method
 * for this range.
 */
internal fun requireSafeFederationUrl(urlString: String) {
    val url = Url(urlString)
    require(url.protocol == URLProtocol.HTTPS) { "Federation target URL must be HTTPS: $urlString" }
    val addresses =
        runCatching { InetAddress.getAllByName(url.host) }
            .getOrElse { throw IllegalArgumentException("Federation target host could not be resolved: ${url.host}") }
    require(addresses.isNotEmpty()) { "Federation target host resolved to no addresses: ${url.host}" }
    addresses.forEach { addr ->
        require(
            !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isSiteLocalAddress &&
                !addr.isMulticastAddress &&
                !addr.isAnyLocalAddress &&
                !isIpv6UniqueLocalAddress(addr),
        ) { "Federation target host resolves to a private/reserved address: ${url.host} -> $addr" }
    }
}

/**
 * `true` iff [addr] is an IPv6 Unique Local Address (RFC 4193, `fc00::/7`) -- i.e. its first byte's
 * top 7 bits equal `1111_110`, matching both the `fc00::/8` (locally assigned, no L-bit-clear
 * historical distinction relevant here) and `fd00::/8` halves of the range. See
 * [requireSafeFederationUrl] KDoc "Live adversarial-test fix" for why this cannot simply reuse
 * [Inet6Address.isSiteLocalAddress].
 */
internal fun isIpv6UniqueLocalAddress(addr: InetAddress): Boolean {
    if (addr !is Inet6Address) return false
    val firstByte = addr.address[0].toInt() and 0xFF
    return (firstByte and 0xFE) == 0xFC
}

/**
 * A hardened [HttpClient] for every outbound federation fetch (remote actor-document GET, remote
 * inbox POST) -- mirrors [network.lapis.cloud.server.economy.oracle.oracleHttpClient]'s own
 * hardening choices (`followRedirects = false` so no redirect can carry a request off an
 * already-SSRF-checked host, `expectSuccess = false` so every call site inspects
 * [HttpResponse.status] itself, bounded [HttpTimeout] so one unresponsive remote server can never
 * stall a caller indefinitely).
 */
internal fun federationHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 5_000
        }
        expectSuccess = false
        followRedirects = false
    }

/**
 * Reads [this] response's body bounded to [MAX_FEDERATION_RESPONSE_BYTES] -- never buffers more
 * than that many bytes regardless of how large (or how long-streaming) the actual response is.
 * Returns `null` if the cap is hit (the body is discarded, not partially parsed) -- mirrors
 * [network.lapis.cloud.server.economy.oracle.readCappedBodyOrNull]'s own contract.
 */
internal suspend fun HttpResponse.readCappedFederationBodyOrNull(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_FEDERATION_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_FEDERATION_RESPONSE_BYTES) null else buffer.copyOf(total)
}

/**
 * SSRF-guarded fetch + parse of a remote ActivityPub actor document -- shared by
 * [network.lapis.cloud.server.rpc.FederationService] ([network.lapis.cloud.shared.rpc.IFederationService.initiateFollow])
 * and [network.lapis.cloud.server.routes.registerFederationRoutes]' inbox handler (resolving a
 * signer's public key), so both call sites apply the exact same [requireSafeFederationUrl] guard
 * and [MAX_FEDERATION_RESPONSE_BYTES] cap. `null` on ANY failure (non-HTTPS/private-range host,
 * unreachable, non-2xx, oversized, unparseable JSON) -- never throws.
 */
internal suspend fun fetchActorDocument(actorUri: String): ActorDocument? =
    runCatching {
        requireSafeFederationUrl(actorUri)
        federationHttpClient().use { client ->
            val response = client.get(actorUri) { header(HttpHeaders.Accept, ACTIVITY_JSON_CONTENT_TYPE) }
            if (!response.status.isSuccess()) return@use null
            val bytes = response.readCappedFederationBodyOrNull() ?: return@use null
            FEDERATION_JSON.decodeFromString(ActorDocument.serializer(), bytes.toString(Charsets.UTF_8))
        }
    }.getOrNull()
