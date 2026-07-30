package network.lapis.cloud.server.federation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
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
 * Outcome of a successful [requireSafeFederationUrl] check -- the ORIGINAL hostname (needed
 * afterwards for the wire `Host:` header, TLS SNI, and certificate hostname verification) and the
 * SINGLE [InetAddress] the actual connection is pinned to. See [requireSafeFederationUrl] KDoc
 * ("DNS-rebinding TOCTOU gap CLOSED") for why exactly one address, never re-resolved, is what
 * closes the DNS-rebinding gap.
 */
internal data class SafeFederationTarget(
    val originalHost: String,
    val pinnedAddress: InetAddress,
)

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
 * **DNS-rebinding TOCTOU gap CLOSED (this wave)**: previously this function only validated and
 * threw/returned `Unit`, and the actual HTTP request that followed let Ktor's CIO engine perform
 * its OWN, independent, later DNS resolution -- a malicious resolver could answer safely here and
 * unsafely at actual-connect-time (`127.0.0.1`, a cloud metadata endpoint, an internal service).
 * This function now RETURNS the specific [InetAddress] it validated, as a [SafeFederationTarget].
 * [federationHttpClient] pins the actual TCP connection to exactly that address via
 * [FederationIpPinningPlugin] -- see that plugin's KDoc for the mechanism and its evidence trail.
 * There is no second, independent resolution anywhere downstream of this function's return value.
 *
 * **Why pin to ONE address, not "any of the validated addresses"**: [InetAddress.getAllByName]
 * can return several addresses for one hostname. ALL of them must still be safe (kept from the
 * original design, deliberately not relaxed to "at least one") -- a resolver answering with one
 * safe and one unsafe address for the same name is itself untrustworthy, and this guard's entire
 * job is to not trust attacker-influenced input. Of the (all-safe) candidates, the first is pinned
 * and returned; there is no fallback to a sibling candidate on connect failure -- this project has
 * no retry/failover precedent for federation fetches (see [OidcBackChannelLogoutNotifier] KDoc,
 * "no retry queue this wave"), and a single deterministic target keeps the guarantee auditable: one
 * resolution, one address, one connection attempt, no ambiguity about which candidate was used.
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
internal fun requireSafeFederationUrl(urlString: String): SafeFederationTarget {
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
    return SafeFederationTarget(originalHost = url.host, pinnedAddress = addresses[0])
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

/** Ktor client-plugin config: the single [SafeFederationTarget] a client instance is pinned to -- see [FederationIpPinningPlugin]. */
internal class FederationIpPinningConfig {
    lateinit var target: SafeFederationTarget
}

/**
 * THE DNS-rebinding fix: rewrites the outgoing request's URL host from the original hostname to
 * [SafeFederationTarget.pinnedAddress]'s literal address string, immediately before the request
 * reaches the CIO engine -- and sets an explicit `Host:` header to the ORIGINAL hostname so
 * virtual-hosted remote servers still route the request correctly.
 *
 * **Why this closes the gap (evidence, not assumption -- read against the actual Ktor 3.5.1 JVM
 * sources for this project's pinned version, `io.ktor:ktor-client-cio` etc.)**:
 * 1. `CIOEngineConfig` (`io.ktor.client.engine.cio.CIOEngineConfig`) exposes only `endpoint {}`,
 *    `https {}` (`TLSConfigBuilder`), `maxConnectionsCount`, `requestTimeout` -- there is genuinely
 *    no public DNS-resolver/connector hook to override. The classes that actually resolve and
 *    connect (`Endpoint`, `ConnectionFactory`) are `internal`, invisible outside the
 *    `ktor-client-cio` module. So there is no cleaner supported extension point than the one used
 *    here.
 * 2. `Endpoint.connect()` builds `InetSocketAddress(host, port)` where `host` is exactly
 *    `request.url.host` (via `CIOEngine.selectEndpoint`) -- rewriting that field before the engine
 *    sees the request is therefore sufficient to control what the engine connects to.
 * 3. `io.ktor.network.sockets.InetSocketAddress(hostname, port)` (the JVM `actual`) is a direct,
 *    undocumented-side-effect-free passthrough to `java.net.InetSocketAddress(hostname, port)` --
 *    its own KDoc states "This conversion does not perform any DNS lookups". `java.net.InetSocketAddress`
 *    recognizes a literal IPv4/IPv6 address string (bracketed or not) and resolves it locally,
 *    without a network DNS query -- confirmed empirically on this machine's JDK via `jshell`
 *    (`isUnresolved()` is `false` immediately for a literal-IP hostname string, no network I/O).
 *    So rewriting `request.url.host` to the pinned [InetAddress]'s literal string guarantees CIO's
 *    own connect path performs ZERO further resolution.
 * 4. CIO's `writeHeaders()` only derives a `Host` header from `url.host` `if (!headers.contains(HttpHeaders.Host))`
 *    -- an explicit `Host` header we set ourselves is honored verbatim and never overwritten.
 *    `HttpHeaders.Host` is not in Ktor's `UnsafeHeadersList` (`Transfer-Encoding`, `Upgrade` only),
 *    so setting it explicitly does not throw `UnsafeHeaderException`.
 * 5. TLS stays pointed at the REAL hostname, not the pinned IP: `Endpoint.connect()`'s handshake
 *    block does `serverName = serverName ?: realAddress.hostname` -- an explicitly-configured
 *    `serverName` (set via `CIOEngineConfig.https.serverName`, see [federationHttpClient]) is never
 *    overwritten by the connected (IP-literal) socket address. `TLSClientHandshake` then runs
 *    `if (config.serverName != null) verifyHostnameInCertificate(config.serverName, serverCertificate)`
 *    -- hostname verification runs against the explicitly-set `serverName`, i.e. the ORIGINAL
 *    hostname, not the socket address. `verifyHostnameInCertificate`/`matchHostnameWithCertificate`
 *    do real X.509 SAN matching (with wildcard support) and throw `TLSException` on mismatch --
 *    **TLS certificate validation is therefore preserved end to end, only re-targeted at the
 *    correct name**; it is not weakened or bypassed by this plugin. See [FederationIpPinningTest]
 *    for a real self-signed-certificate test proving both the happy path (SNI'd hostname covered by
 *    the cert even though the socket target is a loopback IP) and the failure path (a hostname
 *    mismatch is still rejected).
 *
 * Only public, documented, stable Ktor APIs are touched by this mechanism (`createClientPlugin`,
 * `HttpRequestBuilder`, `CIOEngineConfig.https.serverName`) -- no internal/reflection access.
 *
 * The `Host` header replicates CIO's own default port-suffix logic (`writeHeaders`: omit the port
 * only when it's the protocol default) so a non-standard-port federation target still gets a
 * correct `Host` header.
 *
 * **Why not swap engines to `ktor-client-java`** (considered, rejected): `java.net.http.HttpClient`
 * doesn't expose a DNS-resolver hook either -- the same "URL-host-is-a-literal-IP" trick would
 * still be required, so an engine swap buys nothing new here. It would add an unprecedented
 * dependency to this codebase and require redoing this entire verification pass against a
 * different engine's Host-header-override and connection-reuse/HTTP-2 behavior, for no additional
 * robustness over the CIO-based fix above.
 */
internal val FederationIpPinningPlugin =
    createClientPlugin("FederationIpPinning", ::FederationIpPinningConfig) {
        val target = pluginConfig.target
        onRequest { request: HttpRequestBuilder, _ ->
            check(request.url.host.equals(target.originalHost, ignoreCase = true)) {
                "FederationIpPinningPlugin misuse: client built for host '${target.originalHost}' " +
                    "received a request for host '${request.url.host}' -- every " +
                    "federationHttpClient(target) instance must be used for exactly one request, " +
                    "to the SAME host it was built for"
            }
            // request.url is a mutable URLBuilder, NOT the immutable Url CIO's own writeHeaders()
            // reads from -- URLBuilder.port stays the raw DEFAULT_PORT sentinel (0) for an
            // unspecified port, it is NOT auto-normalized to the protocol's default port the way
            // Url.port's getter normalizes it (Url.kt: `specifiedPort.takeUnless { it == DEFAULT_PORT }
            // ?: protocol.defaultPort`). Replicating that same normalization here is required --
            // without it, every ordinary request (no explicit port, the overwhelming common case)
            // would get an incorrect "host:0" Host header instead of just "host".
            val effectivePort = request.url.port.takeUnless { it == 0 } ?: request.url.protocol.defaultPort
            val hostHeaderValue =
                if (effectivePort == request.url.protocol.defaultPort) {
                    target.originalHost
                } else {
                    "${target.originalHost}:$effectivePort"
                }
            request.headers.append(HttpHeaders.Host, hostHeaderValue)
            // Literal address string -- verified (jshell + Ktor's own InetSocketAddress JVM
            // passthrough KDoc) to trigger zero DNS resolution for either IPv4 or IPv6. IPv6
            // literals are bracketed to match Ktor's own Url.host convention for a parsed
            // "https://[::1]:443/..." URL (URLParser.fillHost keeps the brackets as part of the
            // host substring) -- java.net.InetSocketAddress accepts both bracketed and unbracketed
            // IPv6 literal strings without triggering resolution either way, so bracketing here is
            // for internal consistency with Ktor's own representation, not a correctness necessity.
            request.url.host =
                if (target.pinnedAddress is Inet6Address) {
                    "[${target.pinnedAddress.hostAddress}]"
                } else {
                    target.pinnedAddress.hostAddress
                }
        }
    }

/**
 * A hardened [HttpClient] for exactly ONE outbound federation fetch to [target] -- mirrors
 * [network.lapis.cloud.server.economy.oracle.oracleHttpClient]'s own hardening choices
 * (`followRedirects = false` so no redirect can carry a request off an already-SSRF-checked host,
 * `expectSuccess = false` so every call site inspects [HttpResponse.status] itself, bounded
 * [HttpTimeout] so one unresponsive remote server can never stall a caller indefinitely).
 *
 * Installs [FederationIpPinningPlugin] (see its KDoc for the DNS-rebinding-fix mechanism) and
 * explicitly configures `https { serverName = target.originalHost }` -- this is what keeps TLS SNI
 * and certificate hostname verification pointed at the ORIGINAL hostname even though the actual
 * socket connects to [SafeFederationTarget.pinnedAddress]. Every caller MUST obtain [target] from
 * [requireSafeFederationUrl] and use the returned client for exactly one request to that exact
 * host -- [FederationIpPinningPlugin] asserts this at request time.
 */
internal fun federationHttpClient(target: SafeFederationTarget): HttpClient =
    HttpClient(CIO) {
        engine {
            https {
                serverName = target.originalHost
            }
        }
        install(FederationIpPinningPlugin) { this.target = target }
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
        val target = requireSafeFederationUrl(actorUri)
        federationHttpClient(target).use { client ->
            val response = client.get(actorUri) { header(HttpHeaders.Accept, ACTIVITY_JSON_CONTENT_TYPE) }
            if (!response.status.isSuccess()) return@use null
            val bytes = response.readCappedFederationBodyOrNull() ?: return@use null
            FEDERATION_JSON.decodeFromString(ActorDocument.serializer(), bytes.toString(Charsets.UTF_8))
        }
    }.getOrNull()
