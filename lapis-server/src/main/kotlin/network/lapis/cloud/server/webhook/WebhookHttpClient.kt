package network.lapis.cloud.server.webhook

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import network.lapis.cloud.server.federation.FederationIpPinningPlugin
import network.lapis.cloud.server.federation.SafeFederationTarget

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- a hardened [HttpClient] for exactly ONE outbound webhook
 * delivery attempt to [target]. Byte-for-byte the same hardening decisions as
 * `network.lapis.cloud.server.federation.federationHttpClient` (that function's own second
 * consumer -- see its KDoc, updated to name this call site): `followRedirects = false` (a redirect
 * must never carry an already-SSRF-checked request off to an unchecked host -- S18/most common
 * webhook-integration bug in the plan's Stolperfallen list), `expectSuccess = false` (every caller
 * inspects [io.ktor.client.statement.HttpResponse.status] itself), bounded [HttpTimeout] so one
 * unresponsive receiver never stalls [WebhookDeliveryPoller.tick] indefinitely.
 *
 * Installs [FederationIpPinningPlugin] (DNS-rebinding fix -- see that plugin's own KDoc for the
 * full mechanism) with `https { serverName = target.originalHost }`, keeping TLS SNI and
 * certificate hostname verification pointed at the ORIGINAL hostname even though the socket
 * connects to [SafeFederationTarget.pinnedAddress]. **One client per delivery attempt, always
 * `use {}`** (S26 in the plan's Stolperfallen list -- the plugin asserts one client = one host and
 * throws otherwise).
 */
internal fun webhookHttpClient(target: SafeFederationTarget): HttpClient =
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
